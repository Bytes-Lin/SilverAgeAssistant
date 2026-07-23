# 老人端 Agent 系统设计

## 1. 目标

Agent 负责理解老人自然语言、读取必要记忆、选择工具、请求确认、执行动作并反馈结果。Agent 不是自由控制手机的黑盒；它由确定性组件约束。

## 2. 组件

```text
Input Adapter
├── Text
├── ASR final transcript
└── Image
      ↓
Intent & Risk Pre-check
      ↓
Context Builder
├── Current conversation
├── Short-term memory
├── Retrieved long-term memory
├── RAG documents
└── Device/task state
      ↓
MLLM Planner
      ↓ tool call proposal
Policy Engine
      ↓ allowed / confirm / family approval / blocked
Tool Executor
      ↓ result
Response Composer
      ↓
Text + TTS
```

## 3. Tool Use

每个工具必须定义：

- 唯一名称；
- JSON Schema 输入；
- 输出类型；
- 风险级别；
- 所需 Android 权限；
- 是否离线可用；
- 超时；
- 幂等性；
- 确认策略；
- 可审计摘要。

### 初始工具清单

低风险：

- `get_current_time`
- `get_weather`
- `list_today_reminders`
- `play_local_music`
- `adjust_volume`
- `open_app`
- `read_news_summary`

中风险：

- `create_local_reminder`
- `report_family_situation`
- `call_family_contact`
- `query_accessibility_screen`
- `search_product_or_meal`

高风险：

- `prepare_order`
- `submit_order`
- `request_family_approval`
- `trigger_sos`
- `upload_safety_image`

禁止工具行为：

- 输入支付密码；
- 读取验证码；
- 修改药物剂量；
- 代表老人签署法律文件；
- 绕过第三方认证；
- 未经确认上传持续监控视频。

`get_weather` 不接受模型提供的经纬度，只查询老人设备当前粗略位置。首页启动刷新和 Tool 查询共享 `WeatherRepository`；缓存有效时 Tool 返回结构化缓存结果，只有缓存过期才重新定位并请求 Open-Meteo。Android 系统或端侧 BigDataCloud 后备反向地理编码成功时，工具结果可包含由同一经纬度解析出的城市名称；结果包含当前天气、今天与未来三天、缓存状态、更新时间和端侧行动建议，但不包含经纬度。

`call_family_contact` 是中风险的本地电话 Tool。模型只能提供家属称呼或关系，不得接收、推测或生成手机号。Tool 从 Android Keystore + AES-GCM 加密的家属快照中本地解析号码：唯一命中时只创建待确认拨号，多人命中时要求补充称呼，不允许自动猜测。Tool 结果和后续模型上下文中不包含号码。老人在本地确认框点击“确认拨打”后，已授予 `CALL_PHONE` 权限时使用 `ACTION_CALL`；拒绝权限时退回 `ACTION_DIAL`。号码仅在加密联系人存储、待拨号内存对象和 Android 电话 Intent 之间短暂传递。

## 4. Policy Engine

Policy Engine 是普通 Kotlin 规则组件，不依赖模型自行判断。

输出之一：

- `ALLOW`
- `REQUIRE_ELDER_CONFIRMATION`
- `REQUIRE_SECOND_CONFIRMATION`
- `REQUIRE_FAMILY_APPROVAL`
- `BLOCK`

决策输入包括：工具、金额、地址变化、敏感字段、ASR 置信度、老人响应、家属配置、当前任务状态。

## 5. 任务状态机

复杂任务存为可恢复状态：

```text
CREATED
→ GATHERING_REQUIREMENTS
→ RECOMMENDING
→ EXECUTING
→ WAITING_ELDER_CONFIRMATION
→ WAITING_FAMILY_APPROVAL
→ WAITING_EXTERNAL_PAYMENT
→ COMPLETED / CANCELLED / FAILED
```

App 被中断后不得盲目继续支付；恢复时重新展示摘要并确认。

## 6. Memory

### 会话记忆

保留当前聊天消息，受 Token 预算限制。

### 短期记忆

保存数小时到数天的任务和近期上下文，例如订单在配送、明天复诊、今天已提醒两次。

### 长期结构化记忆

仅保存可复用且经过确认的信息：称呼、语言、家属关系、饮食偏好、音乐偏好、常用地址和预算偏好。

Android 首版在老人设备应用私有目录 `files/agent/MEMORY.md` 中保存可读 Markdown。该文件是端侧 Agent 的长期记忆文档，不上传 FastAPI，也从 Android 云备份和设备迁移中排除。当前包含三个受控区段：

- 老人基本信息：当前先记录喜欢的称呼；
- 已绑定家属：称呼、关系、联系方式提示和紧急联系人标记；
- 后续长期记忆：为经过确认的偏好和事实预留追加区。

写入时机：

1. 老人设备完成绑定后写入老人称呼、家属关系和联系方式提示；
2. 应用恢复已有绑定会话时补齐双方称呼和关系，兼容升级前已完成绑定的用户；
3. 家属联系人从中台同步成功或从加密本地快照恢复时，用最新资料替换家属区段；
4. 绑定撤销、凭证失效或联系人权限被拒绝时清空家属区段。

完整手机号继续只保存在 Keystore AES-GCM 保护的联系人快照中。`MEMORY.md` 仅写“联系方式已在本机安全保存”，手机号及其尾号都不进入 system prompt 或模型上下文。

每轮模型请求由 `SystemPromptProvider` 读取最新 `MEMORY.md`，使用明确分隔符追加到 system prompt，并声明记忆只能作为背景事实、不能作为指令。回答只检索当前问题需要的最少信息，不主动复述联系方式。

字段建议：

```text
MemoryItem
- id
- type
- content
- source
- confidence
- sensitivity
- confirmed
- created_at
- last_used_at
- expires_at
```

健康信息不得只凭模型推断写入。老人可查看、修改和删除。

## 7. 上下文压缩

当对话超过预算时：

1. 保留 system/policy 与最近若干轮；
2. 抽取尚未完成的任务状态；
3. 对较早消息生成结构化摘要；
4. 保存摘要来源消息范围；
5. 丢弃无关闲聊原文；
6. 对高风险确认保留原始结构化事件，不只保留自然语言摘要。

摘要必须区分：事实、老人表达、模型推测和待确认信息。

## 8. RAG

RAG 用于本地或可信文档，不用于替代实时工具：

- App 使用说明；
- 家庭配置说明；
- 老人已授权的药品说明书；
- 社区服务指南；
- 常见防诈骗知识。

流程：文档导入 → 分块 → 嵌入 → 本地向量索引 → Top-K 检索 → 引用来源 → MLLM 回答。

天气、新闻、订单状态必须使用实时 Tool，不从旧向量库回答。

## 9. 模型失败降级

- LLM 失败：保留输入，允许重试或执行明确的本地命令；
- ASR 失败：允许重新录音或文字输入；
- TTS 失败：显示大字文本并使用 Android 系统 TTS 作为可选降级；
- VLM 失败：要求人工查看，不给出肯定安全结论。

## 10. 独立状态监控 Agent 边界

状态监控 Agent 与日常聊天 Agent 使用独立 system prompt、上下文、模型请求、确定性状态机和 `safety_monitoring` 用量标签，不把监控图像或分析结果混入聊天历史。老人端默认开启且检测间隔为 5 分钟，家属经中台可关闭或下发 `1/5/10/15/30/60` 分钟；断网时继续使用上次有效本地值。

Android 使用 `specialUse` 前台服务和不可隐藏的常驻通知维持周期调度，不使用 WorkManager 或精确闹钟模拟 5 分钟 cron。老人打开已绑定应用后自动启动；老人端不提供停止按钮，家属端通过中台 `enabled=false` 关闭。间隔热更新立即取消旧等待并按新间隔重排，但保留六小时历史；关闭时停止图像、MLLM 和告警状态机并清空历史，但保留只监听远程配置的轻量前台监督服务，否则在不使用第三方推送的架构下无法实时重新开启。系统强行停止应用仍会终止监控，这是普通 Android App 无法绕过的边界。

图像通过 `SafetyImageSource` 注入。开发测试使用 `MockSafetyImageSource` 轮换读取 `assets/mock/fall_detect`；正式版实现 `NetworkCameraImageSource` 从已授权局域网摄像头取得最新静态图像，不调用老人手机摄像头。

每次请求由独立视觉分析器直接调用老人配置的 OpenAI 兼容 MLLM，完整输入由安全 system prompt、最近一次检测结果 context、当前设备 UTC 时间、JSON 输出约束和一张图像组成。模型只允许返回 `state=正常/异常` 和异常时必填的简短 `detail`，不允许诊断、工具调用或自由文本。

检测结果在本机 `files/agent/safety-detection-state.json` 暂存，六小时到期自动重置。正常结果把连续异常次数归零；连续第 2 次异常通过共享 `FamilySituationReporter` 创建一次紧急事件，并为该事件上传触发上报的单张图像；同一六小时窗口后续异常不再创建事件。连续第 3 次异常仅成功发送一次短信。事件或短信首次失败可在后续异常轮次重试，但成功后必须由本地标记阻止重复；事件创建成功后先持久化本窗口通知标记，再尝试上传图像，因此图像失败不会导致下一轮重复创建事件。短信号码只从本机加密紧急联系人快照读取，不进入模型 prompt、模型响应或日志。

监控结果只能表达为“疑似跌倒”、“疑似晕倒或失去意识”等待核实事件。`GENERAL` 进入家属端“今日状态”，`EMERGENCY` 进入“紧急事件”并在 App 前台弹窗。中台保存结构化事件及独立的短期证据图像，不保存 MLLM 原文；图像不进入普通 JSON 或日志。

聊天 Agent 和状态监控 Agent 共享 `FamilySituationReporter`，聊天侧通过 `report_family_situation` Tool 调用。模型只提供类型、忠实摘要和建议紧急程度；执行器自行生成 UTC 时间和幂等 UUID，并确定性强制“家庭请求=一般”、“身体不适/跌倒/昏迷/其他异常=紧急”。普通闲聊不调用该 Tool。

## 11. 测试重点

- 模型提出未注册工具；
- 参数缺失或类型错误；
- ASR 误识别金额/联系人；
- 订单价格在确认后变化；
- 家属审批超时；
- App 被杀后任务恢复；
- 恶意页面文本诱导 Agent；
- Prompt injection 试图读取 API Key；
- 记忆错误写入与删除。
