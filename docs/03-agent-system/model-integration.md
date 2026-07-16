# 模型接入设计

## 1. 原则

- 每位老人使用自己开通的 API Key；
- Key 只在老人设备保存；
- Android 直接调用云端模型，减少语音链路中转；
- LLM/MLLM 使用 OpenAI 兼容抽象；
- ASR/TTS 使用独立 Provider，支持 HTTP 或 WebSocket；
- 开发阶段可切换本地 llama-server。

## 2. 本地凭证

`ApiCredentialStore`：

1. Android Keystore 生成 AES-GCM 密钥；
2. API Key 加密后存 DataStore；
3. 不参与 Android 自动备份；
4. 调用前短暂解密到内存；
5. Authorization 日志全部屏蔽；
6. 支持验证、替换、删除；
7. 家属端只能查看“已配置/未配置”和末尾掩码，不获取明文。

## 3. Provider 接口

```text
ChatModelProvider
- chat(messages, tools, stream)

MultimodalModelProvider
- analyzeTextAndImages(messages, images, tools)

AsrProvider
- transcribeFile(audio)
- optional startRealtimeSession()

TtsProvider
- synthesize(text, voice)
- optional startStreamingSession()
```

应用业务代码不得直接依赖百炼具体请求类。

## 4. OpenAI 兼容配置

配置项：

- `base_url`
- `api_key_encrypted`
- `chat_model`
- `vision_model`
- `supports_tools`
- `supports_streaming`
- `request_timeout`

开发服务器示例：

```text
base_url = http://10.0.2.2:8080/v1
chat_model = local-model
```

真机使用开发机局域网 IP，不能使用模拟器专用地址。

## 5. ASR/TTS

ASR 首版优先完整文件上传，稳定后再实现实时 WebSocket。TTS 首版可接非流式接口，随后按句切分实现流式播放。

不同厂商可能有完全不同的鉴权、音频格式和事件协议，因此 Provider 负责：

- PCM/WAV/MP3 编码；
- 采样率；
- WebSocket 生命周期；
- 临时结果与最终结果；
- 重试；
- 用量统计。

## 6. 用量上报

本地 `ModelUsageRecorder` 记录：

- provider/model；
- 开始/结束时间；
- ASR 音频秒数；
- LLM 输入/输出 token（响应返回或本地估算）；
- TTS 字符数/音频秒数；
- 成功/失败；
- 本地估算费用。

按批次上传 FastAPI。不得上传 API Key、完整提示词、完整音频或完整回复。家属端显示“本地统计/估算”。

## 7. 图像分析接口

MVP 接收单张压缩图片，调用 MLLM，并要求 JSON Schema 输出风险等级、是否疑似跌倒、是否看见人物、是否需要人工查看和简短理由。

任何高风险结果都先提示家属/老人人工确认，不直接视为事实。

## 8. 本地 llama-server

llama.cpp 的 `llama-server` 提供 OpenAI 兼容 HTTP 接口，可用于文本/工具调用流程模拟。多模态能力取决于所选模型和服务器版本；若本地模型不支持图像，则使用 Mock VLM Provider。

详见 `docs/06-development/local-llama-server.md`。
