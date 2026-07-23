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

老人端“模型服务设置”提供本机 API Key 输入入口。输入内容只存在于当前页面状态，保存成功后立即从页面状态清空；页面重新打开时不解密回填完整 Key，只展示“已配置”和末四位掩码。当前页面只负责本地保存、替换和删除，不主动发起模型请求验证 Key，以免保存动作产生额外用量。删除操作必须二次确认。

该入口是开发联调和用户本人配置的临时方案，不改变“密钥不经过中台”的边界。家属端远程模型配置仍只包含服务地址、模型名、上下文长度和生成参数。

## 3. Provider 接口

```text
ChatModelProvider
- stream(ChatRequest): Flow<ChatStreamEvent>

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
- `dialect`（`standard` 或 `llama_cpp`）

开发服务器示例：

```text
base_url = http://58.199.163.98:11435
chat_model = qwen3_5
dialect = llama_cpp
```

真机使用开发机局域网 IP，不能使用模拟器专用地址。

### 4.1 Android 文字聊天首版

“和我说话”页面直接复用 `ChatModelProvider` 和 `AgentChatCoordinator`，不依赖 FastAPI 中台。一次请求按以下顺序构造：

1. 老人端系统提示词；
2. 设备私有 `MEMORY.md` 中与身份、家属和偏好有关的长期记忆；
3. 已完成的本轮会话历史；
4. 当前用户文本；
5. 强类型工具定义。

长期记忆作为 system prompt 内的受限事实区段注入。该区段不能覆盖系统安全规则，且完整手机号、密钥、验证码等不得写入或发送给模型。

Provider 使用 OpenAI 兼容的 `POST /v1/chat/completions` 与 SSE 流式响应。首版只开放 `temperature`、`top_p`、`top_k` 三个采样参数；输出长度使用应用固定上限，不作为用户采样配置。日常聊天固定关闭思考：

- llama.cpp 方言发送 `chat_template_kwargs.enable_thinking=false`；
- Android 同时过滤 `reasoning_content` 和流式 `<think>...</think>`，不得把内部思考展示给老人；
- `top_k` 作为 llama.cpp 扩展字段发送；标准云端方言不发送非标准字段。

Provider 只负责协议、鉴权、流式解析和错误分类，不执行工具。`AgentChatCoordinator` 聚合分片工具参数、按注册表校验工具并执行，再把 assistant tool call 和 tool result 追加到下一轮模型请求。当前内置的低风险测试工具是 `get_current_time`，返回老人设备当前日期、时间、星期和时区。

低风险工具同时包含 `get_weather`。模型提出天气查询后，工具从共享天气 Repository 取得当前位置的当前天气、今天和未来三天；缓存期内返回缓存，不重复访问 Open-Meteo。工具不会把经纬度加入模型上下文。天气服务失败、定位未授权或定位不可用时返回结构化错误，由模型用简短中文说明下一步。

页面状态为 `Idle / Connecting / Thinking / UsingTool / Responding`。用户可以取消生成并保留已收到的部分内容；网络失败可原请求重试，空回复和截断流会显示可理解错误。首版聊天历史只保存在当前进程内，不写入日志、Room 或中台。

Debug 默认配置位于 `BuildConfig.MODEL_BASE_URL` 与 `BuildConfig.CHAT_MODEL`，可通过未入库的 `AndroidAgent/dev.properties` 中的 `modelBaseUrl` 和 `chatModel` 覆盖。首次运行生成应用私有 `files/agent/model-config.json`；之后老人端可从中台拉取家属设置的非敏感配置并覆盖本地 JSON。每轮聊天动态读取最新地址、模型、方言、最大 Token 和采样参数，无需重启应用。JSON 从备份和设备迁移中排除。

Release 默认不内置服务地址和模型名，并拒绝明文 HTTP 模型地址。云端 API Key 经 Android Keystore AES-GCM 加密后写入独立 Preferences DataStore，不写入 `model-config.json`、不经过中台，并从 Android 备份和设备迁移中排除；没有配置 Key 时不发送 `Authorization` 请求头。

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
- 统计来源功能。

`UsageTrackingChatModelProvider` 位于共享 `ChatModelProvider` 外层，因此所有复用该模型组件的 Agent 功能都会进入同一份 Room 账本；工具调用后的追加模型轮次也分别记录。请求统一发送 `stream_options.include_usage=true`。服务返回完整 usage 时使用服务端 Token；本地 llama 服务未返回 usage、字段不完整或流被取消时，使用字符权重估算并标记 `contains_estimated_values`。

“和我说话”顶部显示上下文圆形进度，分子使用最近一轮请求的输入 Token，分母使用家属模型配置下发的 `context_window_tokens`。字段范围为 1024—2000000，不能小于最大生成 Token；旧配置缺少该字段时兼容使用 `ModelUsagePolicy.DEFAULT_CONTEXT_WINDOW_TOKENS`（32768）。该值是应用侧上下文管理上限，不等同于厂商模型宣称的最大窗口；后续上下文压缩必须复用同一配置。

Room 保存 MLLM 输入/输出 Token、ASR/TTS 次数及扩展计量字段。WorkManager 每小时读取尚未汇报记录，按 modality/provider/model/feature 聚合上传 FastAPI；只有中台确认成功后才标记已汇报。任务不依赖 Android 的“已验证互联网”约束，因为 VPN、私有网络或只可访问中台的网络可能被系统标记为部分连接；实际请求失败时由 Worker 返回 retry 并保留本地记录。家属手动刷新时，中台可通过认证 WebSocket 发送 `MODEL_USAGE_REPORT_REQUESTED`，老人端使用同一 Worker 执行一次性即时上传；周期任务与即时任务通过进程内互斥串行化，本地 `batch_id` 幂等规则保持不变。不得上传 API Key、完整提示词、Tool 参数/结果、完整音频或完整回复。家属端显示“客户端统计/含本地估算”。中台接口见 [`../04-middle-server/model-usage-reporting-requirements.md`](../04-middle-server/model-usage-reporting-requirements.md)。

每日用量边界使用老人位置时区：天气 Repository 将当前位置请求 Open-Meteo
`timezone=auto` 得到的 IANA 时区写入本地轻量缓存，用量 Worker 随批次发送时区名称和
来源标记。家属端不传时区，中台返回老人当地 `current_date`。尚未取得位置时区时仅允许
以设备系统时区作为显式标记的降级值，不能硬编码城市时区。

## 7. 图像分析接口

MVP 接收单张压缩图片，调用 MLLM，并要求 JSON Schema 输出风险等级、是否疑似跌倒、是否看见人物、是否需要人工查看和简短理由。

任何高风险结果都先提示家属/老人人工确认，不直接视为事实。

## 8. 本地 llama-server

llama.cpp 的 `llama-server` 提供 OpenAI 兼容 HTTP 接口，可用于文本/工具调用流程模拟。多模态能力取决于所选模型和服务器版本；若本地模型不支持图像，则使用 Mock VLM Provider。

详见 `docs/06-development/local-llama-server.md`。
