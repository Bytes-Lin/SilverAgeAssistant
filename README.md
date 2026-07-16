# Silver Age Assistant（银龄助手）

面向老年人的 Android AI Agent 应用。仓库采用单仓结构，同时包含 Android 客户端、FastAPI 中间服务器、Codex 配置、系统文档和测试资产。

- 远程仓库：`https://github.com/Bytes-Lin/SilverAgeAssistant`
- Android 最低版本：Android 10.0（API 29）
- 应用形态：一个 APK，包含老人模式和家属模式
- AI 接入：老人端使用用户自行配置的 API Key，直接连接云端 MLLM、ASR 和 TTS
- 双端通信：FastAPI + HTTPS REST + WebSocket
- 本地模型模拟：支持使用 `llama-server` 提供 OpenAI 兼容接口

## 仓库结构

```text
SilverAgeAssistant/
├── .codex/                 Codex 项目配置和自定义子代理
├── .gitignore
├── AGENTS.md               Codex 全局开发约束
├── CONTRIBUTING.md         Git 与协作规范
├── README.md               项目入口、模块索引和进度入口
├── AndroidAgent/           Android 客户端代码目录
├── MiddleServer/           FastAPI 中间服务器代码、迁移和测试目录
├── docs/                   产品、架构、Agent、接口和流程文档
│   ├── README.md
│   ├── 00-product/
│   ├── 01-architecture/
│   ├── 02-android/
│   ├── 03-agent-system/
│   ├── 04-middle-server/
│   ├── 05-security/
│   ├── 06-development/
│   ├── 07-testing/
│   ├── 08-roadmap/
│   └── 99-appendix/
└── scripts/                仓库检查和本地模型启动脚本
```

`AndroidAgent/` 与 `MiddleServer/` 仅存放对应代码、配置和测试，不再维护局部 README 或 AGENTS。所有说明统一由根目录 `README.md`、`AGENTS.md` 和 `docs/` 管理。

## 开发模块与文档对应关系

| 开发部分 | 代码目录 | 必读文档 | 对应里程碑 |
|---|---|---|---|
| 产品目标与范围 | 根目录 / 全仓库 | [`产品总览`](docs/00-product/product-overview.md)、[`需求与范围`](docs/00-product/requirements-and-scope.md) | [`路线图`](docs/08-roadmap/roadmap.md) |
| 总体系统架构 | 全仓库 | [`系统架构`](docs/01-architecture/system-architecture.md)、[`架构决策`](docs/01-architecture/architecture-decisions.md) | 全部里程碑 |
| 老人端 UI 与基础功能 | `AndroidAgent/` | [`Android UI 与功能`](docs/02-android/android-ui-and-features.md) | [`M01 老人端基础 UI`](docs/06-development/milestones/M01-android-ui.md) |
| 语音问答与模型接入 | `AndroidAgent/` | [`模型接入`](docs/03-agent-system/model-integration.md)、[`Agent 系统设计`](docs/03-agent-system/elder-agent-design.md) | [`M02 语音问答闭环`](docs/06-development/milestones/M02-voice-qa.md) |
| 本地提醒与记忆 | `AndroidAgent/` | [`Agent 系统设计`](docs/03-agent-system/elder-agent-design.md)、[`Android UI 与功能`](docs/02-android/android-ui-and-features.md) | [`M03 本地提醒与记忆`](docs/06-development/milestones/M03-local-reminders-and-memory.md) |
| 家属端 UI 与状态管理 | `AndroidAgent/` | [`家属模式`](docs/02-android/family-mode.md)、[`Android UI 与功能`](docs/02-android/android-ui-and-features.md) | [`M04 家属通信`](docs/06-development/milestones/M04-family-communication.md) |
| FastAPI 中间服务器 | `MiddleServer/` | [`FastAPI 通信设计`](docs/04-middle-server/fastapi-communication.md)、[`API 草案`](docs/04-middle-server/api-contract.md) | [`M04 家属通信`](docs/06-development/milestones/M04-family-communication.md) |
| Tool Use、RAG 与生活助手 | `AndroidAgent/` | [`Agent 系统设计`](docs/03-agent-system/elder-agent-design.md) | [`M05 Agent Tool Use`](docs/06-development/milestones/M05-agent-tools.md) |
| SOS、图像接口与安全 | `AndroidAgent/`、`MiddleServer/` | [`安全与隐私`](docs/05-security/data-security-and-privacy.md)、[`风险清单`](docs/05-security/risk-register.md) | [`M06 安全与测试`](docs/06-development/milestones/M06-safety-and-testing.md) |
| 测试与验收 | 全仓库 | [`测试计划`](docs/07-testing/test-plan.md) | 各里程碑验收项 |
| Codex 使用与 Git 流程 | 根目录 / `.codex/` | [`Codex 使用`](docs/06-development/codex-usage.md)、[`开发流程`](docs/06-development/development-workflow.md)、[`CONTRIBUTING.md`](CONTRIBUTING.md) | 持续执行 |

完整文档索引见 [`docs/README.md`](docs/README.md)。

## 当前开发顺序

按以下里程碑推进，每完成一个里程碑都应保持主分支可构建、可测试：

1. [`M01：老人端基础 UI`](docs/06-development/milestones/M01-android-ui.md)
2. [`M02：语音问答闭环`](docs/06-development/milestones/M02-voice-qa.md)
3. [`M03：本地提醒与记忆`](docs/06-development/milestones/M03-local-reminders-and-memory.md)
4. [`M04：家属通信与 FastAPI`](docs/06-development/milestones/M04-family-communication.md)
5. [`M05：Agent Tool Use 与生活助手`](docs/06-development/milestones/M05-agent-tools.md)
6. [`M06：SOS、图像接口与安全测试`](docs/06-development/milestones/M06-safety-and-testing.md)

老人端和家属端基础 UI 已完成，M04 已开始按明确需求小步接入中台。当前只完成家属注册、老人档案、一次性绑定码、设备绑定与绑定查询；事件同步、WebSocket、提醒指令和用量上报仍未实现。

中台首版采用 SQLite 本地数据库，以单进程轻量开发和联调为目标；不引入 PostgreSQL、Redis、Docker 数据库依赖、`.env.example` 或 `infra/`。如后续出现多实例部署、并发写入或共享在线状态需求，再单独评估数据库和缓存迁移。

当前第一可验收版本只完成：

```text
老人端适老化 UI
→ 录音
→ ASR
→ MLLM
→ TTS
→ 播放与状态反馈
```

## 技术边界

- 老人端与家属端通过 FastAPI 中间服务器交换状态、指令、审批和 SOS 事件。
- 老人端直接调用用户自己的云端模型 API；模型 API Key 只在老人设备本地加密保存。
- 中间服务器不代理日常 LLM、ASR 或 TTS 请求，也不保存老人模型 API Key。
- 当前不接入 EMAS、FCM 等移动推送服务。WebSocket 只在客户端进程存活且连接正常时实时通信，断线后通过 REST 补拉。
- SOS 必须同时执行老人手机本地拨号或短信兜底，不能等待服务器通信成功后再求救。
- 跌倒检测首版只实现图像选择、上传至云端 MLLM 和结构化结果展示，不宣称医疗级自动检测能力。

## 开始开发

```bash
git clone https://github.com/Bytes-Lin/SilverAgeAssistant.git
cd SilverAgeAssistant
```

开始任务前：

1. 阅读 [`AGENTS.md`](AGENTS.md)。
2. 在上方“开发模块与文档对应关系”中找到任务对应文档。
3. 阅读当前里程碑文件。
4. 只实现当前任务范围，完成测试后再更新进度。
