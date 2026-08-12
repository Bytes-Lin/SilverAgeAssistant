# 提醒完成回传与家属记录中台需求

实现状态（2026-08-12）：完成记录表、设备幂等完成上报、家属稳定游标分页查询、
`PENDING/STORED/COMPLETED` 状态聚合、最小 WebSocket 提示及 Android 自动刷新已完成。
Android 的逐条清除 UI 和 archive 请求映射已经接入；家属账号级幂等归档、GET 查询过滤、
迁移与 OpenAPI/权限验收测试仍待中台交付。REST 查询仍是可靠事实来源，WebSocket 投递失败
不会影响完成记录；归档不得删除老人端提醒、命令回执或完成状态。

## 1. 目标

在现有家属通知与一次性提醒命令链路上补充三个可靠能力：

1. 老人端明确点击“我已完成”后，将一次性提醒的完成确认幂等回传中台；
2. 家属端查询已创建提醒的设备接收状态与老人确认状态；
3. 家属可清除自己视图中的历史记录，但不删除老人端提醒和完成审计数据。

“已完成”只表示老人执行了确认操作，不得推断实际服药、测量或其他行为已经客观发生。
即时通知不是提醒计划，不进入完成状态回传。

## 2. 状态模型

中台继续以 `commands` 中的 `REMOTE_REMINDER` 为提醒主体，并新增提醒完成记录表
`command_completions`：

| 字段 | 类型 | 说明 |
|---|---|---|
| `command_id` | TEXT PK / FK | 仅允许指向 `REMOTE_REMINDER` |
| `elder_id` | TEXT FK | 冗余归属字段，便于强制校验 |
| `device_id` | TEXT FK | 提交完成确认的老人设备 |
| `status` | TEXT | 首版固定 `COMPLETED` |
| `client_request_id` | UUID/TEXT UNIQUE | 老人端稳定幂等 ID |
| `completed_at` | UTC datetime | 老人手机记录确认动作的时间 |
| `reported_at` | UTC datetime | 中台成功接收时间 |

家属记录中的派生状态：

- `delivery_status=PENDING`：中台已创建，尚无 `STORED` receipt；
- `delivery_status=STORED`：老人设备已幂等写入 Room；
- `completion_status=PENDING`：尚无完成确认；
- `completion_status=COMPLETED`：已有合法完成记录。

状态只能单向推进。重复完成上报返回第一次结果，不允许从 `COMPLETED` 回退。

## 3. 老人端上报完成

```http
POST /api/v1/commands/{command_id}/completion
Authorization: Bearer <device_credential>
Idempotency-Key: <client_request_id>
Content-Type: application/json
```

```json
{
  "client_request_id": "7cbd41c8-589c-5aec-b04f-a8b39e1b71aa",
  "status": "COMPLETED",
  "completed_at": "2026-08-11T02:35:00Z"
}
```

成功返回 `200`：

```json
{
  "command_id": "a92b3db8-5553-42e9-b5cc-b44c95203601",
  "status": "COMPLETED",
  "completed_at": "2026-08-11T02:35:00Z",
  "reported_at": "2026-08-11T02:35:03Z"
}
```

校验规则：

- 凭证必须属于该提醒当前绑定的老人设备和老人档案；
- `command_type` 必须为 `REMOTE_REMINDER`，通知返回 `400 COMMAND_NOT_COMPLETABLE`；
- JSON 与 `Idempotency-Key` 的 UUID 必须一致；
- `completed_at` 必须是 UTC ISO 8601，不得显著晚于服务器时间；
- 相同请求 ID 和相同请求体幂等返回；不同请求体返回 `409 IDEMPOTENCY_CONFLICT`；
- 同一提醒被同一合法设备重复确认为完成，返回已有完成状态；
- 绑定撤销、设备被轮换或命令不属于该设备时拒绝，不能泄露其他老人信息。

Android 会先本地提交 Room，再调用本接口；网络失败时保持待同步状态并在启动、进入提醒页、
`COMMAND_AVAILABLE`、WebSocket 连接/重连、应用回到前台或手动刷新触发的下一次同步中
重试。接口失败不得回滚老人端已确认状态。

## 4. 家属查询提醒记录

```http
GET /api/v1/elders/{elder_id}/reminders?limit=100&cursor=<opaque>
Authorization: Bearer <family_access_token>
```

成功返回 `200`：

```json
{
  "reminders": [
    {
      "command_id": "a92b3db8-5553-42e9-b5cc-b44c95203601",
      "title": "量血压",
      "content": "测量后把结果记下来。",
      "scheduled_at": "2026-08-11T00:30:00Z",
      "timezone": "Asia/Shanghai",
      "created_at": "2026-08-10T08:00:01Z",
      "delivery_status": "STORED",
      "completion_status": "COMPLETED",
      "stored_at": "2026-08-10T08:00:03Z",
      "completed_at": "2026-08-11T00:35:00Z"
    }
  ],
  "next_cursor": null
}
```

要求：

- 只返回当前家属与目标老人有效绑定范围内的 `REMOTE_REMINDER`；
- 默认按 `scheduled_at` 倒序，顺序稳定，游标必须是不透明字符串；
- `scheduled_at` 在提醒业务中表示家属设置的完成截止时间；老人端从该时刻开始执行未完成重复提醒；
- `limit` 为 1～100；
- 尚未接收时 `stored_at=null`，尚未完成时 `completed_at=null`；
- access token 过期沿用现有 refresh 流程；
- 家属页面手动刷新立即请求该接口；收到 `REMINDER_STATUS_CHANGED`、WebSocket 连接/重连或应用回到前台也自动请求，不能依赖 WebSocket 缓存；
- 返回提醒正文符合既有绑定授权，但日志和审计不得记录正文。

## 4.1 家属清除（归档）提醒记录

```http
POST /api/v1/elders/{elder_id}/reminders/{command_id}/archive
Authorization: Bearer <family_access_token>
Idempotency-Key: <client_request_id>
Content-Type: application/json
```

```json
{
  "client_request_id": "7cbd41c8-589c-5aec-b04f-a8b39e1b71aa"
}
```

成功返回 `200`：

```json
{
  "command_id": "a92b3db8-5553-42e9-b5cc-b44c95203601",
  "archived": true,
  "archived_at": "2026-08-12T08:30:00Z"
}
```

中台必须新增家属账号级归档关系，建议唯一键为
`(family_account_id, command_id)`，并保存 `elder_id`、`client_request_id` 和
`archived_at`。归档语义如下：

- 只允许与目标老人存在有效绑定且有提醒查看权限的家属调用；
- 只允许归档 `REMOTE_REMINDER`，普通即时通知不属于提醒记录；
- 不物理删除 `commands`、receipt 或 completion，不修改老人端 Room 和催办计划；
- 只对当前家属账号隐藏，同一老人的其他家属仍可查看；
- `GET /elders/{elder_id}/reminders` 默认排除当前账号已经归档的记录；
- 相同幂等键和请求重复调用返回第一次结果；已归档记录再次归档也返回成功；
- 绑定撤销、提醒不属于目标老人或越权访问必须拒绝且不得泄露记录是否存在。

建议错误码：`AUTHENTICATION_REQUIRED`、`COMMAND_FORBIDDEN`、
`COMMAND_NOT_FOUND`、`COMMAND_NOT_ARCHIVABLE`、`IDEMPOTENCY_CONFLICT`、
`BINDING_REVOKED` 和 `REQUEST_VALIDATION_ERROR`。验收测试必须覆盖跨账号隔离、
幂等归档、查询过滤、完成状态不被修改以及老人端仍能读取并完成该提醒。

## 5. WebSocket

完成状态写库后，可向在线家属发送不含正文的轻量提示：

```json
{
  "protocol_version": 1,
  "message_type": "REMINDER_STATUS_CHANGED",
  "message_id": "uuid",
  "sent_at": "2026-08-11T02:35:03Z",
  "payload": {
    "elder_id": "uuid",
    "command_id": "uuid"
  }
}
```

Android 已消费该提示并触发 REST 刷新；应用回到前台和 WebSocket 首次连接/重连时也会补拉。并发刷新会合并，不进行短周期轮询；WebSocket 不传提醒正文。

## 6. 错误码与验收

新增或复用：`AUTHENTICATION_REQUIRED`、`COMMAND_FORBIDDEN`、`COMMAND_NOT_FOUND`、
`COMMAND_NOT_COMPLETABLE`、`IDEMPOTENCY_CONFLICT`、`BINDING_REVOKED`、
`REQUEST_VALIDATION_ERROR`。

中台验收至少覆盖：

- 老人设备不能完成通知、其他老人的提醒或已撤销绑定的提醒；
- 完成上报幂等且状态不可回退；
- 家属只能读取有效绑定老人的提醒；
- `PENDING → STORED → COMPLETED` 聚合正确；
- 记录分页无重复、无遗漏，排序稳定；
- Android 离线完成后重试只产生一条完成记录；
- OpenAPI、pytest、Ruff、类型检查与迁移升级测试通过。
