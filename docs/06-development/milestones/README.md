# 开发里程碑

按顺序执行，每个里程碑完成后保持主分支可构建、可测试。

Android 老人端和家属端基础 UI 已完成。当前已跨里程碑实现文字流式聊天、老人端
ASR→MLLM→TTS 闭环、家属新通知和新闻前五条播报、长期记忆基础、天气/电话/家属报告
Tool、百度热搜新闻文字列表与暂存、家庭通知与提醒、模型配置与用量、mock 图像驱动的
独立状态监控 Agent，以及 GUI Agent 基础观察/执行框架。语音多设备验收、系统 TTS 降级、
本地提醒调度、结构化记忆治理、通用 Policy/可恢复任务状态机、完整网购外卖流程和 SOS
仍待开发。逐项状态以 [`../../08-roadmap/todo.md`](../../08-roadmap/todo.md) 为准。

1. [`M01-android-ui.md`](M01-android-ui.md)：老人端基础 UI。
2. [`M02-voice-qa.md`](M02-voice-qa.md)：ASR → MLLM → TTS 闭环。
3. [`M03-local-reminders-and-memory.md`](M03-local-reminders-and-memory.md)：本地提醒与记忆。
4. [`M04-family-communication.md`](M04-family-communication.md)：FastAPI 与双端通信。
5. [`M05-agent-tools.md`](M05-agent-tools.md)：Tool Use 与生活助手。
6. [`M06-safety-and-testing.md`](M06-safety-and-testing.md)：SOS、图像接口和安全测试。
