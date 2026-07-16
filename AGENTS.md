# SilverAgeAssistant Codex Instructions

## 1. 项目目标

开发“银龄助手”：最低支持 Android 10（API 29）的 Android 应用。同一个 APK 包含老人模式和家属模式；老人端直接连接用户自行配置的云端 AI API；两端通过 FastAPI 中间服务器交换状态、指令、审批和紧急事件。

## 2. 仓库目录边界

- `AndroidAgent/`：Kotlin、Jetpack Compose、Room、DataStore、Android 系统能力和端侧 Agent。
- `MiddleServer/`：FastAPI、SQLite、REST、WebSocket、身份绑定和事件同步；M04 已开始实现家属注册与设备绑定子任务。
- `docs/`：产品、架构、接口、安全、开发和测试文档。
- `.codex/`：Codex 项目配置和子代理。
- `scripts/`：仓库检查、本地模型等辅助脚本。

不要在根目录直接堆放 Android 或 Python 业务代码。`AndroidAgent/` 与 `MiddleServer/` 中不维护独立 README 或 AGENTS；所有说明统一更新根目录 `README.md`、本文件和 `docs/`。

## 3. 开发前必读

根据任务阅读对应文档：

- 产品总览：`docs/00-product/product-overview.md`
- 需求范围：`docs/00-product/requirements-and-scope.md`
- 系统架构：`docs/01-architecture/system-architecture.md`
- 架构决策：`docs/01-architecture/architecture-decisions.md`
- Android UI 与功能：`docs/02-android/android-ui-and-features.md`
- 家属模式：`docs/02-android/family-mode.md`
- Agent 系统：`docs/03-agent-system/elder-agent-design.md`
- 模型接入：`docs/03-agent-system/model-integration.md`
- FastAPI 通信：`docs/04-middle-server/fastapi-communication.md`
- API 草案：`docs/04-middle-server/api-contract.md`
- 安全隐私：`docs/05-security/data-security-and-privacy.md`
- 测试计划：`docs/07-testing/test-plan.md`
- 当前开发阶段：`docs/06-development/milestones/`

不要一次性实现全部功能，只完成当前任务或当前里程碑，并保持可运行、可测试。

## 4. 不可违反的架构决策

1. 一个 Android APK，通过角色切换呈现老人模式和家属模式。
2. Android 使用 Kotlin、Jetpack Compose、Coroutines、Room 和 DataStore。
3. Android `minSdk = 29`；`compileSdk` 与 `targetSdk` 使用开发环境可用的稳定版本。
4. FastAPI 只负责账号、绑定、事件、消息、提醒指令、审批、SOS、用量上报和 WebSocket，不代理老人端日常 LLM/ASR/TTS 流量。
5. 每位老人使用自己的模型 API Key；Key 只能在老人设备本地加密保存，不得上传中间服务器、日志、崩溃报告或 Git。
6. LLM/MLLM 优先使用 OpenAI 兼容协议；ASR/TTS 必须通过独立 Provider 封装。
7. 老人提醒计划以本地 Room 为事实来源，离线时仍可触发。
8. 不使用第三方移动推送。WebSocket 只保证连接存活时实时通信，断线后由 REST 补偿。
9. SOS 的本地电话或短信路径不得依赖 FastAPI 请求成功。
10. 无障碍 Agent 不得读取、保存或填写支付密码、短信验证码和生物识别信息。
11. 跌倒检测 MVP 只实现图像选择/上传与结构化结果展示，不宣称医疗级自动检测能力。

## 5. Android 开发规则

- Kotlin + Jetpack Compose；最低 Android 10 / API 29。
- 一个 APK，两套角色导航；共享数据层和通信层，不复制业务实现。
- 适老化 UI 使用大字号、大触控区、高对比、少层级、固定布局和明确语音状态。
- Composable 只渲染状态并发送事件；网络、Room、播放器、录音器和系统能力位于可替换接口后。
- 使用不可变状态、单向数据流和 ViewModel；Composable 不直接访问数据库或网络。
- 所有权限按功能就地申请，并提供拒绝后的可理解提示。
- 模型 API Key 只保存在老人模式设备，使用 Android Keystore + AES-GCM；家属模式不得请求或同步 Key。
- 本地提醒、SOS、电话和音乐在云端不可用时应尽量保持可用。
- 无障碍操作按受控状态机执行，优先节点树，其次 OCR/VLM；正式实现禁止依赖固定坐标。
- 修改 Android 行为时同步更新 `docs/02-android/` 或 `docs/03-agent-system/` 中对应文档。

## 6. FastAPI 开发规则

- Python 3.12+，FastAPI，Pydantic v2，SQLAlchemy 2 async，Alembic，SQLite（异步驱动）。当前轻量阶段不引入 PostgreSQL 和 Redis。
- 首版按单进程服务设计；WebSocket 连接映射和可丢失的短期在线状态保存在进程内存，可靠业务记录必须写入 SQLite。
- REST 负责可靠提交和查询，WebSocket 负责在线实时投递；WebSocket 消息必须可通过 REST 重放。
- 不代理模型请求，不保存老人 API Key，不保存完整语音或完整聊天原文。
- 使用 JWT access token + refresh token；老人设备使用可吊销的 device credential。
- 事件写库成功后再尝试 WebSocket 投递，客户端 ACK 后更新送达状态。
- 所有写操作支持幂等键；金额、权限、设备归属和老人家属绑定必须服务端验证。
- 路由保持薄，业务逻辑放 service，数据库操作放 repository。
- SOS 写库和通知失败不能阻止老人端本地拨号或短信；服务端只负责协同通知和状态跟踪。
- 修改接口时同步更新 `docs/04-middle-server/api-contract.md` 和 OpenAPI 测试。

## 7. Agent 与数据规则

- 新功能先定义状态、数据模型和失败路径，再实现 UI。
- Android 网络、数据库、模型 Provider 和系统能力均通过接口注入。
- 跨端事件必须有唯一 ID、创建时间、发送者、接收者、类型、版本和幂等策略。
- 金额使用最小货币单位整数或十进制定点类型，禁止用浮点数判断支付阈值。
- 传输层时间使用 UTC ISO 8601，客户端展示时转换到本地时区。
- Tool Use 使用强类型 schema；支付、医疗、紧急和隐私工具必须经过确定性 Policy Engine。
- 长短期记忆默认保存在老人端，检索后最小化发送给模型。
- 面向老人的文案简短、明确，不暴露技术术语。

## 8. 安全规则

- 不提交真实 API Key、手机号、验证码、签名文件、证书私钥和数据库密码。
- 模型 API Key 使用 Android Keystore 生成的 AES-GCM 密钥加密，密文存 DataStore；只在请求前短暂解密到内存。
- 日志脱敏 Authorization、Cookie、手机号、精确地址、医疗信息和聊天内容。
- 家属端默认只能查看被授权的状态摘要，不能查看完整聊天原文。
- “已确认服药”仅表示老人完成了确认动作，不能推断实际服药结果。
- 医疗功能仅用于提醒、记录和求助，不进行诊断或修改药物剂量。

## 9. 测试与完成标准

Android 相关改动至少执行可用的单元测试、Compose/UI 测试、lint 和 debug 构建。

MiddleServer 相关改动至少执行 `pytest`、Ruff、类型检查和 OpenAPI 变更检查。

完成任务前：

1. 检查 diff 是否包含秘密或生成文件。
2. 更新受影响文档。
3. 说明已运行的测试和未验证风险。
4. 不把未实现功能描述为已完成。

## 10. Codex 工作方式

- 从仓库根目录启动 Codex。
- 先总结任务、约束和相关目录，再检查仓库现状。
- 根据根目录 `README.md` 的模块映射阅读对应文档。
- 复杂任务按 `docs/06-development/milestones/` 拆分小步骤。
- 优先小批量修改，不做无关重构。
- Android 基础 UI 已完成；中台按明确需求小步开发，不提前扩展尚未进入任务范围的 M04 功能。
- Android 任务只修改 `AndroidAgent/` 与必要文档。
- 服务端任务只修改 `MiddleServer/` 与必要文档。
