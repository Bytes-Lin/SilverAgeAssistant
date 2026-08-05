# 老人状态检测配置与异常事件中台需求

> 实现状态（2026-07-29）：中台已完成 `enabled` 启停配置、结构化安全事件、首次 ACK、GUI Agent 兜底事件类型、今日/活动紧急事件查询、幂等事件处理，以及异常事件 JPEG/PNG 原始字节上传、绑定鉴权读取、文件签名与 8 MiB 校验、去 EXIF 原尺寸副本和 512px 缩略图、私有存储、7 天限期清理与在线提示。

## 1. 目标与范围

本需求为后续独立“老人状态监控 Agent”提供跨端基础能力：

1. 家属设置检测间隔，中台可靠保存并下发到老人设备；
2. 老人设备产生疑似跌倒、疑似晕倒/失去意识等结构化异常事件；
3. 中台先持久化事件，再向有权限的家属端发送在线提示；
4. 家属端通过 REST 补拉事件，一般事件进入“今日状态”，紧急事件进入“紧急事件”并弹窗；
5. 家属确认收到紧急事件后，中台保存 ACK；
6. 家属可把已处理的“今日状态”或“紧急事件”标记完成，使其退出活动列表。

中台不代理 MLLM 请求，也不保存模型 API Key、完整模型提示词、推理过程或原始模型响应。状态监控 Agent 达到上报阈值后，可以为该紧急事件上传一张证据图像；图像使用独立二进制接口并受绑定权限保护，不进入普通事件 JSON、WebSocket 或日志。

## 2. 核心规则

- 默认 `enabled=true`、检测间隔为 `5` 分钟；Android UI 允许关闭或选择 `1/5/10/15/30/60` 分钟，服务端仍须验证启停字段和间隔范围。
- `enabled=false` 与检测间隔是独立状态，不得使用 `interval_minutes=0` 表示关闭。
- 配置更新必须实时提示当前在线的老人设备。老人端收到提示后立即 REST 补拉：修改间隔会热重排调度且保留六小时历史；关闭会停止图像/MLLM Agent 并清空暂存历史，但保留配置监听，以便之后实时重新开启。
- 配置以老人档案为单位，不以家属账号或设备为单位；同一老人更换设备后继续使用原配置。
- 所有传输时间均为 UTC ISO 8601；“今日”按中台保存的老人 IANA 时区计算，不允许家属查询参数覆盖老人时区。
- 严重程度仅允许 `GENERAL`（一般）和 `EMERGENCY`（紧急）。
- 事件类型允许 `HEALTH_DISCOMFORT_REPORTED`、`FAMILY_REQUEST`、`FALL_SUSPECTED`、`UNCONSCIOUSNESS_SUSPECTED`、`OTHER_ABNORMALITY`、`GUI_ORDER_ASSISTANCE_REQUIRED`。
- `GUI_ORDER_ASSISTANCE_REQUIRED` 表示 GUI Agent 在确定的时间限制内无法完成外卖或网购任务，需要家属接手；它复用现有设备事件创建接口，不新增另一套通知创建接口。
- 端侧和服务端确定性策略强制 `FAMILY_REQUEST -> GENERAL`，其余身体异常事件和 `GUI_ORDER_ASSISTANCE_REQUIRED -> EMERGENCY`，不盲信模型生成的 severity。
- GUI 点单协助事件的 `client_event_id` 使用 GUI 任务 UUID，同一任务只允许创建一次；`event_summary` 只包含家属完成点单所需的最小摘要、当前进度和失败原因，不得包含账号、密码、验证码、支付凭据、完整聊天原文或模型推理。
- Android 和中台文案必须使用“疑似”“需要核实”，不能把 MLLM 结果宣称为医疗诊断或确定事实。
- 为兼容状态检测 Agent 直接上报的客观描述，三种疑似异常事件的摘要若未包含“疑似”或“需要核实”，中台必须确定性补充“需要核实：”前缀并保持摘要不超过 200 字符，不得因缺少固定措辞而丢弃紧急事件。
- ACK 仅表示“家属已读”，不能代替“已完成”；resolve 表示事件已经处理。首版不做物理删除，避免多个家属看到不一致状态并保留必要审计。
- “今日状态”按老人本地自然日自动刷新，不需要定时删除数据库记录；跨日未处理紧急事件必须继续出现在活动紧急事件查询中，直至家属 resolve。
- WebSocket 仅发送“有新数据”的提示，REST/SQLite 才是事实来源。

## 3. 数据模型

### 3.1 safety_monitoring_configurations

| 字段 | 类型 | 说明 |
|---|---|---|
| elder_id | UUID，唯一 | 老人档案 |
| enabled | bool | 是否启用状态检测，默认 `true` |
| interval_minutes | int | `1..60`，默认 `5` |
| revision | bigint | 从 1 单调递增 |
| updated_by_family_account_id | UUID | 最后修改家属 |
| updated_at | UTC datetime | 更新时间 |
| request_fingerprint | string | 幂等冲突检测 |

未创建配置记录时，读取接口可返回 `404 SAFETY_CONFIG_NOT_FOUND`；Android 使用本地默认 5 分钟。也可在老人绑定成功时创建默认记录，选定一种行为后保持 OpenAPI 一致。

### 3.2 safety_events

| 字段 | 类型 | 说明 |
|---|---|---|
| event_id | UUID，主键 | 中台事件 ID |
| client_event_id | UUID，唯一 | 老人端幂等 ID |
| elder_id | UUID | 由 device credential 推导 |
| source_device_id | UUID | 由凭证推导 |
| server_sequence | bigint | 老人事件流递增序号 |
| occurred_at | UTC datetime | 端侧实际检测时间 |
| event_type | enum | 六种允许类型，包含 GUI 点单协助 |
| event_summary | string | 1..200 字符的家属可读摘要 |
| severity | enum | `GENERAL/EMERGENCY` |
| acknowledged_at | UTC datetime/null | 首位家属确认时间 |
| acknowledged_by_family_account_id | UUID/null | 确认家属 |
| resolved_at | UTC datetime/null | 首次标记已处理时间 |
| resolved_by_family_account_id | UUID/null | 首位处理家属 |
| created_at | UTC datetime | 中台入库时间 |
| image_available | bool/派生字段 | 是否已有证据图像 |
| image_content_type | string/null | `image/jpeg` 或 `image/png` |
| image_byte_size | int/null | 原图字节数 |

事件表不得保存 Base64 图片、精确位置、聊天内容、API Key、模型 prompt、思考过程或完整模型响应。图片存储与事件元数据分离，使用不可猜测存储名和事件外键；不得使用老人姓名、手机号或原始文件名作为路径。

## 4. REST 接口

### 4.1 家属读取检测配置

`GET /api/v1/elders/{elder_id}/safety-monitoring/config`

认证：family access token；必须验证当前家属与老人存在有效绑定。

```json
{
  "enabled": true,
  "interval_minutes": 5,
  "revision": 3,
  "updated_at": "2026-07-22T03:00:00Z"
}
```

### 4.2 家属更新检测配置

`PUT /api/v1/elders/{elder_id}/safety-monitoring/config`

Header：`Idempotency-Key: <client_request_id>`

```json
{
  "enabled": true,
  "interval_minutes": 10,
  "expected_revision": 3,
  "client_request_id": "UUID"
}
```

响应为包含 `enabled` 的更新后完整配置。相同幂等键和相同请求体返回首次结果；`enabled` 必须纳入请求指纹，相同键不同请求体返回 `409 IDEMPOTENCY_CONFLICT`。`expected_revision` 不匹配返回 `409 SAFETY_CONFIG_REVISION_CONFLICT`，错误详情包含当前 revision，不能静默覆盖另一位家属刚提交的修改。

写库成功后，向该老人所有有效 device credential WebSocket 连接发送 `SAFETY_MONITORING_CONFIG_AVAILABLE`。

### 4.3 老人设备读取配置

`GET /api/v1/devices/me/safety-monitoring/config`

认证：device credential。老人身份和设备身份只能从凭证推导，请求不得传入 `elder_id`。响应同 4.1。Android 在启动、收到 WebSocket 配置提示以及后续监控 Agent 恢复任务时调用；失败时继续使用上次成功保存的本地值。

### 4.4 老人设备创建异常事件

`POST /api/v1/devices/me/safety-events`

认证：device credential。Header：`Idempotency-Key: <client_event_id>`

```json
{
  "client_event_id": "UUID",
  "occurred_at": "2026-07-22T03:10:00Z",
  "event_type": "FALL_SUSPECTED",
  "event_summary": "检测到老人疑似跌倒，请尽快联系核实。",
  "severity": "EMERGENCY"
}
```

```json
{
  "event_id": "UUID",
  "server_sequence": 1024,
  "occurred_at": "2026-07-22T03:10:00Z",
  "event_type": "FALL_SUSPECTED",
  "event_summary": "检测到老人疑似跌倒，请尽快联系核实。",
  "severity": "EMERGENCY",
  "acknowledged_at": null,
  "resolved_at": null,
  "created_at": "2026-07-22T03:10:02Z"
}
```

服务端必须先提交 SQLite 事务，再尝试 WebSocket 投递。相同 `client_event_id` 幂等重试不得创建重复事件。服务端应限制未来时间容差、过旧事件、摘要长度、枚举值和设备归属。

GUI Agent 兜底时仍调用本接口，例如：

```json
{
  "client_event_id": "GUI任务UUID",
  "occurred_at": "2026-07-29T04:30:00Z",
  "event_type": "GUI_ORDER_ASSISTANCE_REQUIRED",
  "event_summary": "老人想点一份清淡午餐；已选餐厅但未进入支付，页面操作超时，请家属协助。",
  "severity": "EMERGENCY"
}
```

服务端必须把该类型校验为 `EMERGENCY`。同一 GUI 任务重试必须返回第一次创建的事件，不得重复通知家属。

### 4.5 家属读取活动事件

```text
GET /api/v1/elders/{elder_id}/safety-events?scope=today
GET /api/v1/elders/{elder_id}/safety-events?scope=active_emergencies
```

认证和绑定验证同 4.1。

- `scope=today`：按老人最近可靠 IANA 时区计算老人当地自然日，返回当天 `resolved_at IS NULL` 的一般和紧急事件，用于“今日状态”自动按天刷新；
- `scope=active_emergencies`：返回全部 `severity=EMERGENCY AND resolved_at IS NULL` 的事件，不限制发生日期，用于“紧急事件”跨日保留；
- 无位置时区时使用中台明确的系统后备值，并返回实际时区；不得由查询参数传入固定时区覆盖；
- 两种 scope 均按 `occurred_at DESC, server_sequence DESC` 排序，最新事件在上；
- 未识别的 scope 返回 `400 INVALID_SAFETY_EVENT_SCOPE`。

```json
{
  "current_date": "2026-07-22",
  "timezone": "Asia/Shanghai",
  "events": [
    {
      "event_id": "UUID",
      "server_sequence": 1024,
      "occurred_at": "2026-07-22T03:10:00Z",
      "event_type": "FALL_SUSPECTED",
      "event_summary": "检测到老人疑似跌倒，请尽快联系核实。",
      "severity": "EMERGENCY",
      "acknowledged_at": null,
      "resolved_at": null,
      "created_at": "2026-07-22T03:10:02Z"
    }
  ],
  "synced_at": "2026-07-22T03:10:05Z"
}
```

无事件返回空数组，不返回 404。服务端应设置合理分页或首版最多返回最近 100 条活动紧急事件，不能因历史数据无限增长而返回无界响应。

### 4.6 家属确认紧急事件

`POST /api/v1/elders/{elder_id}/safety-events/{event_id}/acknowledge`

Header：`Idempotency-Key: <client_request_id>`

```json
{
  "client_request_id": "UUID"
}
```

响应为确认后的完整事件。重复确认返回第一次确认结果，不更改首位确认家属和时间。一般事件也可确认，但首版 Android 只对紧急弹窗调用。

### 4.7 家属标记事件已处理

`POST /api/v1/elders/{elder_id}/safety-events/{event_id}/resolve`

认证：family access token；必须验证当前家属与事件所属老人存在有效绑定。Header：`Idempotency-Key: <client_request_id>`。

```json
{
  "client_request_id": "UUID"
}
```

成功返回 `200` 和处理后的完整事件：

```json
{
  "event_id": "UUID",
  "server_sequence": 1024,
  "occurred_at": "2026-07-29T04:30:00Z",
  "event_type": "GUI_ORDER_ASSISTANCE_REQUIRED",
  "event_summary": "老人想点一份清淡午餐；已选餐厅但未进入支付，页面操作超时，请家属协助。",
  "severity": "EMERGENCY",
  "acknowledged_at": "2026-07-29T04:31:00Z",
  "resolved_at": "2026-07-29T04:40:00Z",
  "created_at": "2026-07-29T04:30:02Z"
}
```

规则：

- resolve 适用于一般和紧急事件；家属端“已完成/删除”按钮统一调用本接口；
- 首次成功调用原子写入 `resolved_at` 和 `resolved_by_family_account_id`；
- 已处理事件再次调用时返回第一次处理结果，不改变首位处理家属和时间；
- resolve 不物理删除事件、图片或审计记录，也不自动伪造 `acknowledged_at`；
- 处理完成后，该事件不再出现在 `scope=today` 和 `scope=active_emergencies` 的默认活动结果中；
- 相同 `Idempotency-Key` 与相同请求返回首次结果；相同键用于不同请求时返回 `409 IDEMPOTENCY_CONFLICT`。
- 事务提交后复用现有 `SAFETY_EVENT_AVAILABLE` WebSocket 提示通知其他在线家属刷新 REST 数据；提示仍不得携带事件摘要。首版不要求新增另一种 WebSocket 消息。

### 4.8 老人设备上传事件图像

`PUT /api/v1/devices/me/safety-events/{event_id}/image`

认证：device credential。`Content-Type` 仅允许 `image/jpeg` 或 `image/png`，`Idempotency-Key` 使用 `{event_id}`，Body 为原始图片字节，不使用 JSON/Base64。

服务端必须验证事件属于当前设备所绑定的老人，且事件类型为监控异常类型。单图最大 8 MiB；验证实际文件签名，不能只信任 Content-Type。相同事件和相同内容幂等返回首次结果，不同内容返回 `409 IDEMPOTENCY_CONFLICT`。保存原图后生成不超过 512px、去除 EXIF 的缩略图，再把事件 `image_available` 置为 true。图像失败不能删除已入库的紧急事件。

### 4.9 家属读取事件图像

```text
GET /api/v1/elders/{elder_id}/safety-events/{event_id}/image?variant=thumbnail
GET /api/v1/elders/{elder_id}/safety-events/{event_id}/image?variant=original
```

认证：family access token。必须验证有效绑定和安全事件查看权限。响应为图片二进制，设置 `Cache-Control: private, no-store` 和 `X-Content-Type-Options: nosniff`，不得返回可公开访问的永久 URL。无图返回 `404 SAFETY_EVENT_IMAGE_NOT_FOUND`。

## 5. WebSocket

### 5.1 老人端配置提示

```json
{
  "message_type": "SAFETY_MONITORING_CONFIG_AVAILABLE",
  "revision": 4
}
```

老人端收到后立即调用 4.3，不直接信任 WebSocket 中的配置值。服务端需要向该老人所有有效设备连接广播，连续快速更新时允许合并提示，但设备最终补拉结果必须是最新 revision。

### 5.2 家属端事件提示

```json
{
  "message_type": "SAFETY_EVENT_AVAILABLE",
  "elder_id": "UUID",
  "event_id": "UUID",
  "server_sequence": 1024,
  "severity": "EMERGENCY"
}
```

仅投递给与该老人存在有效绑定且有安全事件查看权限的在线家属。Android 收到后调用 4.5；WebSocket 不携带摘要，避免把内容当成可靠事实或在日志中扩大敏感信息。

图像保存成功后再发送 `SAFETY_EVENT_IMAGE_AVAILABLE`，只携带 `elder_id` 和 `event_id`。Android 收到后重新调用 4.5，并通过 4.9 按需加载缩略图；WebSocket 不携带图片、URL 或摘要。

## 6. 错误码

- `SAFETY_CONFIG_NOT_FOUND`：尚无远程配置；
- `INVALID_SAFETY_INTERVAL`：间隔超出 `1..60`；
- `INVALID_SAFETY_ENABLED`：`enabled` 缺失或不是布尔值；
- `SAFETY_CONFIG_REVISION_CONFLICT`：乐观锁冲突；
- `INVALID_SAFETY_EVENT`：事件字段、时间或枚举无效；
- `INVALID_SAFETY_EVENT_SCOPE`：事件查询 scope 不受支持；
- `SAFETY_EVENT_NOT_FOUND`：事件不存在；
- `INVALID_SAFETY_EVENT_IMAGE`：类型、文件签名或事件类型不允许；
- `SAFETY_EVENT_IMAGE_TOO_LARGE`：超过 8 MiB；
- `SAFETY_EVENT_IMAGE_NOT_FOUND`：尚无图像或已按保留策略删除；
- `SAFETY_EVENT_IMAGE_RATE_LIMITED`：同一家属读取同一事件图像过于频繁；
- `SAFETY_EVENT_FORBIDDEN`：无有效绑定或无权限；
- `IDEMPOTENCY_CONFLICT`：幂等键请求体冲突；
- 通用 `AUTHENTICATION_REQUIRED`、`NETWORK_TIMEOUT` 保持现有协议。

## 7. 可靠性、安全和审计

- 配置更新、事件创建、ACK、resolve 均须事务写库并支持幂等。
- 事件投递失败不能回滚已入库事件；家属 App 启动、恢复前台和进入事件页面时通过 REST 补拉。
- 中台日志仅记录 event ID、枚举、状态码和脱敏主体 ID，不记录事件摘要、图像或模型正文。
- 原图与缩略图必须加密存储或放在仅服务进程可读的私有目录，禁止由静态文件服务器直接公开；默认保留 7 天后删除二进制并令 `image_available=false`。
- 下载接口设置家属与事件维度速率限制；审计只记录查看动作、家属 ID、事件 ID 和 variant。
- 紧急事件不能宣称“家属已收到”，除非存在 ACK；WebSocket 写成功只能记为“已尝试在线提示”。
- 当前项目不使用第三方移动推送。App 被系统杀死后不能保证即时弹窗，恢复时必须 REST 补拉未确认紧急事件并再次提示。
- 对单设备事件创建接口设置速率限制，但不得覆盖不同真实事件；按 `client_event_id` 去重并保留审计。

## 8. Android 已完成的对接点

- 家属端启停/检测间隔配置页及默认开启、5 分钟；
- 老人端 `agent/safety-monitor-config.json` 原子保存与备份排除；
- 家属配置 PUT、老人配置 GET、老人事件 POST、家属今日事件 GET 和 ACK Repository；
- 家属“今日状态”和“紧急事件”列表；
- `SAFETY_EVENT_AVAILABLE` 触发 REST 刷新，未确认紧急事件弹窗；
- `SAFETY_MONITORING_CONFIG_AVAILABLE` 触发老人端 REST 拉取并在运行中热更新前台调度服务；间隔更新保留历史，关闭清空历史。
- 聊天 Agent 的 `report_family_situation` Tool 和后续状态监控 Agent 共享同一 `FamilySituationReporter`；事件 UTC 时间和幂等 UUID 由端侧执行器生成，不由模型填写。
- 状态监控 Agent 每个六小时窗口最多创建一条异常事件；创建成功后上传触发上报的图像。家属紧急事件页按需加载缩略图，点击后请求原图。

中台现在会始终返回 `enabled`，Android 保留旧响应按 `true` 处理仅用于兼容升级前服务，不能作为新接口的正常行为。

## 9. 本轮中台实现清单（已完成）

必须完成：

1. 扩展现有 `POST /api/v1/devices/me/safety-events`，接受 `GUI_ORDER_ASSISTANCE_REQUIRED` 并在服务端强制映射为 `EMERGENCY`；
2. 为 `safety_events` 增加可空的 `resolved_at`、`resolved_by_family_account_id` 字段，并在所有完整事件响应中返回 `resolved_at`；
3. 扩展现有 `GET /api/v1/elders/{elder_id}/safety-events`，支持 `scope=active_emergencies`，并让两个活动 scope 排除已处理事件；
4. 新增 `POST /api/v1/elders/{elder_id}/safety-events/{event_id}/resolve`；
5. 更新 OpenAPI、数据库迁移、Repository/Service、鉴权、幂等记录和自动化测试。

本轮不需要新增：

- GUI Agent 专用的事件创建接口；
- 事件硬删除接口；
- 单独的 dismiss/隐藏接口；
- 新的 WebSocket 消息类型。

最低验收测试应覆盖：GUI 事件 severity 强制映射、同任务创建幂等、无绑定家属禁止处理、首次 resolve 原子写入、重复 resolve 不改变首位处理人和时间、今日列表排除已处理事件、跨日未处理紧急事件仍可查询、跨日已处理紧急事件不可查询、无效 scope、相同幂等键不同请求冲突。
