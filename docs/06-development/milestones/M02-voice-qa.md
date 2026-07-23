# MILESTONE 02：语音问答闭环

## 目标

完成录音 → ASR → LLM → TTS → 播放。

## 任务

- [ ] AudioRecorder 接口和 Android 实现；
- [ ] ASR Provider + Mock；
- [x] ChatModelProvider + llama-server/OpenAI-compatible SSE 实现；
- [ ] TTS Provider + Mock；
- [x] ApiCredentialStore（Keystore AES-GCM + DataStore）；
- [x] 文字对话状态机；
- [x] 文字生成取消、超时错误映射、重试；
- [x] 全局模型用量记录、上下文占用和小时聚合上报；
- [x] 不记录敏感请求头；
- [ ] 单元测试和 API 29 测试（文字聊天单元测试已完成，API 29 待验证）。

文字聊天子任务还包括：

- [x] system prompt、工具和用户消息请求组装；
- [x] `temperature`、`top_p`、`top_k` 参数；
- [x] 日常聊天关闭思考并过滤 reasoning 内容；
- [x] `get_current_time` 低风险测试工具；
- [ ] ASR、TTS 与语音状态接入。

## 验收

老人点击按钮说一句话，能看到识别文本、AI 回复并听到语音；失败时能理解下一步。
