# 老人端全局语音交互设计

实现状态（2026-08-11）：老人端全局开关与角色隔离、独立语音 Key 加密存储、内存 PCM
录音、Qwen ASR/TTS WebSocket、AudioFocus、聊天与 GUI 覆盖层按住说话、完整回复单次播报、家属新通知
Room 去重播报、新闻前五条单次播报和 ASR/TTS 次数记录已接入。首版流式播放仅接受
`tts_response_format=pcm`。TTS 已通过初步真机测试；ASR 已完成松手竞态、页面/后台资源释放
和协程取消消息过滤，仍在继续真机验证。系统 TTS 降级、多设备兼容性和 API 29 验收仍待完成。

## 1. 范围与目标

本模块只在老人模式生效，家属模式不显示语音开关、不申请麦克风权限，也不直接调用
ASR/TTS。老人端使用一个全局开关统一控制以下能力：

1. “和我说话”按住说话：录音 → ASR → 文本 → MLLM → 文字显示 → TTS；
2. 新家属通知成功写入老人端 Room 后播报通知内容；
3. 新闻页面显示前 15 条榜单，并一次播报前 5 条新闻；
4. GUI Agent 播报单步操作提示，并在商品/订单确认时播报必要详情；覆盖层按住说话的 ASR
   文本只交给当前 GuiRun。

首版不实现唤醒词、持续监听、后台偷录、通话录音、实时 ASR WebSocket 和逐 Token
TTS。ASR 首版通过 WebSocket 发送内存中的实时音频帧，不生成完整录音文件；TTS 必须等待
MLLM 完整回答后只发起一次合成任务，首版不由客户端分句、分段或逐 Token 提交文本。

## 2. 架构边界

```text
Elder Compose UI
  ├── ConversationViewModel
  ├── ReminderViewModel / command sync
  ├── NewsViewModel
  └── Elder settings
           │
           ▼
VoiceInteractionCoordinator（应用级唯一实例）
  ├── VoiceInteractionSettingsStore（DataStore，全局开关）
  ├── AudioRecorder（Android 麦克风）
  ├── AgentAsrProvider（厂商适配）
  ├── AgentTtsProvider（厂商适配）
  ├── AudioFocusController（录音/播报/通话仲裁）
  ├── VoiceAnnouncementQueue（单播放器、优先级、去重）
  └── ModelUsageRecorder（ASR/TTS 用量）
           │
           ├── ASR/TTS 服务（老人手机直连）
           └── ChatModelProvider（既有 MLLM 链路）
```

FastAPI 中台不代理 ASR/TTS，不接触语音 API Key，不保存音频、识别文本或播报正文。
中台只继续接收老人端按小时聚合的 ASR/TTS 用量，并负责可靠保存家属通知。

现有 `AgentAsrProvider`、`AgentTtsProvider`、`ModelUsageRecorder` 和用量上报契约继续
复用。GUI Agent 使用相同 Provider 和全局开关，但使用 `VoiceFeature.GUI_AGENT` 请求上下文，
不共享聊天记忆或上下文实例。

## 3. 全局语音开关

### 3.1 存储与生效范围

- 新增 `VoiceInteractionSettingsStore`，使用 Preferences DataStore 保存
  `voice_interaction_enabled`；
- 新安装及升级默认关闭，避免安装后突然播报或申请麦克风；
- 设置只属于老人模式。切换到家属模式时有效状态强制为关闭，但不删除老人端偏好；
- 该偏好排除云备份和设备迁移，换机后必须由老人重新主动开启；
- 开关位于老人端齿轮设置页顶部，家属端模型配置和其他家属页面不出现该选项；
- 开启开关不立即申请麦克风权限，老人第一次按住说话时就地申请；新闻和通知 TTS
  不需要麦克风权限。

有效状态定义为：

```text
effective_enabled = stored_enabled && (elder_page_active || gui_task_active)
```

Provider 未配置或暂时离线不会偷偷关闭用户偏好，而是分别显示 ASR/TTS 当前不可用。
配置恢复后无需重新打开开关。

### 3.2 关闭动作

开关关闭必须立即执行：

1. 取消当前录音并立即释放内存音频缓冲区；
2. 取消尚未完成的 ASR；
3. 停止当前 TTS，清空待播队列；
4. 释放 AudioFocus；
5. 保留正在进行的 MLLM 文字生成，结果仍显示但不播报；
6. 后续收到的通知只写 Room，不生成待播任务。

## 4. 统一状态与接口

### 4.1 状态机

```text
Disabled
  └─ enable → Idle

Idle
  ├─ press → Recording
  ├─ enqueue TTS → Speaking
  └─ disable → Disabled

Recording
  ├─ release → Transcribing
  ├─ cancel/lifecycle stop → Idle
  └─ disable → Disabled

Transcribing
  ├─ success → WaitingModel
  ├─ failure → Idle + 可理解错误
  └─ disable → Disabled

WaitingModel
  ├─ complete → Speaking
  ├─ failure/cancel → Idle
  └─ disable → Disabled（MLLM 文字生成可继续）

Speaking
  ├─ completed → Idle / next queued item
  ├─ user press → stop TTS → Recording
  ├─ stop → Idle
  └─ disable → Disabled
```

UI 只订阅不可变 `VoiceInteractionState`，不得直接控制录音器或播放器。建议状态字段：

```kotlin
data class VoiceInteractionState(
    val enabled: Boolean,
    val asrAvailable: Boolean,
    val ttsAvailable: Boolean,
    val phase: VoicePhase,
    val activeFeature: VoiceFeature?,
    val recordingDurationMillis: Long,
    val message: String?,
)
```

### 4.2 请求上下文

现有仅区分 Main/GUI Agent 的 `VoiceSession` 不能表达通知与新闻。实施时扩展为不含正文的
请求上下文：

```kotlin
enum class VoiceFeature {
    CONVERSATION,
    FAMILY_NOTIFICATION,
    NEWS,
    GUI_AGENT,
}

data class VoiceRequestContext(
    val feature: VoiceFeature,
    val correlationId: String,
    val priority: VoicePriority,
)
```

`correlationId` 用于取消、去重和用量关联，不上传中台。Provider 不读取聊天上下文、长期
记忆或 Room，只处理调用方传入的最小音频/文本。

## 5. “和我说话”语音链路

### 5.1 按住说话

- 只有全局开关开启时显示可用的“按住说话”按钮；关闭时保留打字和系统手写输入；
- `ACTION_DOWN` 获取 AudioFocus、停止当前 TTS、检查/申请 `RECORD_AUDIO`，建立或复用
  WebSocket，发送 `run-task` 并等待 `task-started` 后开始录音和发送音频帧；
- `ACTION_UP` 停止采集，发送 `finish-task`，继续接收最终识别结果直至 `task-finished`；手指
  移出、系统返回、页面进入后台、来电或权限撤销时取消录音并释放缓冲区；
- WebSocket 尚在建立时收到的 `ACTION_UP` 也必须保留为待执行停止请求，不能因为 Provider
  仍处于 `IDLE/PROCESSING` 而丢弃；录音状态重组不得重建或取消当前按压手势；
- “和我说话”页面和活动 GUI 任务的无障碍覆盖层提供“按住说话”。GUI 按下期间执行许可
  暂停，松手识别成功后把文本加入当前 GuiRun 的短期历史并恢复；识别文本不持久化；
- 首版建议最大录音 60 秒、最短有效录音 300 毫秒，达到上限自动停止；
- 默认录音中间格式为单声道 PCM16/WAV、16 kHz；若厂商要求不同格式，由 Provider
  或编码适配器转换，UI 不感知厂商格式；
- 音频只以有上限的 PCM 帧缓冲存在于内存并及时写入 WebSocket 二进制通道，不创建
  `cacheDir` 临时文件，不进入 MediaStore、Room、日志、备份或中台；默认使用 PCM16、
  16 kHz、单声道，按约 100 ms/3200 字节分帧，避免保留整段音频和 Base64 副本；
- ASR 请求成功、失败、取消或页面生命周期终止后都立即清空并释放音频字节引用。若厂商
  只接受 multipart，也应由内存中的请求体直接发送，不能为了上传而落盘。
- 离开聊天页或银龄助手进入后台时，应用必须取消仍在启动、录音或识别中的 ASR，并停止
  TTS；`AudioRecord.stop()` 与 `release()` 覆盖正常完成、初始化异常和协程取消路径，避免
  系统麦克风使用标识在退出应用后继续存在。

### 5.2 ASR 到 MLLM

ASR 最终文本去除首尾空白并通过长度限制后，作为普通 User 消息显示在对话框右侧，随后
复用既有 `AgentChatCoordinator.streamTurn()`，不得建立第二套聊天历史或 Tool Registry。

- 空文本：提示“没有听清，请再说一次”，不调用 MLLM；
- Provider 返回低置信度：文本先进入可编辑输入框并提示老人确认，不自动执行；
- Provider 不返回置信度：允许进入普通聊天，但所有电话、通知、订单等风险 Tool 仍执行
  既有确定性确认策略，不能把“无置信度”视为高置信度；
- ASR 失败保留打字入口，不自动无限重试；
- 主动停止、页面退出、新播报打断旧播报产生的 `CancellationException` 属于内部控制流，
  必须静默处理；任何协程类名、异常消息、WebSocket 细节或堆栈不得显示给老人；
- 正在等待 MLLM 时首版不接受第二段录音，避免两轮上下文乱序。

### 5.3 MLLM 到 TTS

- 流式回复继续实时显示；只播报最终完成的 Assistant 正文；
- 不播报 reasoning、Tool 参数、Tool 原始结果、错误堆栈、Markdown 标记和上下文统计；
- 首版在 `AgentChatEvent.Completed` 后取得完整 Assistant 正文，只调用一次 TTS 并一次
  播放完整回答；不得对 `TextDelta` 调用 TTS，也不得按标点拆成多次请求；
- 若完整回答超过厂商单次字符上限，首版保留并显示全部文字，同时明确提示该回答无法播报，
  不静默拆成多次 TTS；后续只有在产品重新确认策略后才允许调整；
- 工具确认弹窗出现时不播报“已经完成”，只播报明确的确认提示；
- 用户取消生成时停止与该回答关联的待播任务；已经显示的部分文本保留；
- 用户在播报中再次按住说话属于 barge-in：立即停止 TTS 后开始新录音。

## 6. 家属新通知播报

### 6.1 触发点

“收到”定义为老人端通过 REST 取得命令并首次成功写入 Room，而不是收到 WebSocket
提示或服务端已经创建记录。WebSocket 只触发 REST 补拉，Room 仍是事实来源。

`ReminderRepository.saveRemoteCommand()` 实施时应返回 `Inserted / Duplicate`，只有：

- 类型为 `FAMILY_NOTIFICATION`；
- 本次确实新插入；
- 全局语音开关当时开启；

才创建播报任务。补拉到重复 `command_id`、重新打开提醒页、重新 ACK 均不得重复播报。

### 6.2 持久去重

Reminder Room 增加：

- `voice_announcement_state = NONE / PENDING / SPOKEN / FAILED / EXPIRED`；
- `voice_announced_at_epoch_millis`；
- 可选 `voice_attempt_count`。

先以事务写入提醒和 `PENDING`，播放完成后标记 `SPOKEN`。进程意外退出后，只恢复最近
10 分钟内的 `PENDING`；更早项目标记 `EXPIRED`，避免老人启动 App 后突然朗读大量旧消息。
开关关闭期间收到的通知直接记为 `NONE`，以后打开开关不追溯播报。

播报文本为：

```text
您收到一条来自{家属称呼}的通知。{通知内容}
```

播报内容设置合理字符上限并移除 URL 等不适合朗读的片段；屏幕上仍显示完整原文。

### 6.3 实时边界

无第三方移动推送时，App 被系统强制停止或进程被彻底终止后，无法保证服务端消息到达时
立即播报。老人端在线时可新增命令 WebSocket 提示并立即 REST 补拉；恢复前台时继续 REST
补偿。不能为了“实时”绕过 Android 后台限制或把 WebSocket 当成可靠记录。

## 7. 新闻播报

- 新闻文字获取和 2 小时暂存保持不变，页面仍展示前 15 条，TTS 不触发额外百度请求；
- 进入新闻页且全局开关开启时，只取榜单前 5 条，播报时读取老人手机当前本地日期时间，
  拼接为一段文本并只调用一次 TTS。固定模板为：
  `当前是X年X月X日X点X分，今天的新闻如下。第N条新闻的内容如下：{标题}。{摘要}`；中文
  序号使用“一”至“五”，移动版无摘要
  时使用：`第 N 条新闻的内容如下：{标题}`；
- 若前 5 条拼接文本超过厂商单次字符上限，应先按确定性上限缩短摘要，仍超限时只播标题，
  不能拆成多次 TTS 请求；
- 页面提供“播放/暂停、停止/重新播放”；同一次页面停留中，点击命中缓存的“刷新”不自动
  重播，老人主动点击“重新播放”才重新发起一次合成；
- 离开新闻页默认停止并清空新闻队列，避免老人已进入其他功能后继续长时间播报。

## 8. 播放与录音仲裁

应用内只允许一个录音会话和一个 TTS 播放器。建议优先级：

```text
用户按住说话 > 聊天回复 > 家属通知 > 新闻
```

规则：

- 用户按住说话始终停止任何 TTS；
- 家属通知不打断录音、ASR 或 MLLM 处理，排队等待；
- 家属通知可停止当前新闻播报并插队；新闻不自动续播，老人可主动重新播放；
- 聊天回复不与通知/新闻混播；
- 来电、闹钟或其他 App 获得永久 AudioFocus 时停止；短暂失焦时暂停，恢复焦点后由来源
  策略决定继续；
- 多条家属通知在短时间内到达时逐条播报，不合并正文，也不并发创建多个播放器；
- 关闭全局开关或离开老人模式拥有最高取消权。

## 9. Android 权限与后台约束

- Manifest 增加 `RECORD_AUDIO`，只在老人第一次按住说话时运行时申请；
- 权限拒绝后显示“可以继续打字，也可以到系统设置开启麦克风”，不得反复弹窗；
- 聊天前台短录音无需独立前台服务；
- 首版通知播报只保证在老人 App 进程存活并成功同步消息后执行；若未来要求 App 在后台
  长时间可靠播放，需要独立 `mediaPlayback` 前台服务及常驻播放通知，另行评审；
- 播放和录音必须使用 Android AudioFocus，不能用固定音量覆盖老人设置；
- 可选使用音频属性 `USAGE_ASSISTANCE_ACCESSIBILITY` 或 `USAGE_ASSISTANT`，最终依据真机
  对蓝牙、助听设备和系统音量分组测试选择。

## 10. 用量、隐私与安全

- ASR/TTS 用量只记录实际发起的调用次数，分别累计为 `ASR` 和 `TTS`；不记录音频时长、
  字符数，也不按聊天、家属通知、新闻等业务来源拆分用量；
- 为兼容既有用量表和中台协议，必须保留的 provider/model/feature 或扩展计量字段使用稳定
  默认值，音频时长和字符数固定为 0；家属端只展示 ASR/TTS 次数；
- 继续按小时聚合上传中台，家属端只看到用量汇总，不看到音频、识别文本、聊天回复或
  通知/新闻正文；
- ASR/TTS 共用的语音 API Key 只保存在老人设备 Android Keystore 加密存储，并与 MLLM
  Key 使用独立凭证槽位，禁止通过家属端或中台下发明文或密文；
- 日志不得记录 Authorization、音频字节、完整 transcript、通知正文和 TTS 正文；
- ASR 文本与手工文字使用同一 Tool/Policy/确认流程，语音输入不能绕过电话、订单、医疗
  或紧急操作确认。

## 11. 失败与降级

| 场景 | 老人可见行为 |
|---|---|
| 麦克风权限拒绝 | 保留打字/手写，提供系统设置说明 |
| 录音太短或无声音 | “没有听清，请按住按钮再说一次” |
| ASR 超时/断网 | 保留页面与文字历史，可重新录音或打字 |
| MLLM 失败 | 显示既有错误和重试；不产生 TTS 成功提示 |
| TTS 失败 | 文字继续显示，不重跑 MLLM；允许重试播报 |
| 新闻 TTS 失败 | 前 15 条文字继续显示，允许重新播报，不重新抓取新闻 |
| 通知 TTS 失败 | Room 保留通知；有限重试后标记 FAILED，禁止无限循环 |
| AudioFocus 被抢占 | 暂停或停止，并显示简短状态，不强抢系统声音 |
| 全局开关关闭 | 所有语音立即停止，文字功能不受影响 |

Android 系统 TTS 可作为后续显式降级 Provider，但不能在云端 TTS 失败时未经说明突然切换
声音。是否启用、语言包可用性和隐私提示需单独验收。

## 12. 实施顺序

1. 全局开关、DataStore、角色隔离和设置 UI；
2. `AudioRecorder`、麦克风权限、有上限的内存缓冲区和假 ASR Provider；
3. 聊天按住说话接入既有文字发送路径；
4. 真正 ASR Provider、错误映射和用量记录；
5. `VoiceInteractionCoordinator`、AudioFocus、假 TTS Provider；
6. 聊天完整回答 TTS；
7. Reminder Room 播报状态迁移、首次插入事件和通知播报；
8. 新闻前 5 条单次合成和页面控制；
9. 真正 TTS Provider、完整文本单次合成、取消和调用次数记录；
10. API 29、Android 12+、蓝牙耳机、来电打断、断网和进程恢复测试。

每一步保持打字、提醒文字和新闻文字可独立使用，不能把语音 Provider 失败升级为整个功能
不可用。

## 13. 接入前需要确认的模型信息

### ASR

- 厂商/服务名、HTTP 或 WebSocket 协议、Base URL、Endpoint 和模型名；
- 鉴权头格式，是否与 MLLM 共用 Key；
- 请求是 multipart、原始音频还是 JSON base64；
- 支持的音频容器、编码、采样率、声道、最大时长和最大文件；
- 返回 transcript、confidence 和语言的字段；
- 错误响应、超时建议、限流和并发限制；
- 是否支持取消、流式临时结果以及是否需要服务端 VAD。

### TTS

- 厂商/服务名、协议、Base URL、Endpoint 和模型名；
- 鉴权方式，是否与 ASR/MLLM 共用 Key；
- voice ID、中文普通话支持、语速/音调/音量参数及默认值；
- 单次最大字符数、SSML 支持情况；
- 返回格式（PCM/WAV/MP3/Opus）、采样率、流式分块协议和响应 Content-Type；
- 错误响应、超时、限流和并发限制。

### 远程配置与凭证

- 本次选择阿里云百炼 Qwen-Audio WebSocket，ASR/TTS 共用一个 `wss://` 接口地址和一把
  语音 API Key。语音 Key 使用与现有 MLLM Key 相同的 Keystore + AES-GCM 存储方案，但
  使用独立凭证槽位；家属端只配置并经中台下发 WebSocket 地址、ASR/TTS 模型名、TTS
  音色及非敏感音频参数；
- API Key 不得在家属端填写或经过中台。ASR/TTS 握手时从老人设备语音凭证槽位短暂解密
  同一把语音 Key，并设置 `Authorization: Bearer <API Key>`；除非以后明确配置为同一值，
  不假定语音 Key 与 MLLM Key 相同；
- 还需确认准确 endpoint、HTTP 方法、鉴权头、请求 Content-Type、ASR multipart 字段名、
  transcript 响应字段、TTS 请求字段、返回音频 MIME、最大音频时长/字节、最大文本字符数、
  超时、限流和错误响应结构；
- Release 只允许 `wss://`。开发阶段如使用明文 WebSocket，必须继续受 Debug 构建和明确的
  Network Security Config 限制。

当前官方协议固定使用 `run-task → task-started → 二进制/continue-task → finish-task →
task-finished`。ASR 读取 `result-generated.payload.output.sentence`，只把
`sentence_end=true` 的最终文本提交聊天；TTS 等完整回答后发送一次 `continue-task`，随后
立即发送 `finish-task`，通过 binary 帧在内存中播放。服务端内部自动分句产生音频帧不计为
客户端多次 TTS 调用，整项任务仍只记录一次 TTS 调用。

## 14. 验收标准

1. 家属模式看不到语音开关且不申请麦克风；
2. 开关关闭时聊天、通知和新闻均不录音、不调用 ASR/TTS；
3. 开关打开后按住说话可完成 ASR→MLLM→TTS，文字始终同步显示；
4. 松手、移出、返回、来电、权限撤销和关闭开关都能正确释放内存音频，应用私有目录、
   MediaStore 和 Room 均不产生录音文件；
5. 同一 `command_id` 的家属通知最多成功播报一次，补拉和重启不重复；
6. 新闻使用现有 2 小时数据暂存，页面展示前 15 条，只把前 5 条拼接后单次播报，且不因
   TTS 重复抓取百度；
7. 录音与多个 TTS 来源不并发，按优先级可预测地打断/排队；
8. ASR/TTS 失败不影响打字、文字提醒、新闻列表和 MLLM 文字结果；
9. 用量只累计 ASR/TTS 实际调用次数，不按业务来源拆分，且不上传正文或音频；
10. API 29 真机以及 Android 12+ 启动、前后台、蓝牙和 AudioFocus 场景通过验证。
