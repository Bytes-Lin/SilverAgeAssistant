# 家属通知与远程提醒中台需求

实现状态：中台首版已于 2026-07-17 完成。代码位于 `MiddleServer/app/`，数据库迁移为 `0002_family_commands`，专项验收位于 `MiddleServer/tests/test_family_commands.py`。

## 1. 目标与范围

本需求实现家属通过 FastAPI 中台向已绑定老人设备发送：

- 即时文字通知：老人端收到后作为“家人通知”加入当天的“今日提醒”；
- 一次性提醒：按家属指定的完成截止日期、时间和时区写入老人端本地提醒库；协议继续使用 `scheduled_at` 字段承载截止时间。

首版不实现语音附件、图片、周期重复规则、移动推送和中台定时唤醒。中台不得直连老人端模型服务，也不得保存模型 API Key。

## 2. 可靠性流程

```text
家属 Android
  → REST 创建命令（Idempotency-Key）
  → 中台鉴权并写入 SQLite
  → 返回 command_id / server_sequence
  → 若老人 WebSocket 在线，发送 COMMAND_AVAILABLE 提示
  → 老人 Android 通过 REST 补拉完整命令
  → 以 command_id 幂等写入本地 Room
  → Room 提交成功后 ACK
  → 中台记录设备已存储状态
```

REST 和 SQLite 是事实来源。WebSocket 仅用于降低前台在线时的延迟，不能携带完整通知正文，也不能替代 REST 补拉。

## 3. 服务端数据模型

建议新增 `commands` 表：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | UUID / TEXT PK | `command_id` |
| `server_sequence` | INTEGER UNIQUE | 单进程服务内严格递增 |
| `elder_id` | UUID / TEXT FK | 目标老人档案 |
| `binding_id` | UUID / TEXT FK | 创建时使用的有效绑定关系 |
| `actor_family_id` | UUID / TEXT FK | 发送家属 |
| `command_type` | TEXT | `FAMILY_NOTIFICATION` / `REMOTE_REMINDER` |
| `title` | TEXT nullable | 通知为空；提醒必填 |
| `content` | TEXT | 展示正文 |
| `scheduled_at` | UTC datetime nullable | 通知为空；提醒必填 |
| `timezone` | TEXT | IANA 时区，例如 `Asia/Shanghai` |
| `client_request_id` | UUID | Android 生成的幂等 ID |
| `created_at` | UTC datetime | 服务端创建时间 |
| `expires_at` | UTC datetime nullable | 可选清理时间，不影响审计记录 |

唯一约束：`(actor_family_id, client_request_id)`。同一幂等键与相同请求体重试返回第一次创建的命令；同一键对应不同请求体返回 `409 IDEMPOTENCY_CONFLICT`。

建议新增 `command_receipts` 表：

| 字段 | 类型 | 说明 |
|---|---|---|
| `command_id` | FK | 命令 |
| `device_id` | FK | 接收设备 |
| `ack_type` | TEXT | 首版固定 `STORED` |
| `client_request_id` | UUID | ACK 幂等 ID |
| `stored_at` | UTC datetime | 客户端完成 Room 提交时间 |
| `acked_at` | UTC datetime | 服务端收到 ACK 时间 |

唯一约束：`(device_id, command_id, ack_type)` 和 `(device_id, client_request_id)`。

## 4. 鉴权与权限

- 家属创建命令使用 Bearer access token；access token 过期仍沿用现有 `/auth/refresh`。
- 服务端必须确认 `elder_id` 属于当前家属的有效、未撤销绑定。
- 创建通知或提醒要求 `Helper` 或 `Owner` 权限；不能只信任客户端传入的老人 ID。
- 老人补拉和 ACK 使用 device credential；只能读取该设备当前绑定老人对应的命令。
- 绑定撤销后，家属不能创建新命令。已写入但尚未接收的命令是否继续可见，首版采用“设备绑定仍有效才可补拉”。
- 日志不得记录 Authorization、device credential、完整手机号或完整通知正文。

## 5. API

Base path：`/api/v1`。所有写请求同时携带：

```http
Authorization: Bearer <credential>
Idempotency-Key: <client_request_id>
Content-Type: application/json
```

请求头 `Idempotency-Key` 必须与 JSON 中的 `client_request_id` 相同，否则返回 `400 REQUEST_VALIDATION_ERROR`。

### 5.1 创建即时通知

```http
POST /elders/{elder_id}/commands/notifications
```

```json
{
  "client_request_id": "4b0b32c6-daf1-4b4d-a8fb-75bb0791d337",
  "content": "下午有快递，请留意电话。",
  "created_at": "2026-07-16T08:00:00Z"
}
```

校验：`content` 去除首尾空白后 1～200 个字符。`created_at` 用于客户端发生时间记录，服务端仍生成自己的 `created_at`，允许合理时钟偏差但不能作为排序事实来源。

### 5.2 创建一次性提醒

```http
POST /elders/{elder_id}/commands/reminders
```

```json
{
  "client_request_id": "0f00a5f1-b53d-44f4-b9e7-1562df93dca1",
  "title": "量血压",
  "content": "测量后把结果记下来。",
  "scheduled_at": "2026-07-17T00:30:00Z",
  "timezone": "Asia/Shanghai"
}
```

校验：

- `title` 1～40 个字符；
- `content` 1～200 个字符；
- `scheduled_at` 必须是 UTC ISO 8601，且在当前时间之后、最多一年以内；
- `timezone` 必须是有效 IANA 时区；
- 首版仅允许一次性提醒，不接受 `repeat_rule`。

两个创建接口成功均返回 `201`：

```json
{
  "command_id": "a92b3db8-5553-42e9-b5cc-b44c95203601",
  "elder_id": "b9755bb9-7211-42e3-8fad-66706c916c6a",
  "command_type": "REMOTE_REMINDER",
  "server_sequence": 1025,
  "status": "PENDING",
  "created_at": "2026-07-16T08:00:01Z"
}
```

只有数据库事务提交成功后才能返回成功。WebSocket 投递失败不回滚命令。

### 5.3 老人设备补拉命令

```http
GET /commands/pending?after_sequence=1020&limit=100
Authorization: Bearer <device_credential>
```

`limit` 范围 1～100，默认 100。结果按 `server_sequence` 升序排列：

```json
{
  "commands": [
    {
      "command_id": "a92b3db8-5553-42e9-b5cc-b44c95203601",
      "server_sequence": 1025,
      "elder_id": "b9755bb9-7211-42e3-8fad-66706c916c6a",
      "command_type": "FAMILY_NOTIFICATION",
      "title": null,
      "content": "下午有快递，请留意电话。",
      "scheduled_at": null,
      "timezone": "Asia/Shanghai",
      "sender": {
        "display_name": "小林"
      },
      "created_at": "2026-07-16T08:00:01Z"
    }
  ],
  "next_after_sequence": 1025,
  "has_more": false
}
```

- 只返回 `server_sequence > after_sequence` 且属于当前设备老人档案的命令；
- `next_after_sequence` 为本页最大序列号，无数据时等于传入值；
- 客户端可能因 ACK 丢失再次请求同一命令，服务端和客户端都必须允许幂等处理；
- 正文只返回给仍有有效绑定的目标老人设备。

### 5.4 确认已写入老人端

```http
POST /commands/{command_id}/ack
```

```json
{
  "client_request_id": "23c237e8-08bf-3fe2-bc93-1d42e81c24cc",
  "ack_type": "STORED",
  "stored_at": "2026-07-16T08:00:03Z"
}
```

成功返回 `200`：

```json
{
  "command_id": "a92b3db8-5553-42e9-b5cc-b44c95203601",
  "status": "STORED",
  "acked_at": "2026-07-16T08:00:04Z"
}
```

只有对应老人设备可以 ACK。重复 ACK 返回同一业务状态，不返回冲突。

## 6. WebSocket 提示

连接地址为 `WS /api/v1/ws`，老人设备通过 `Authorization: Bearer <device_credential>` 请求头认证。凭证不放入 URL 查询参数。

老人设备 WebSocket 在线时，中台在命令写库后发送：

```json
{
  "protocol_version": 1,
  "message_type": "COMMAND_AVAILABLE",
  "message_id": "msg_uuid",
  "server_sequence": 1025,
  "sent_at": "2026-07-16T08:00:02Z",
  "payload": {
    "command_id": "a92b3db8-5553-42e9-b5cc-b44c95203601",
    "command_type": "FAMILY_NOTIFICATION"
  }
}
```

Android 收到后调用补拉接口，不直接把 WebSocket payload 写入提醒库。App 被杀或后台受限时不保证即时送达；下次启动、回到前台或进入今日提醒时通过 REST 补偿。

Android 已接入该 WebSocket 监听：收到 `COMMAND_AVAILABLE`、连接首次建立或重连、应用回到前台时触发 REST 补拉；并发提示由 ViewModel 合并，Room 继续按 `command_id` 去重。App 被杀或后台受限时不保证即时送达，下次前台启动补偿。HTTP 200 只表示补拉请求成功，客户端必须以响应中的命令、Room 写入和 `STORED` ACK 判断实际接收。

## 7. 错误码

| HTTP | `error.code` | 场景 |
|---:|---|---|
| 400 | `INVALID_COMMAND_CONTENT` | 标题、正文或时间字段不合法 |
| 401 | `AUTHENTICATION_REQUIRED` | access token 或 device credential 无效 |
| 403 | `COMMAND_FORBIDDEN` | 无目标老人权限或权限级别不足 |
| 404 | `ELDER_NOT_FOUND` | 当前主体不可见的老人档案统一按不存在处理 |
| 404 | `COMMAND_NOT_FOUND` | 当前设备不可见的命令 |
| 409 | `IDEMPOTENCY_CONFLICT` | 同一幂等键对应不同请求体 |
| 410 | `BINDING_REVOKED` | 绑定已撤销 |
| 429 | `COMMAND_RATE_LIMITED` | 发送频率过高 |

建议限制单家属对单老人每分钟最多 10 条、每天最多 200 条，避免误操作和骚扰。不要在错误信息中泄露其他老人、家属或设备是否存在。

## 8. 中台实现清单

- Alembic：`commands`、`command_receipts`、序列和唯一约束；
- Pydantic schema：通知、提醒、补拉分页、ACK 和错误响应；
- repository：幂等创建、主体过滤、分页补拉、幂等 ACK；
- service：绑定权限、字段校验、限流、事务和事件投递；
- route：保持薄层，不直接写数据库；
- WebSocket：写库后尽力发送 `COMMAND_AVAILABLE`；
- OpenAPI：补齐四个接口、Bearer 两类凭证和响应示例；
- 脱敏审计：记录 command_id、主体、类型和结果，不记录正文。

## 9. 验收测试

- 家属只能向已绑定且有 Helper/Owner 权限的老人创建命令；
- 相同幂等键重试返回相同 `command_id` 和 `server_sequence`；
- 相同幂等键不同正文返回 `IDEMPOTENCY_CONFLICT`；
- 命令先写 SQLite，WebSocket 失败时 REST 仍可补拉；
- 老人设备不能看到其他老人命令；
- 补拉严格按序分页，无遗漏、无越权；
- ACK 仅在命令属于当前设备时成功，重复 ACK 幂等；
- 绑定撤销后禁止创建和补拉；
- access token 过期可通过现有 refresh 流程恢复创建；
- 通知和提醒字段边界、非法时区、过去时间和超频均有测试；
- OpenAPI 变更测试、pytest、Ruff 和类型检查全部通过。
