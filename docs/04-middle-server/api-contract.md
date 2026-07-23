# FastAPI 接口草案

Base path：`/api/v1`

> 老人安全状态检测配置、异常事件、家属 ACK 与 WebSocket 提示详见 [`safety-monitoring-and-events-requirements.md`](safety-monitoring-and-events-requirements.md)。

## 1. 认证与绑定

```text
POST /auth/family/register
POST /auth/refresh
POST /elders
POST /bindings/codes
DELETE /bindings/codes/{elder_id}
POST /devices/bind
GET  /bindings
DELETE /bindings/{binding_id}
```

家属注册后创建老人档案并生成一次性绑定码；老人设备必须使用“绑定码 + 生成该码的家属手机号”联合校验。详细字段、安全规则、幂等、错误码和验收标准见 [`family-registration-and-binding-requirements.md`](family-registration-and-binding-requirements.md)。

首版手机号按中国大陆规则规范化：接受 `1[3-9]` 开头的 11 位号码，以及带 `+86`、`0086` 前缀或常见空格、连字符、括号的等价值。数据库只比较规范化值，API 业务响应只返回掩码。

当前轻量联调版本不接入短信 OTP，也不提供开发验证令牌接口。`POST /auth/family/register` 直接接收手机号并将其作为账号标识；该手机号属于用户自报信息，服务端不会将其标记为“已验证”。正式对外部署前应单独接入真实手机号验证 Provider，并重新评估冒用手机号注册的风险。

创建老人档案、生成绑定码和撤销绑定码需要家属 Bearer access token。`GET /bindings` 同时接受家属 access token 或绑定成功后获得的 device credential，并按当前主体过滤结果。

绑定码默认 10 分钟有效，重新生成会立即撤销同一老人档案的旧码，但不会撤销现有 device credential。设备绑定成功后，绑定码消费、Binding 创建或复用、旧设备凭据撤销和新 device credential 签发在同一 SQLite 事务中完成。相同 `client_request_id` 重试返回相同绑定结果和相同 credential；`bound_at` 表示本次设备凭据生效时间。

当前 `POST /bindings/codes` 和 `POST /devices/bind` 已按 [`device-rebinding-requirements.md`](device-rebinding-requirements.md) 支持应用数据丢失或换机恢复：生成码时保留旧凭据；新设备联合校验成功后复用同一家属、同一老人档案的有效 Binding，并原子轮换该老人档案的设备凭据。只有请求 `device_id` 仍有效绑定其他老人档案时返回 `DEVICE_BINDING_CONFLICT`。

当前已实现的绑定错误码：

| HTTP | `error.code` |
|---:|---|
| 400 | `INVALID_MOBILE_FORMAT` |
| 400 | `SHARING_CONSENT_REQUIRED` |
| 400 | `BINDING_CREDENTIALS_INVALID` |
| 409 | `BINDING_CODE_USED_OR_REVOKED` |
| 409 | `DEVICE_BINDING_CONFLICT` |
| 410 | `BINDING_CODE_EXPIRED` |
| 429 | `BINDING_ATTEMPTS_EXCEEDED` |

手机号不存在、手机号与绑定码不匹配、绑定码不存在统一返回 `BINDING_CREDENTIALS_INVALID`。

第 2 节的家属资料快照、第 3.1 节安全监测接口、第 4 节家属通知与提醒接口和第 7 节用量接口已经实现。第 2、3、5、6 节中的其余接口仍为后续 M04/M06 草案，当前不能作为可调用接口。`/api/v1/ws` 已支持老人设备和家属 access token；当前只承载已实现功能的轻量提示，不代表其余通用事件同步已经完成。

家属远程模型配置接口已按第 4.1 节契约实现。家属写入和双方读取均校验有效绑定，更新使用 revision 乐观锁和独立幂等记录；所有成功与错误响应均设置 `Cache-Control: no-store`。

## 2. 设备与同步

```text
GET  /devices/me/family-contacts
POST /devices/status
GET  /sync/events?after_sequence={n}&limit={n}
POST /sync/ack
WS   /ws
```

`GET /devices/me/family-contacts` 仅接受 device credential，根据凭证所属老人档案返回当前有效绑定家属的完整联系人快照。完整手机号只允许在该受保护接口返回；现有面向家属的 `GET /bindings` 继续返回脱敏手机号。响应字段、权限、隐私、版本和验收标准见 [`elder-family-profile-sync-requirements.md`](elder-family-profile-sync-requirements.md)。

该接口已实现，成功和错误响应均设置 `Cache-Control: no-store`。快照版本由所有可见资料、权限和绑定字段生成稳定摘要；空联系人快照返回 `empty-v1`。

## 3. 状态事件

```text
POST /events
GET  /elders/{elder_id}/events
GET  /elders/{elder_id}/daily-summary
```

创建事件：

```json
{
  "event_id": "client-generated-uuid",
  "event_type": "MEDICATION_REMINDER_CONFIRMED",
  "elder_id": "uuid",
  "occurred_at": "2026-07-15T08:06:00Z",
  "payload": {
    "reminder_id": "local-uuid",
    "scheduled_at": "2026-07-15T08:00:00Z"
  }
}
```

### 3.1 状态检测配置与安全事件（已实现）

```text
GET  /elders/{elder_id}/safety-monitoring/config
PUT  /elders/{elder_id}/safety-monitoring/config
GET  /devices/me/safety-monitoring/config
POST /devices/me/safety-events
PUT  /devices/me/safety-events/{event_id}/image
GET  /elders/{elder_id}/safety-events?scope=today
GET  /elders/{elder_id}/safety-events/{event_id}/image?variant=thumbnail|original
POST /elders/{elder_id}/safety-events/{event_id}/acknowledge
WS   /ws
```

启停状态和检测间隔按老人档案保存；`enabled` 为布尔值，间隔允许 `1..60` 分钟，不能用
间隔 0 表示关闭。未设置时返回 `404 SAFETY_CONFIG_NOT_FOUND`，Android 继续使用本地默认
开启、5 分钟。配置 PUT 使用
revision 乐观锁和 `Idempotency-Key`，冲突响应的 `error.details.current_revision`
返回当前版本。事务提交后仅向有效老人设备发送
`SAFETY_MONITORING_CONFIG_AVAILABLE`，设备随后立即通过 GET 补拉包含 `enabled` 的完整配置。
运行中修改间隔不清空六小时检测历史；关闭清空历史并停止图像/MLLM 分析，但老人设备保留
配置监听以便实时重新开启。

老人设备使用 device credential 提交五种结构化事件：老人主动报告身体不适、向家属提出
请求，以及三种“疑似/需要核实”的异常事件。中台从凭证推导老人和设备身份，并强制
`FAMILY_REQUEST=GENERAL`、其余四类 `=EMERGENCY`，不信任客户端或模型给出的 severity；
事件先写入 SQLite，再向具备查看权限的在线家属发送不含摘要的
`SAFETY_EVENT_AVAILABLE`。家属 GET 按中台保存的老人 IANA 时区计算当地今日，事件按
`occurred_at DESC, server_sequence DESC` 返回；ACK 只保存首位确认家属和首次确认时间。
每个六小时检测窗口最多创建一条异常事件。事件 JSON 不接收图片；老人设备可在事件创建后通过
独立二进制 PUT 上传一张触发图像，家属经绑定鉴权读取缩略图或原图。上传接口只接受
JPEG/PNG 原始字节，限制 8 MiB，并验证实际文件签名和事件归属；服务端以不可猜测名称写入
私有目录，生成不超过 512px、去除 EXIF 的缩略图，默认保留 7 天。图片完成后向有权限的
在线家属发送仅包含老人和事件 ID 的 `SAFETY_EVENT_IMAGE_AVAILABLE`。中台仍不得接收位置、
API Key、prompt、推理过程或模型原始响应，详细契约见
[`safety-monitoring-and-events-requirements.md`](safety-monitoring-and-events-requirements.md)。

## 4. 家属消息与提醒

```text
POST /elders/{elder_id}/commands/notifications
POST /elders/{elder_id}/commands/reminders
GET  /commands/pending?after_sequence={n}&limit={n}
POST /commands/{command_id}/ack
WS   /ws
```

通知和提醒不依赖 WebSocket 作为事实来源。服务器持久化后可发送 `COMMAND_AVAILABLE`，老人端通过 REST 获取详情，以 `command_id` 幂等写入 Room，提交成功后再 ACK。完整字段、权限、幂等、错误码和中台验收标准见 [`family-notification-and-reminder-requirements.md`](family-notification-and-reminder-requirements.md)。

`WS /ws` 使用老人设备的 `Authorization: Bearer <device_credential>` 或家属的短期 access token 认证。客户端只发送连接保活和可选 `PING`；业务正文不经 WebSocket 发送，消息只提示客户端通过 REST 补拉。

## 4.1 家属远程模型配置

```text
GET /elders/{elder_id}/model-config
PUT /elders/{elder_id}/model-config
GET /devices/me/model-config
```

只同步模型服务地址、模型名、协议方言、上下文长度、最大生成 Token 和三个采样参数。API Key、Authorization 和其他模型凭证禁止经过中台。老人端以 REST 拉取为事实来源并保存到应用私有 `model-config.json`，详细字段、权限、幂等、revision 和验收标准见 [`remote-model-configuration-requirements.md`](remote-model-configuration-requirements.md)。

`PUT` 请求中的 `client_request_id` 必须与 `Idempotency-Key` 一致。首次创建使用
`expected_revision: null` 并产生 revision 1；后续更新必须提交当前 revision。相同幂等键
和相同规范化请求返回第一次成功响应，即使当前配置之后又有更新，也不会重复递增 revision。

## 5. SOS

```text
POST /emergencies
GET  /emergencies/{id}
POST /emergencies/{id}/acknowledge
POST /emergencies/{id}/resolve
```

创建 SOS：

```json
{
  "event_id": "uuid",
  "elder_id": "uuid",
  "trigger_type": "VOICE|BUTTON|SENSOR|IMAGE_REVIEW",
  "occurred_at": "...",
  "location": {
    "latitude": 0.0,
    "longitude": 0.0,
    "accuracy_meters": 20
  },
  "local_actions": {
    "dial_started": true,
    "sms_attempted": true
  }
}
```

## 6. 交易审批

```text
POST /approvals
GET  /approvals/{id}
POST /approvals/{id}/decision
```

订单金额使用最小货币单位：

```json
{
  "currency": "CNY",
  "amount_minor": 3800
}
```

## 7. 用量

```text
POST /model-usage/batches
GET  /elders/{elder_id}/model-usage?from=&to=
GET  /elders/{elder_id}/model-usage/daily
POST /elders/{elder_id}/model-usage/refresh
```

老人设备使用 device credential 每小时上传聚合统计；家属使用 access token 查询有效绑定老人汇总。家属明确点击刷新时，新增 POST 通过现有 WebSocket 请求在线老人设备立即上传，离线时仍返回上次汇总。MLLM 统计输入/输出 Token 和调用次数，ASR/TTS 首版统计调用次数。只上传统计，不上传 API Key、提示词、回复、Tool 内容或音频。完整字段、幂等、权限和验收标准见 [`model-usage-reporting-requirements.md`](model-usage-reporting-requirements.md)。

批量上报、汇总查询、每日分桶和手动刷新四条接口均已实现。批量上报校验并保存 IANA
`time_zone` 与 `time_zone_source`；每日分桶使用老人最近的 `LOCATION` 时区返回当地当前
自然月的全部日期，尚无位置时区时明确返回 `SYSTEM_FALLBACK`。手动刷新请求持久化幂等结果，
同一家属对同一老人 3 秒内的新请求返回 `429 USAGE_REFRESH_RATE_LIMITED`；在线投递成功
返回 `device_online=true`，离线时仍以 200 返回 `device_online=false`，不虚构上传完成状态。
批量上报 `POST` 要求 `Idempotency-Key` 与 `batch_id` 一致，单批 1–100 项，
同批同内容重试返回首次 `received_at`，同批不同内容返回 `409 IDEMPOTENCY_CONFLICT`。
`GET` 只接受存在有效绑定的家属，并按客户端上报周期起始时间汇总 `from`（含）至
`to`（不含）的数据；无数据时返回全 0 totals 和 `last_reported_at: null`。所有用量接口
响应均为 `Cache-Control: no-store`。

## 8. 错误响应

```json
{
  "error": {
    "code": "BINDING_FORBIDDEN",
    "message": "当前账号无权访问该老人档案",
    "request_id": "uuid"
  }
}
```

老人端应将技术错误映射为简单中文，不直接播报服务器堆栈或状态码。
