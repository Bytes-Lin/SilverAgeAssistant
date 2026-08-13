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
base_url = https://model-provider.example.invalid
chat_model = example-model
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

Provider 只负责协议、鉴权、流式解析和错误分类，不执行工具。`AgentChatCoordinator` 聚合分片工具参数、按注册表校验工具并执行，再把 assistant tool call 和 tool result 追加到下一轮模型请求。主聊天当前注册 `get_current_time`、`get_weather`、`list_today_reminders`、`call_family_contact`、`gui_agent`，并在中台 Reporter 可用时注册 `report_family_situation`；各 Tool 的风险策略、确定性路由和边界见 [`agent-tools-and-capabilities.md`](agent-tools-and-capabilities.md)。

`get_weather` 从共享天气 Repository 取得当前位置的当前天气、今天和未来三天；缓存期内返回缓存，不重复访问 Open-Meteo。工具不会把经纬度加入模型上下文。天气服务失败、定位未授权或定位不可用时返回结构化错误，由模型用简短中文说明下一步。

页面状态为 `Idle / Connecting / Thinking / UsingTool / Responding`。用户可以取消生成并保留已收到的部分内容；网络失败可原请求重试，空回复和截断流会显示可理解错误。首版聊天历史只保存在当前进程内，不写入日志、Room 或中台。

Debug 默认配置位于 `BuildConfig.MODEL_BASE_URL` 与 `BuildConfig.CHAT_MODEL`，可通过未入库的 `AndroidAgent/dev.properties` 中的 `modelBaseUrl` 和 `chatModel` 覆盖。首次运行生成应用私有 `files/agent/model-config.json`；之后老人端可从中台拉取家属设置的非敏感配置并覆盖本地 JSON。中台发送 `MODEL_CONFIG_AVAILABLE` 最小提示后，老人端立即通过 REST 补拉并原子热更新；会话恢复和进入聊天时继续补偿拉取。每轮聊天动态读取最新地址、模型、方言、最大 Token 和采样参数，无需重启应用；已开始的请求使用启动时快照，下一次请求使用新配置。JSON 从备份和设备迁移中排除。

Release 默认不内置服务地址和模型名，并拒绝明文 HTTP 模型地址。云端 API Key 经 Android Keystore AES-GCM 加密后写入独立 Preferences DataStore，不写入 `model-config.json`、不经过中台，并从 Android 备份和设备迁移中排除；没有配置 Key 时不发送 `Authorization` 请求头。

## 5. ASR/TTS

ASR 使用阿里云百炼 Qwen-Audio WebSocket：录音期间把 PCM16/16 kHz/单声道音频以内存帧
发送，不创建本地录音文件。TTS 等待 MLLM 完整回答后，在一个 WebSocket task 中只发送一次
`continue-task`，随后 `finish-task`，暂不由 Android 按句切分，也不对流式文字增量发起
合成。

Android 已实现上述 Qwen 协议。语音 Key 与 MLLM Key 使用两个独立 Keystore 别名，并共用
已排除备份的端侧凭证 DataStore。ASR 二进制帧和 TTS PCM 帧只在内存中流转；TTS 当前通过
`AudioTrack` 播放 PCM，因此家属配置其他返回格式时会保留文字并显示不支持播报的提示，
不会创建临时音频文件。

老人端聊天、家属新通知和新闻共用全局语音开关、单一录音/播放仲裁器以及 ASR/TTS
Provider；家属模式不启用语音交互。全局状态、按住说话、通知播报去重、新闻播放列表、
AudioFocus 和失败降级的完整设计见
[`voice-interaction-design.md`](voice-interaction-design.md)。

家属远程配置只下发非敏感语音连接信息：ASR/TTS 共用的 WebSocket URL、各自模型名，以及
TTS 音色、输出格式、采样率、音量、语速、音调和语言。ASR/TTS 共用一把老人端语音 Key，
使用与 MLLM Key 相同的 Keystore + AES-GCM 实现但存入独立凭证槽位；Key 不属于远程配置。
字段草案见
[`../04-middle-server/remote-model-configuration-requirements.md`](../04-middle-server/remote-model-configuration-requirements.md)。

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
- ASR 调用次数；
- LLM 输入/输出 token（响应返回或本地估算）；
- TTS 调用次数；
- 成功/失败；
- MLLM 统计来源功能；ASR/TTS 只累计各自调用次数，不按业务来源拆分。

`UsageTrackingChatModelProvider` 位于共享 `ChatModelProvider` 外层，因此所有复用该模型组件的 Agent 功能都会进入同一份 Room 账本；工具调用后的追加模型轮次也分别记录。请求统一发送 `stream_options.include_usage=true`。服务返回完整 usage 时使用服务端 Token；本地 llama 服务未返回 usage、字段不完整或流被取消时，使用字符权重估算并标记 `contains_estimated_values`。

主聊天 Agent 与 GUI Agent 可以复用同一个底层模型 Provider、ASR Provider 和 TTS
Provider，但每个 Agent 必须使用独立 Coordinator、上下文、记忆实例和用量归属：

- 主聊天 Agent 使用 `feature=conversation`；
- 主聊天上下文压缩请求使用 `feature=conversation_context_compression`，与普通聊天推理分开记账；
- GUI Agent 使用 `feature=gui_agent`；
- GUI Tool 调用产生的模型轮次不得累计到主聊天上下文或记忆；
- ASR/TTS Provider 本身不读取 Agent 记忆。调用时可以携带
  `MAIN_CHAT_AGENT/GUI_AGENT` owner 用于运行时路由和取消，但用量只按 ASR/TTS modality
  聚合，不按 Agent 或业务来源拆分；
- 当前只允许 `get_current_time` 同时出现在两个 Agent 的 Tool 能力视图。Tool 实例通过
  共享目录复用，但两个 Agent 使用独立 Registry；天气、联系人、提醒和其他 Tool 暂不
  扩展到 GUI Agent。

状态监控 Agent 不使用 Tool Calling。视觉模型只输出严格的状态 JSON，图像获取、六小时
状态保存、连续异常阈值、家属事件、证据上传和短信均由端侧确定性编排器调用，不能由模型
自行选择。

GUI Agent 当前通过 `OpenAiGuiVisionPlanner` 直接使用同一份 OpenAI 兼容连接配置和本地
加密 API Key，并复用该配置的连接与读取超时（当前默认 10 秒连接、120 秒读取），不得退回
OkHttp 默认约 10 秒读取超时。节点优先模式的请求包含 GUI system prompt、当前任务、当前
resize 截图、当前无障碍节点、最近 8 个短期步骤摘要和 GUI Registry 的 Tool schema；Debug
纯坐标实验不发送无障碍节点。两种模式均不传入主聊天消息、主 Agent 长期记忆或之前的截图。
响应必须是单动作 JSON，`click_point` 应将 `x`、`y` 分别输出为单个 `0..1000` 数字；端侧仅
对常见二元数组格式做有界兼容，截断或不完整 JSON 不执行。模型返回的 MLLM usage 以
`feature=gui_agent` 单独写入 Room。

“和我说话”顶部显示上下文圆形进度，分子来自主 Agent `AgentContextManager` 当前实际组装上下文的 Token 占用：请求发送前使用本地估算，Provider 返回 `prompt_tokens` 后校准，轮次提交或后台压缩完成后重新计算；分母使用家属模型配置下发的 `context_window_tokens`。字段范围为 1024—2000000，不能小于最大生成 Token；旧配置缺少该字段时兼容使用 `ModelUsagePolicy.DEFAULT_CONTEXT_WINDOW_TOKENS`（32768）。该值是应用侧上下文管理上限，不等同于厂商模型宣称的最大窗口。

Room 保存 MLLM 输入/输出 Token，以及 ASR/TTS 实际调用次数。语音 MVP 不保存音频时长、
TTS 字符数或聊天/通知/新闻来源明细；既有协议要求的扩展字段固定为 0。WorkManager 每小时
读取尚未汇报记录，按 modality/provider/model/feature 聚合上传 FastAPI；只有中台确认成功后才标记已汇报。任务不依赖 Android 的“已验证互联网”约束，因为 VPN、私有网络或只可访问中台的网络可能被系统标记为部分连接；实际请求失败时由 Worker 返回 retry 并保留本地记录。家属手动刷新时，中台可通过认证 WebSocket 发送 `MODEL_USAGE_REPORT_REQUESTED`，老人端使用同一 Worker 执行一次性即时上传；周期任务与即时任务通过进程内互斥串行化，本地 `batch_id` 幂等规则保持不变。不得上传 API Key、完整提示词、Tool 参数/结果、完整音频或完整回复。家属端显示“客户端统计/含本地估算”。中台接口见 [`../04-middle-server/model-usage-reporting-requirements.md`](../04-middle-server/model-usage-reporting-requirements.md)。

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
