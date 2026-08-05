# 模型用量汇报与家属查询需求

实现状态（2026-07-19）：Android 端已完成本地全局用量账本、每小时 WorkManager 汇报、位置时区采集、家属端今日/月度用量页面、每日柱状图，以及手动刷新触发的 WebSocket 即时汇报链路；中台批量上报、全局幂等、SQLite 聚合存储、IANA 时区与来源校验、最近位置时区优先、当地自然月每日分桶与零日期补齐、有效绑定权限校验、家属时间段汇总、手动刷新持久化幂等与 3 秒限流、在线 WebSocket 投递、离线结果、隐私边界和 OpenAPI 验收测试均已完成。

## 1. 目标与边界

- 老人设备直接调用 MLLM、ASR、TTS，中台不代理这些请求。
- 老人设备按小时向中台汇报聚合用量。
- 家属只能查询与自己有效绑定的老人档案用量。
- MLLM 统计输入、输出 Token 和调用次数；ASR、TTS 只统计调用次数，不统计音频时长、
  字符数或聊天/通知/新闻来源明细。
- 统计是客户端记录或估算，不得描述为云模型厂商正式账单。
- 不上传 API Key、提示词、聊天正文、Tool 参数/结果、音频、图片或精确位置。

上下文容量不是用量事件字段。家属通过模型配置接口设置 `context_window_tokens`，中台再下发给老人设备；老人端用最近一轮 input Token 除以该配置值显示上下文进度，并为后续压缩策略提供统一阈值。字段契约见 [`remote-model-configuration-requirements.md`](remote-model-configuration-requirements.md)。

## 2. 数据模型

建议新增 `model_usage_batches` 与 `model_usage_items`。

`model_usage_batches`：

| 字段 | 说明 |
|---|---|
| `batch_id` | 客户端 UUID；全局唯一幂等键 |
| `elder_id` | 从 device credential 推导，客户端不得指定 |
| `device_id` | 从 device credential 推导 |
| `period_started_at` / `period_ended_at` | UTC RFC 3339 |
| `time_zone` | 老人设备上报的合法 IANA 时区 |
| `time_zone_source` | `LOCATION` 或 `SYSTEM_FALLBACK` |
| `received_at` | 服务端接收时间 |

`model_usage_items`：

| 字段 | 说明 |
|---|---|
| `batch_id` | 所属批次 |
| `modality` | `MLLM` / `ASR` / `TTS` |
| `provider` | 最长 80 字符，如 `openai_compatible` |
| `model` | 可空，最长 120 字符 |
| `feature` | 最长 80 字符；ASR/TTS 使用统一稳定值，不按业务来源拆分 |
| `request_count` / `success_count` | 非负整数，且成功数不大于请求数 |
| `input_tokens` / `output_tokens` | MLLM 非负整数 |
| `asr_audio_duration_ms` | 兼容字段；语音 MVP 固定为 0，不参与统计 |
| `tts_character_count` / `tts_audio_duration_ms` | 兼容字段；语音 MVP 固定为 0，不参与统计 |
| `contains_estimated_values` | 是否包含本地估算 |

同一批次内可按 `modality + provider + model + feature` 聚合保存。服务端不得从用量字段反推出或保存聊天内容。

## 3. 老人设备批量汇报

```http
POST /api/v1/model-usage/batches
Authorization: Bearer <device_credential>
Idempotency-Key: <batch_id>
Content-Type: application/json
```

```json
{
  "batch_id": "132657ad-7ff2-45ac-b427-264697623697",
  "period_started_at": "2026-07-18T12:00:00Z",
  "period_ended_at": "2026-07-18T12:58:10Z",
  "time_zone": "Asia/Shanghai",
  "time_zone_source": "LOCATION",
  "items": [
    {
      "modality": "MLLM",
      "provider": "openai_compatible",
      "model": "qwen3_5",
      "feature": "conversation",
      "request_count": 4,
      "success_count": 4,
      "input_tokens": 6120,
      "output_tokens": 980,
      "asr_audio_duration_ms": 0,
      "tts_character_count": 0,
      "tts_audio_duration_ms": 0,
      "contains_estimated_values": false
    }
  ]
}
```

成功响应：

```json
{
  "batch_id": "132657ad-7ff2-45ac-b427-264697623697",
  "accepted": true,
  "received_at": "2026-07-18T13:00:06Z"
}
```

规则：

- 仅接受有效 device credential。
- `elder_id`、`device_id` 必须由凭证推导。
- `batch_id` 与 `Idempotency-Key` 必须一致。
- 相同批次与相同请求体重试返回第一次结果；相同批次不同请求体返回 `409 IDEMPOTENCY_CONFLICT`。
- 单批 `items` 1—100，单字段计数不超过 `9_000_000_000_000_000`。
- 时间范围不得倒置，单批跨度不超过 7 天，未来时间容差不超过 10 分钟。
- `time_zone` 必须是合法 IANA 时区。老人端优先使用通过当前位置调用 Open-Meteo
  `timezone=auto` 得到的时区，并发送 `time_zone_source=LOCATION`；定位时区尚未取得时
  可发送设备系统时区和 `SYSTEM_FALLBACK`，不得硬编码城市时区。
- 中台保存老人最近一次 `LOCATION` 时区作为每日统计依据；后续
  `SYSTEM_FALLBACK` 不应覆盖已有的位置时区。
- Android 只有收到成功响应后才把本地记录标为已汇报；网络失败会保留并在后续周期重试。

## 4. 家属查询

```http
GET /api/v1/elders/{elder_id}/model-usage?from=2026-07-01T00%3A00%3A00Z&to=2026-08-01T00%3A00%3A00Z
Authorization: Bearer <family_access_token>
```

```json
{
  "elder_id": "a088a55f-f2c5-4a89-b4d7-d9b7f1759637",
  "period_started_at": "2026-07-01T00:00:00Z",
  "period_ended_at": "2026-08-01T00:00:00Z",
  "totals": {
    "input_tokens": 52840,
    "output_tokens": 10890,
    "mllm_request_count": 46,
    "asr_request_count": 0,
    "tts_request_count": 0,
    "asr_audio_duration_ms": 0,
    "tts_character_count": 0,
    "tts_audio_duration_ms": 0,
    "contains_estimated_values": true
  },
  "last_reported_at": "2026-07-18T13:00:06Z"
}
```

规则：

- access token 对该老人不存在有效绑定时返回 `403 BINDING_FORBIDDEN`。
- `from < to`，最大查询跨度 366 天。
- 汇总按客户端上报事件的时间范围筛选，不使用接收时间代替。
- 没有数据时返回全 0 totals，`last_reported_at` 可为 `null`，不返回 404。
- 响应使用 `Cache-Control: no-store`。
- 家属端不得获得单次调用明细、聊天正文或精确调用时间线。

### 4.1 每日用量分桶

家属端需要同时展示今日用量和本月每日趋势。不得让 Android 为一个月逐日发起约 30 次
汇总查询，中台应提供一次返回完整日期范围的每日分桶接口：

```http
GET /api/v1/elders/{elder_id}/model-usage/daily
Authorization: Bearer <family_access_token>
```

中台使用老人设备最近上报的位置时区计算老人当地的当前日期，并返回该当地日期所在的
完整自然月。家属端无权指定时区，避免异地家属将自己的时区错误套用到老人数据。

```json
{
  "elder_id": "uuid",
  "period_started_on": "2026-07-01",
  "period_ended_on": "2026-08-01",
  "current_date": "2026-07-19",
  "timezone": "Asia/Shanghai",
  "timezone_source": "LOCATION",
  "days": [
    {
      "date": "2026-07-01",
      "totals": {
        "input_tokens": 1520,
        "output_tokens": 380,
        "mllm_request_count": 6,
        "asr_request_count": 2,
        "tts_request_count": 2,
        "asr_audio_duration_ms": 18400,
        "tts_character_count": 210,
        "tts_audio_duration_ms": 26000,
        "contains_estimated_values": true
      }
    }
  ],
  "last_reported_at": "2026-07-19T03:30:00Z"
}
```

规则：

- 权限、隐私范围和 `Cache-Control: no-store` 与月汇总接口一致。
- 按老人最近一次 `LOCATION` 时区将用量批次 `period_started_at` 转换为当地日期分桶。
- 尚无位置时区时可使用设备上报的 `SYSTEM_FALLBACK`，响应必须原样返回
  `timezone_source`，Android 向家属显示降级提示。
- `days` 按日期升序，建议返回范围内全部日期；没有用量的日期 totals 全为 0。
- Token 仅累计 MLLM；ASR/TTS 分别只累计调用次数，兼容扩展计量字段不得进入产品统计。
- 不返回单次调用记录、具体聊天时间、提示词、回复或音频。
- Android 使用响应的 `current_date` 桶展示“今日用量”，不能使用家属手机日期；
  使用全部桶绘制本月 Token 及 ASR/TTS 柱状图。

## 5. 家属手动请求当前用量

普通情况下老人端仍按一小时周期上传。家属打开页面时只读取中台已有汇总；只有家属明确点击“立即刷新用量”时才执行以下链路：

```text
家属 POST 刷新请求
  → 中台验证有效绑定
  → 向在线老人设备发送 WebSocket 事件
  → 老人设备启动一次性 WorkManager，立即上传尚未汇报记录
  → 家属端在最多约 6 秒内重新查询汇总
```

```http
POST /api/v1/elders/{elder_id}/model-usage/refresh
Authorization: Bearer <family_access_token>
Idempotency-Key: <client_request_id>
Content-Type: application/json
```

```json
{
  "client_request_id": "aa2e38bb-247f-4b61-a5a3-0f67d1536722"
}
```

```json
{
  "client_request_id": "aa2e38bb-247f-4b61-a5a3-0f67d1536722",
  "requested_at": "2026-07-19T02:45:00Z",
  "device_online": true
}
```

中台向该老人当前在线设备发送：

```json
{
  "protocol_version": 1,
  "message_type": "MODEL_USAGE_REPORT_REQUESTED",
  "message_id": "uuid",
  "sent_at": "2026-07-19T02:45:00Z",
  "payload": {
    "client_request_id": "aa2e38bb-247f-4b61-a5a3-0f67d1536722"
  }
}
```

规则：

- 只接受对 `elder_id` 存在有效绑定的家属 access token。
- `client_request_id` 必须与 `Idempotency-Key` 一致。
- 这是在线提示，不创建新的用量，也不替代小时批量上报。
- `device_online=true` 只表示事件成功交给至少一个当前 WebSocket 连接，不表示上传已经完成。
- 老人设备离线时返回 200 且 `device_online=false`；家属端立即展示中台上次汇报数据。
- 重复投递是安全的：老人端即时任务串行执行，本地记录仍通过固定 `batch_id` 幂等上传。
- 建议同一家属对同一老人限制为每 3 秒一次，超限返回 `429 USAGE_REFRESH_RATE_LIMITED`。
- 不使用第三方推送；老人应用进程不在线时无法保证即时刷新。
- 中台现有 `/api/v1/ws` 认证方式保持不变，禁止把 device credential 放在 URL。

## 6. 错误码

| HTTP | code | 场景 |
|---|---|---|
| 400 | `INVALID_USAGE_BATCH` | 字段、范围或统计关系无效 |
| 401 | `AUTHENTICATION_REQUIRED` | device credential/access token 无效 |
| 403 | `BINDING_FORBIDDEN` | 家属无权查询该老人 |
| 409 | `IDEMPOTENCY_CONFLICT` | 幂等键对应不同请求体 |
| 413 | `USAGE_BATCH_TOO_LARGE` | 批次超限 |
| 429 | `USAGE_REFRESH_RATE_LIMITED` | 家属手动刷新过于频繁 |

## 7. 验收标准

1. 同一老人设备重复上传相同批次不会重复累计。
2. 不同小时的批次可连续累计，失败后补传不会丢失。
3. 家属只能查看有效绑定老人汇总。
4. MLLM 输入/输出 Token 与调用次数正确求和；ASR/TTS 次数正确求和。
5. 任一 item 含估算值时查询结果 `contains_estimated_values=true`。
6. 数据库、请求日志和审计日志均不出现 API Key、提示词、回复或音频。
7. OpenAPI 覆盖请求/响应 schema、权限、幂等和全部错误码。
8. 家属修改 `context_window_tokens` 后，老人设备下一次成功补拉配置并发起聊天时，圆形进度使用新值作为分母；用量批次本身不重复上传该配置值。
9. 家属只进入页面时不会触发老人即时上传；点击刷新且老人在线时，新产生但尚未到小时周期的用量能在约 6 秒内显示。
10. 老人离线时手动刷新返回上次汇总并明确提示离线，不显示虚假的“已获取最新用量”。
