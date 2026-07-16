# MILESTONE 02：语音问答闭环

## 目标

完成录音 → ASR → LLM → TTS → 播放。

## 任务

- [ ] AudioRecorder 接口和 Android 实现；
- [ ] ASR Provider + Mock；
- [ ] ChatModelProvider + llama-server/OpenAI-compatible 实现；
- [ ] TTS Provider + Mock；
- [ ] ApiCredentialStore；
- [ ] 对话状态机；
- [ ] 取消、超时、重试；
- [ ] 使用量记录；
- [ ] 不记录敏感请求头；
- [ ] 单元测试和 API 29 测试。

## 验收

老人点击按钮说一句话，能看到识别文本、AI 回复并听到语音；失败时能理解下一步。
