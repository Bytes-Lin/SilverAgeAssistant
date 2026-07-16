# 文档索引

文档按“产品 → 架构 → Android → Agent → 中间服务器 → 安全 → 开发 → 测试 → 路线图”组织。

## 00 产品

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
* [`model-integration.md`](03-agent-system/model-integration.md)：OpenAI 兼容 MLLM、ASR/TTS Provider、本地 Key 和 llama-server。

## 04 MiddleServer

* [`fastapi-communication.md`](04-middle-server/fastapi-communication.md)：REST、WebSocket、事件、幂等、认证和断线恢复。
* [`api-contract.md`](04-middle-server/api-contract.md)：FastAPI API 草案。
* [`family-registration-and-binding-requirements.md`](04-middle-server/family-registration-and-binding-requirements.md)：家属注册、绑定码生成和手机号联合校验的中台交付需求。

## 05 安全

* [`data-security-and-privacy.md`](05-security/data-security-and-privacy.md)：数据分类、凭证、权限、日志与隐私。
* [`risk-register.md`](05-security/risk-register.md)：主要产品和技术风险。

## 06 开发

* [`development-workflow.md`](06-development/development-workflow.md)：Git、分支、PR 和开发顺序。
* [`codex-usage.md`](06-development/codex-usage.md)：Codex 配置和使用方式。
* [`local-llama-server.md`](06-development/local-llama-server.md)：本地 OpenAI 兼容模型模拟。
* [`milestones/`](06-development/milestones/)：可执行开发里程碑。

## 07 测试

* [`test-plan.md`](07-testing/test-plan.md)：客户端、服务端、Agent、SOS 和适老化测试。

## 08 路线图

* [`roadmap.md`](08-roadmap/roadmap.md)：从工程初始化到试点的阶段规划。

## 99 附录

* [`references.md`](99-appendix/references.md)：设计参考资料。
