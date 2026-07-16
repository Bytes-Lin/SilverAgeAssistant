# FastAPI 通信设计

## 1. 技术栈

- Python 3.12+
- FastAPI + Uvicorn
- Pydantic v2
- SQLAlchemy 2 async + Alembic
- SQLite + aiosqlite
- HTTPS REST + WSS WebSocket
- pytest + Ruff + 类型检查

当前首版按单进程、轻量本地开发设计，不引入 PostgreSQL、Redis 或数据库 Docker 依赖。M04 已实现家属注册、老人档案、一次性绑定码、设备凭证和绑定查询子任务。

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

## 3. 协议组合

### REST

用于登录、绑定、事件写入、状态查询、指令创建、ACK 和补拉。

### WebSocket

用于双方在线时的低延迟“有新事件”通知。消息中只携带必要概要和 `event_id`，客户端随后按需 REST 获取详情。

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

## 7. 认证

家属：手机号/开发账号 → access token + refresh token。

老人设备：绑定码换取 device credential；凭证与设备、老人档案绑定，可吊销。

WebSocket 连接使用短期 access token 或握手后的认证消息，禁止在 URL 查询参数长期暴露 Refresh Token。

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
- `SILVERAGE_DEV_VERIFICATION_ENABLED`：是否开放开发验证令牌端点，默认关闭；
- `SILVERAGE_DEV_VERIFICATION_KEY`：开发验证端点请求头密钥；
- `SILVERAGE_BINDING_CODE_TTL_SECONDS`：绑定码有效期，默认 600 秒。

仓库内的默认密钥只允许本机开发。非 development 环境若仍使用默认密钥或启用开发验证端点，服务将拒绝启动。真实部署必须由 TLS 反向代理提供 HTTPS。
