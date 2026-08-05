# FastAPI 通信设计

## 1. 技术栈

- Python 3.12+
- FastAPI + Uvicorn
- Pydantic v2
- SQLAlchemy 2 async + Alembic
- SQLite + aiosqlite
- HTTPS REST + WSS WebSocket
- pytest + Ruff + 类型检查

当前首版按单进程、轻量本地开发设计，不引入 PostgreSQL、Redis 或数据库 Docker 依赖。M04 已实现家属注册、老人档案、一次性绑定码、设备凭证、已有绑定下的原子凭据轮换、绑定查询、家属联系人完整快照、含上下文窗口的非敏感模型配置同步、带老人位置时区的模型用量批量上报、家属汇总与每日分桶查询、在线即时刷新、家属通知/一次性提醒，以及包含 `enabled` 启停与间隔的状态检测配置下发、结构化安全事件和私有证据图像。

## 2. 服务器职责

- 家属账号认证；
- 老人档案和设备绑定；
- 老人与家属授权关系；
- 状态事件持久化；
- 远程提醒/通知指令；
- SOS 和审批流程；
- 在线连接映射；
- 模型用量上报；
- 断线补偿与 ACK。

不负责 LLM/ASR/TTS 代理，不保存老人 API Key。

模型用量由老人设备本地全局账本记录，并通过 WorkManager 每小时向 `POST /api/v1/model-usage/batches` 汇报聚合数字；家属通过受绑定权限保护的汇总接口查询。中台不得接收提示词、回复、Tool 内容、音频或 API Key。详细契约见 [`model-usage-reporting-requirements.md`](model-usage-reporting-requirements.md)。

用量批次与聚合明细可靠写入 SQLite；服务端从 device credential 推导老人和设备身份，以
`batch_id` 做全局幂等，并按客户端事件时间筛选家属查询区间。相同批次重试不会重复累计，
家属接口只返回 MLLM/ASR/TTS 汇总和最后上报时间，不提供单次调用时间线。
家属端的今日统计与月度柱状图使用每日分桶接口。老人设备随批次上报由当前位置
天气响应推导的 IANA 时区；中台按老人最近的位置时区返回当地当前自然月并补齐零用量
日期。后续设备系统兜底时区不会覆盖已保存的位置时区；家属端不得传入自己的时区，
避免异地家属改变老人每日边界。
家属点击“立即刷新用量”时，Android 调用刷新 REST 接口；中台向在线老人设备
发送 `MODEL_USAGE_REPORT_REQUESTED`，老人端立即排入一次性 WorkManager 上传并与
小时任务共用幂等批次。中台持久化刷新幂等结果并执行同一家属、同一老人 3 秒限流；
老人设备离线时返回 `device_online=false` 并只展示上次汇报值。详细契约见用量需求文档第 5 节。

## 3. 协议组合

### REST

用于登录、绑定、事件写入、状态查询、指令创建、ACK 和补拉。

### WebSocket

用于双方在线时的低延迟“有新事件”通知。当前 `/api/v1/ws` 已实现老人设备
`COMMAND_AVAILABLE`，消息中只携带 `command_id`、类型和序列号，客户端随后通过
REST 获取详情。用量即时刷新复用该连接发送 `MODEL_USAGE_REPORT_REQUESTED`，只向
仍持有有效 device credential 的在线老人设备投递。状态检测配置更新同样只向有效老人
设备发送 `SAFETY_MONITORING_CONFIG_AVAILABLE`。模型配置事务提交后发送
`MODEL_CONFIG_AVAILABLE`，`payload` 仅包含新 revision；投递失败不回滚配置，老人端通过
REST 补拉并热更新。家属可使用短期 access token 建立同一路径
连接；安全事件提交并落库后，仅向存在有效绑定和查看权限的家属发送
`SAFETY_EVENT_AVAILABLE`，提示中不包含事件摘要。
事件证据图像通过独立二进制 REST 接口上传和读取，不进入 WebSocket。图像保存并生成缩略图后，
中台向有权限的在线家属发送 `SAFETY_EVENT_IMAGE_AVAILABLE`，只携带 `elder_id` 和 `event_id`；
家属端再次 REST 补拉事件元数据，再按需读取缩略图或原图。

聊天 Agent 的 `report_family_situation` 与后续状态监控 Agent 复用同一设备事件接口。中台
允许 `HEALTH_DISCOMFORT_REPORTED` 和 `FAMILY_REQUEST` 使用忠实描述老人原话的摘要；
传感/模型异常类型仍必须使用“疑似”或“需要核实”。服务端根据事件类型重新确定严重级别，
避免模型把普通家属请求升级为紧急事件，或把身体不适和异常检测降级为一般事件。

### 重连补偿

客户端每次连接携带最后确认的服务器序列号。服务器返回此后仍有权限访问的事件。

## 4. WebSocket 消息信封

```json
{
  "protocol_version": 1,
  "message_type": "EVENT_AVAILABLE",
  "message_id": "msg_uuid",
  "server_sequence": 1024,
  "sent_at": "2026-07-15T08:00:00Z",
  "payload": {
    "event_id": "evt_uuid",
    "event_type": "MEDICATION_REMINDER_RESULT"
  }
}
```

客户端 ACK：

```json
{
  "protocol_version": 1,
  "message_type": "ACK",
  "message_id": "ack_uuid",
  "payload": {
    "acked_message_id": "msg_uuid",
    "server_sequence": 1024
  }
}
```

## 5. 事件模型

```text
Event
- id UUID
- server_sequence BIGINT
- type
- actor_type / actor_id
- elder_id
- target_account_id nullable
- payload JSON
- occurred_at UTC
- created_at UTC
- idempotency_key
- sensitivity
- expires_at nullable
```

`payload` 在 SQLAlchemy 模型中使用通用 JSON 类型，以兼容当前 SQLite；不要依赖 PostgreSQL 专有的 JSONB 类型。

事件类型至少包括：

- `MEDICATION_REMINDER_CONFIRMED`
- `REMINDER_NOT_CONFIRMED`
- `ELDER_CHECK_IN`
- `FAMILY_MESSAGE_CREATED`
- `REMOTE_REMINDER_CREATED`
- `REMOTE_REMINDER_ACCEPTED`
- `FAMILY_NOTIFICATION_STORED`
- `REMOTE_REMINDER_STORED`
- `EMERGENCY_TRIGGERED`
- `EMERGENCY_ACKNOWLEDGED`
- `ORDER_APPROVAL_REQUESTED`
- `ORDER_APPROVAL_DECIDED`
- `MODEL_USAGE_REPORTED`
- `DEVICE_STATUS_REPORTED`

## 6. 幂等与重试

- Android 为写事件生成 UUID；
- HTTP 请求携带 `Idempotency-Key`；
- 服务器对 `(actor_id, idempotency_key)` 建唯一索引；
- 重试返回同一业务结果；
- 客户端 outbox 表保存待上传事件，成功后标记完成；
- 服务端先提交数据库，再尝试 WebSocket。

家属通知和提醒采用独立 command 资源：家属 REST 创建后先写 SQLite，老人端补拉完整命令并写入本地 Room，随后发送 `STORED` ACK。具体接口见 [`family-notification-and-reminder-requirements.md`](family-notification-and-reminder-requirements.md)。

老人设备的家属联系人使用独立完整快照：`GET /api/v1/devices/me/family-contacts` 根据 device credential 确定老人档案并投影当前有效绑定资料。接口已实现稳定快照摘要、完整手机号白名单、活跃绑定过滤、脱敏审计和 `no-store` 缓存策略。完整契约见 [`elder-family-profile-sync-requirements.md`](elder-family-profile-sync-requirements.md)。

非敏感模型配置采用每位老人一份当前配置和单调递增 revision。家属使用
`PUT /api/v1/elders/{elder_id}/model-config` 可靠写入，老人设备使用
`GET /api/v1/devices/me/model-config` 补拉；历史幂等响应单独保存，API Key 和
Authorization 等模型凭证不进入请求 schema、数据库、审计或响应。完整契约见
[`remote-model-configuration-requirements.md`](remote-model-configuration-requirements.md)。
配置中的 `context_window_tokens` 已纳入 revision、幂等摘要和双方读取响应；既有 SQLite
配置在迁移时回填为 32768。

## 7. 认证

家属：轻量联调版使用自报手机号直接注册 → access token + refresh token。当前不提供开发验证令牌接口，也不代表手机号真实性已经验证；正式对外部署前再接入真实验证 Provider。

老人设备：绑定码换取 device credential；凭证与设备、老人档案绑定，可吊销。重新生成绑定码不会立即影响旧凭据；新设备成功完成联合校验后，中台才在同一事务中撤销旧凭据并签发新凭据。

当前老人设备 WebSocket 使用 `Authorization: Bearer <device_credential>` 请求头认证；家属 WebSocket 使用短期 `family_access` token。两者均禁止在 URL 查询参数暴露 Refresh Token 或 device credential。

## 8. 权限

所有查询按 `elder_id` 验证绑定关系。一个家属可管理多个老人，一个老人可绑定多个家属并有权限级别：

- Viewer：查看状态；
- Helper：发送提醒；
- Approver：审批交易；
- EmergencyContact：接收/处理 SOS；
- Owner：管理绑定和权限。

## 9. 无推送限制

服务器只可向活跃 WebSocket 发送。连接不存在时：

- 事件留在数据库；
- 对端恢复后补拉；
- 可由 WorkManager 最快按系统允许的周期轮询普通事件；
- 不得用频繁轮询伪装实时通信；
- 关键 SOS 由老人本地通信兜底。

## 10. 建议目录

```text
MiddleServer/app/
├── main.py
├── api/v1/
├── core/
├── models/
├── schemas/
├── repositories/
├── services/
├── websocket/
└── workers/
```

## 11. SQLite 与单进程边界

- 正式事件、绑定、凭证状态、ACK 和幂等结果写入 SQLite；
- WebSocket 活跃连接和可重建的在线状态保存在进程内存；
- 数据库开启外键约束，并通过 Alembic 管理 schema；
- 测试使用独立临时数据库，不复用开发数据文件；
- 首版不运行多个 ASGI worker，不承诺跨进程实时投递；
- 出现多实例或明显并发瓶颈时，再评估迁移独立数据库和共享缓存。

## 12. 本地运行与配置

在 `MiddleServer/` 中使用已初始化的虚拟环境：

```powershell
.\.venv\Scripts\python.exe -m pip install -e ".[dev]"
.\.venv\Scripts\python.exe -m alembic upgrade head
.\.venv\Scripts\python.exe -m uvicorn app.main:app --reload
```

默认开发数据库为 `MiddleServer/silverage.db`。主要配置使用 `SILVERAGE_` 前缀环境变量：

- `SILVERAGE_DATABASE_URL`：异步 SQLite URL；
- `SILVERAGE_AUTO_CREATE_SCHEMA`：是否在启动时自动建表，默认关闭；正常开发使用 Alembic；
- `SILVERAGE_JWT_SECRET`：家属 JWT 签名密钥；
- `SILVERAGE_SECURITY_SECRET`：绑定码、设备凭证和限流摘要密钥；
- `SILVERAGE_BINDING_CODE_TTL_SECONDS`：绑定码有效期，默认 600 秒。
- `SILVERAGE_DEVICE_CREDENTIAL_TTL_SECONDS`：新签发设备凭证有效期，默认一年；迁移前旧凭证保持兼容；
- `SILVERAGE_COMMAND_DEFAULT_TIMEZONE`：即时通知的默认 IANA 时区，默认 `Asia/Shanghai`；
- `SILVERAGE_COMMAND_PER_MINUTE_LIMIT`：单家属对单老人每分钟命令上限，默认 10；
- `SILVERAGE_COMMAND_PER_DAY_LIMIT`：单家属对单老人 24 小时命令上限，默认 200。
- `SILVERAGE_SAFETY_EVENT_FUTURE_TOLERANCE_SECONDS`：安全事件未来时间容差，默认 600 秒；
- `SILVERAGE_SAFETY_EVENT_MAX_AGE_DAYS`：可补传安全事件的最大年龄，默认 7 天；
- `SILVERAGE_SAFETY_EVENT_PER_MINUTE_LIMIT`：单设备每分钟安全事件上限，默认 30；
- `SILVERAGE_SAFETY_IMAGE_STORAGE_PATH`：安全事件图像私有存储目录，默认 `./private/safety-event-images`；
- `SILVERAGE_SAFETY_IMAGE_MAX_BYTES`：单张原始上传最大字节数，默认 8 MiB；
- `SILVERAGE_SAFETY_IMAGE_THUMBNAIL_MAX_PIXELS`：缩略图最长边，默认 512px；
- `SILVERAGE_SAFETY_IMAGE_RETENTION_DAYS`：原图和缩略图保留天数，默认 7 天；
- `SILVERAGE_SAFETY_IMAGE_CLEANUP_INTERVAL_SECONDS`：过期图像清理周期，默认 3600 秒；
- `SILVERAGE_SAFETY_IMAGE_DOWNLOAD_PER_MINUTE_LIMIT`：单家属、单事件每分钟图片读取上限，默认 60。

仓库内的默认密钥只允许本机开发。非 development 环境若仍使用默认密钥，服务将拒绝启动。真实部署必须由 TLS 反向代理提供 HTTPS。
