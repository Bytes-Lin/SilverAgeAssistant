# FastAPI 接口草案

Base path：`/api/v1`

## 1. 认证与绑定

```text
POST /auth/family/dev-verification-token
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

开发阶段手机号验证：

- `POST /auth/family/dev-verification-token` 默认关闭，仅在显式设置 `SILVERAGE_DEV_VERIFICATION_ENABLED=true` 时启用；
- 请求必须携带 `X-Development-Verification-Key`，其值由 `SILVERAGE_DEV_VERIFICATION_KEY` 配置；
- 返回绑定手机号且短期有效的签名 `verification_token`，注册时手机号必须与令牌声明一致；
- 非开发环境必须关闭该端点并接入真实手机号验证 Provider。

创建老人档案、生成绑定码和撤销绑定码需要家属 Bearer access token。`GET /bindings` 同时接受家属 access token 或绑定成功后获得的 device credential，并按当前主体过滤结果。

绑定码默认 10 分钟有效，重新生成会立即撤销同一老人档案的旧码。设备绑定成功后，绑定码消费、Binding 创建和 device credential 签发在同一 SQLite 事务中完成。相同 `client_request_id` 重试返回相同绑定结果和相同 credential。

当前已实现的绑定错误码：

| HTTP | `error.code` |
|---:|---|
| 400 | `INVALID_MOBILE_FORMAT` |
| 400 | `SHARING_CONSENT_REQUIRED` |
| 400 | `BINDING_CREDENTIALS_INVALID` |
| 401 | `FAMILY_MOBILE_NOT_VERIFIED` |
| 409 | `BINDING_CODE_USED_OR_REVOKED` |
| 409 | `DEVICE_BINDING_CONFLICT` |
| 410 | `BINDING_CODE_EXPIRED` |
| 429 | `BINDING_ATTEMPTS_EXCEEDED` |

手机号不存在、手机号与绑定码不匹配、绑定码不存在统一返回 `BINDING_CREDENTIALS_INVALID`。

以下第 2 至第 7 节仍为后续 M04/M06 接口草案，当前代码尚未实现，不能作为可调用接口。

## 2. 设备与同步

```text
POST /devices/status
GET  /sync/events?after_sequence={n}&limit={n}
POST /sync/ack
WS   /ws
```

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

## 4. 家属消息与提醒

```text
POST /elders/{elder_id}/messages
POST /elders/{elder_id}/reminder-commands
GET  /devices/{device_id}/commands/pending
POST /commands/{command_id}/ack
```

提醒指令不直接只依赖 WebSocket。服务器持久化后发送 `EVENT_AVAILABLE`，老人端 REST 获取详情并写入 Room。

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
```

只上传统计，不上传 API Key 和完整内容。

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
