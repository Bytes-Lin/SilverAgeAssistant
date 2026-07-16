# 系统架构

## 1. 总体结构

```text
老人模式 Android ───────────────► 云端模型服务
  │                              LLM/MLLM: OpenAI-compatible
  │                              ASR/TTS: Provider-specific HTTP/WS
  │
  ├──── HTTPS REST ────┐
  └──── WebSocket ─────┤
                       ▼
                 FastAPI 中间服务
                       ▲
  ┌──── HTTPS REST ────┤
  └──── WebSocket ─────┘
  │
家属模式 Android
```

模型链路和家庭协同链路分离。FastAPI 不接触老人模型 API Key，也不位于日常语音推理路径。

## 2. Android 分层

```text
UI / Compose
  ↓ UI events + immutable state
ViewModel
  ↓ use cases
Domain
  ├── Agent orchestration
  ├── Reminder policy
  ├── Emergency policy
  └── Family communication
  ↓ repositories/providers
Data & Platform
  ├── Room
  ├── DataStore + Keystore
  ├── Retrofit/OkHttp/WebSocket
  ├── Audio recorder/player
  ├── Contacts/Phone/SMS/Location
  ├── AccessibilityService
  └── MediaStore
```

## 3. FastAPI 分层

```text
API routers / WebSocket endpoints
  ↓
Application services
  ↓
Repositories + event delivery
  ↓
SQLite / in-process connection state
```

当前轻量版本使用 SQLite 保存正式业务记录。WebSocket 连接映射和可丢失的短期在线状态保存在 FastAPI 进程内存；绑定码、凭证状态和需要可靠恢复的数据仍写入 SQLite。首版按单进程运行，不引入 Redis。

## 4. 通信可靠性

### REST

所有重要事件先写服务器：服药确认、远程提醒、SOS、审批、消息、用量记录。请求带 `Idempotency-Key` 或客户端生成的 `event_id`。

### WebSocket

用于连接存活时的低延迟通知。WebSocket 不是事实来源，消息必须先存在数据库并可通过 REST 拉取。

### 断线恢复

客户端维护 `last_server_sequence`：

1. 重连后调用 `/sync/events?after_sequence=...`；
2. 服务端返回缺失事件；
3. 客户端逐条幂等应用；
4. 客户端 ACK；
5. 更新本地游标。

### 无移动推送的限制

Android App 退到后台或被系统终止后，服务端不能保证立即唤醒它。开发阶段采用：

- 前台时 WebSocket；
- App 恢复时 REST 补拉；
- 可选 WorkManager 周期同步非紧急消息；
- 可选带常驻通知的前台服务，仅用于受控测试，不作为默认用户体验。

SOS 不依赖该链路：老人手机本地拨号/短信与服务器事件并行发起。

## 5. 部署拓扑

开发与首版轻量部署：

```text
Android Emulator/Device
├── llama-server（开发机）
└── FastAPI（开发机，单进程）
      └── SQLite 数据库文件
```

当前不维护 PostgreSQL、Redis 或数据库 Docker 基础设施。若未来需要多实例部署、较高并发写入或跨进程共享在线状态，再评估迁移到独立数据库与缓存；迁移前必须更新架构决策和测试方案。

未来可扩展拓扑（非当前实现范围）：

```text
TLS reverse proxy
  ↓
FastAPI ASGI workers
  ├── shared relational database
  └── optional shared cache
```

## 6. 核心架构边界

- 云端模型不可用不应影响本地提醒、SOS 本地路径、本地音乐。
- FastAPI 不应成为模型调用单点。
- WebSocket 不应成为消息唯一记录。
- LLM 不直接执行系统操作，所有动作经过 Tool/Policy/Executor。
