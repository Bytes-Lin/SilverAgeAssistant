# MILESTONE 02：语音问答闭环

## 目标

完成录音 → ASR → LLM → TTS → 播放。

## 任务

- [x] 老人端全局语音开关、跨聊天/通知/新闻播报与播放仲裁设计；
- [x] 录音仅内存处理、完整回答单次播报、新闻前 5 条单次播报和 ASR/TTS 次数统计约束；
- [x] Android 家属语音模型配置、远程 JSON、本地存储和老人补拉热更新；
- [x] 中台模型配置 `voice` 字段、迁移、校验和接口测试；
- [x] 老人端模型配置 WebSocket 提示补拉与下一次请求热更新接线；
- [x] AudioRecord 内存 PCM 采集和 Android 实现；
- [x] Qwen ASR WebSocket Provider、最终识别结果和错误降级；
- [x] ChatModelProvider + llama-server/OpenAI-compatible SSE 实现；
- [x] Qwen TTS WebSocket Provider、PCM AudioTrack 播放和取消；
- [x] 聊天按住说话手势、连接中松手排队、页面/后台取消和 `AudioRecord` 可靠释放；
- [x] GUI Agent 覆盖层按住说话、单步 TTS 和全局语音开关联动；
- [x] 新闻前 5 条带本地日期时间的单次 TTS、家属新通知首次入库播报；
- [x] 主动协程取消静默处理，禁止向老人显示调试异常和网络栈消息；
- [x] ApiCredentialStore（Keystore AES-GCM + DataStore）；
- [x] 文字对话状态机；
- [x] 文字生成取消、超时错误映射、重试；
- [x] 全局模型用量记录、上下文占用和小时聚合上报；
- [x] 不记录敏感请求头；
- [ ] 完整单元测试、多设备真机和 API 29 测试（文字聊天单元测试已完成，TTS 已初测，
  ASR 仍在真机验证）。

文字聊天子任务还包括：

- [x] system prompt、工具和用户消息请求组装；
- [x] `temperature`、`top_p`、`top_k` 参数；
- [x] 日常聊天关闭思考并过滤 reasoning 内容；
- [x] `get_current_time` 低风险测试工具；
- [x] ASR、TTS 与语音状态接入；
- [ ] 系统 TTS 显式降级 Provider；

## 验收

老人点击按钮说一句话，能看到识别文本、AI 回复并听到语音；失败时能理解下一步。
