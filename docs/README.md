# 文档索引

文档按“产品 → 架构 → Android → Agent → 中间服务器 → 安全 → 开发 → 测试 → 路线图”组织。

## 00 产品

* [`project-description.md`](00-product/project-description.md)：项目完整说明，集中介绍产品定位、功能、亮点、技术架构、安全边界和当前进度。
* [`product-overview.md`](00-product/product-overview.md)：服务对象、核心价值、产品形态和基本功能。
* [`requirements-and-scope.md`](00-product/requirements-and-scope.md)：功能优先级、范围和非功能需求。

## 01 架构

* [`system-architecture.md`](01-architecture/system-architecture.md)：整体拓扑、Android/FastAPI 分层和可靠性边界。
* [`architecture-decisions.md`](01-architecture/architecture-decisions.md)：已确认的关键技术决策及其影响。

## 02 Android

* [`android-ui-and-features.md`](02-android/android-ui-and-features.md)：老人端和家属端 UI、基础功能和交互规则。
* [`family-mode.md`](02-android/family-mode.md)：家属端状态、指令、用量和隐私边界。

## 03 Agent 系统

* [`elder-agent-design.md`](03-agent-system/elder-agent-design.md)：Tool Use、Policy、任务状态机、Memory、上下文压缩和 RAG。
* [`agent-development-status-2026-08-11.md`](03-agent-system/agent-development-status-2026-08-11.md)：按当前可执行代码汇总主聊天、Tools、语音、GUI Agent、状态监控 Agent 的已实现内容、限制和下一步。
* [`agent-tools-and-capabilities.md`](03-agent-system/agent-tools-and-capabilities.md)：按实际 Registry 和装配代码列出各 Agent 的 Tools、GUI Actions、内部能力及共享边界。
* [`today-reminder-tool-interface.md`](03-agent-system/today-reminder-tool-interface.md)：聊天 Agent 读取端侧今日提醒的只读 Tool 契约和完成状态边界。
* [`context-compression-design.md`](03-agent-system/context-compression-design.md)：主 Agent 的上下文预算、8 轮滑动窗口、Summary、启动 Memory 快照、Token 统计与可复用组件边界。
* [`model-integration.md`](03-agent-system/model-integration.md)：OpenAI 兼容 MLLM、ASR/TTS Provider、本地 Key 和 llama-server。
* [`voice-interaction-design.md`](03-agent-system/voice-interaction-design.md)：全局语音开关、聊天与 GUI ASR/TTS、播放仲裁和资源释放边界。

## 04 MiddleServer

* [`fastapi-communication.md`](04-middle-server/fastapi-communication.md)：REST、WebSocket、事件、幂等、认证和断线恢复。
* [`api-contract.md`](04-middle-server/api-contract.md)：FastAPI API 草案。
* [`admin-user-management.md`](04-middle-server/admin-user-management.md)：同一 FastAPI 应用内的本机管理登录、绑定关系展示、修改、软撤销和公网监听边界。
* [`family-registration-and-binding-requirements.md`](04-middle-server/family-registration-and-binding-requirements.md)：家属注册、绑定码生成和手机号联合校验的中台交付需求。
* [`device-rebinding-requirements.md`](04-middle-server/device-rebinding-requirements.md)：家属重新生成绑定码及老人设备安全恢复绑定的中台交付需求。
* [`family-notification-and-reminder-requirements.md`](04-middle-server/family-notification-and-reminder-requirements.md)：家属通知、一次性提醒、设备补拉和 ACK 的中台交付需求。
* [`reminder-completion-and-history-requirements.md`](04-middle-server/reminder-completion-and-history-requirements.md)：老人完成确认回传、离线重试、家属提醒记录查询和账号级归档清除的中台需求；归档接口当前待交付。
* [`elder-family-profile-sync-requirements.md`](04-middle-server/elder-family-profile-sync-requirements.md)：老人设备同步真实家属联系人、完整手机号和权限快照的中台交付需求。
* [`remote-model-configuration-requirements.md`](04-middle-server/remote-model-configuration-requirements.md)：家属远程下发非敏感模型配置、老人设备补拉和 API Key 安全边界。
* [`model-usage-reporting-requirements.md`](04-middle-server/model-usage-reporting-requirements.md)：老人设备每小时汇报聚合用量、家属查询汇总和隐私边界。
* [`safety-monitoring-and-events-requirements.md`](04-middle-server/safety-monitoring-and-events-requirements.md)：状态检测间隔下发、结构化异常事件、家属今日补拉和首次 ACK 的中台交付需求。

## 05 安全

* [`data-security-and-privacy.md`](05-security/data-security-and-privacy.md)：数据分类、凭证、权限、日志与隐私。
* [`risk-register.md`](05-security/risk-register.md)：主要产品和技术风险。

## 06 开发

* [`development-workflow.md`](06-development/development-workflow.md)：Git、分支、PR 和开发顺序。
* [`codex-usage.md`](06-development/codex-usage.md)：Codex 配置和使用方式。
* [`local-llama-server.md`](06-development/local-llama-server.md)：本地 OpenAI 兼容模型模拟。
* [`android-review-handoff-2026-07-30.md`](06-development/android-review-handoff-2026-07-30.md)：当前 Android 实现与文档对照、粗审发现和下一次 Agent/GUI Agent 开发会话起点。
* [`non-agent-progress-2026-08-12.md`](06-development/non-agent-progress-2026-08-12.md)：不含 Agent/GUI Agent 的 Android、中台、提醒、天气、新闻、语音基础和家庭协同进度快照。
* [`milestones/`](06-development/milestones/)：可执行开发里程碑。

## 07 测试

* [`test-plan.md`](07-testing/test-plan.md)：客户端、服务端、Agent、SOS 和适老化测试。

## 08 路线图

* [`roadmap.md`](08-roadmap/roadmap.md)：从工程初始化到试点的阶段规划。
* [`todo.md`](08-roadmap/todo.md)：按当前代码核对的已完成与待开发功能清单。

## 99 附录

* [`references.md`](99-appendix/references.md)：设计参考资料。
