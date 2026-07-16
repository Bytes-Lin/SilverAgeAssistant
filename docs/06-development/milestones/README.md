# 开发里程碑

按顺序执行，每个里程碑完成后保持主分支可构建、可测试。

Android 老人端和家属端基础 UI 已完成，M04 已开始。当前已实现 FastAPI 家属注册与老人设备绑定子任务；事件同步、WebSocket 和其余家庭通信能力继续按明确任务推进。中台使用 SQLite 单进程方案。

1. [`M01-android-ui.md`](M01-android-ui.md)：老人端基础 UI。
2. [`M02-voice-qa.md`](M02-voice-qa.md)：ASR → MLLM → TTS 闭环。
3. [`M03-local-reminders-and-memory.md`](M03-local-reminders-and-memory.md)：本地提醒与记忆。
4. [`M04-family-communication.md`](M04-family-communication.md)：FastAPI 与双端通信。
5. [`M05-agent-tools.md`](M05-agent-tools.md)：Tool Use 与生活助手。
6. [`M06-safety-and-testing.md`](M06-safety-and-testing.md)：SOS、图像接口和安全测试。
