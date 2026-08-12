# 开发里程碑

按顺序执行，每个里程碑完成后保持主分支可构建、可测试。

Android 老人端和家属端基础 UI 已完成。非 Agent 部分已跨里程碑实现注册绑定与会话恢复、
天气和新闻、真实家属联系人、家庭通知与一次性提醒、提醒完成回传与家属记录、截止后每小时
本地通知、老人/家属前台自动补拉、语音基础 Provider、模型配置与用量。当前非 Agent 缺口包括家属提醒记录中台归档接口、老人时区同步、稍后提醒重新调度、本地提醒 CRUD、多老人管理、独立 SOS、发布配置和
多设备验收。Agent/GUI Agent 进度不在本段重复统计。详见
[`Agent 系统开发进度`](../../03-agent-system/agent-development-status-2026-08-11.md)、
[`非 Agent 功能开发进度`](../non-agent-progress-2026-08-12.md) 和
[`开发 TODO`](../../08-roadmap/todo.md)。

1. [`M01-android-ui.md`](M01-android-ui.md)：老人端基础 UI。
2. [`M02-voice-qa.md`](M02-voice-qa.md)：ASR → MLLM → TTS 闭环。
3. [`M03-local-reminders-and-memory.md`](M03-local-reminders-and-memory.md)：本地提醒与记忆。
4. [`M04-family-communication.md`](M04-family-communication.md)：FastAPI 与双端通信。
5. [`M05-agent-tools.md`](M05-agent-tools.md)：Tool Use 与生活助手。
6. [`M06-safety-and-testing.md`](M06-safety-and-testing.md)：SOS、图像接口和安全测试。
