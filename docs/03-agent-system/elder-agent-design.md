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

### 5.1 GUI Agent 核心任务边界

GUI Agent 是主聊天 Agent 可调用的异步 Tool，但不在主聊天模型轮次内执行完整 GUI
循环。Tool 只负责创建、查询或控制任务并立即返回 `todo_id`，独立的 `GuiTaskManager`
在进程内运行 GUI Run，因此 GUI 操作期间主聊天 Agent 仍可继续对话。

当前 Android 已建立以下核心代码边界，并已接入美团、微信、淘宝真机启动、Accessibility
观察/动作以及截图 MLLM 单步 ReAct：

- `GuiTodo` 只在 Room 保存老人可理解的任务内容、状态、完整失败次数、时间和家属协助
  事件 ID，不保存候选商品、地址、页面节点、截图、ReAct 历史、订单号或支付信息；
- `GuiRun`、页面状态和动作循环只存在于当前进程内。新进程发现遗留
  `RUNNING/PAUSED` Todo 时只转换为 `INTERRUPTED` 并提醒重新开始，不恢复旧动作；
- 同一设备只允许一个非终态 GUI 任务；第二个任务返回忙碌状态；
- 第一次完整 GuiRun 耗尽自身 ReAct/重规划预算后先告知老人，再自动创建第二次完整
  GuiRun；第二次运行先回到桌面并用 `NEW_TASK + CLEAR_TASK + CLEAR_TOP` 清理目标 App
  的旧任务栈，然后重新打开入口页，不在第一次失败页面上续跑。这里重置的是 Android
  可见任务栈，不使用系统权限强制停止第三方 App 进程；暂停、取消、等待语音、离开目标
  App 和进程中断不计为完整失败；
- 第二次完整 GuiRun 失败后才调用 `GuiFailureEscalationSink`。Android 组合根已将该出口接到
  主聊天上报 Tool 共用的 `FamilySituationReporter`，以 GUI Todo UUID 作为幂等键创建
  `GUI_ORDER_ASSISTANCE_REQUIRED / EMERGENCY`；外卖和网购分别使用不包含订单隐私的固定
  摘要。该链路由任务管理器确定性触发，不依赖主聊天模型再次决定是否调用 Tool；
- 第二次完整失败会先把 Todo 和悬浮控制条置为终态，经桌面退出目标 App 前台，再唤起银龄
  助手并由进程内一次性导航请求回到聊天页面。普通 Android 应用不能强制停止其他 App
  进程，因此这里只退出其可见页面，不申请系统级 `force-stop` 权限；
- GUI 终态通过进程内 `GuiTaskChatFeedbackBus` 追加一条确定性聊天消息，不再为结果反馈调用
  模型：完成显示“已完成任务。”；第二次失败且中台已保存家属事件显示“任务失败，已通知
  家人。”；上报未成功时只能显示“任务失败，请稍后再试。”，禁止谎称家属已收到；
- Executor 在观察、模型规划和每个真实动作前调用 `GuiRunControl.awaitRunning()`，顶部
  悬浮条、人工接管、离开目标 App 和主 Agent 销毁共用同一停止门。

### 5.2 GUI 截图与坐标空间

GUI Agent 每个真实动作只允许使用一张新截图进行规划。动作提案必须携带该截图的
`frame_id`；截图后发生旋转、窗口切换、人工触摸、目标 App 离开前台或产生更新截图时，
旧 `frame_id` 立即失效，禁止继续执行旧坐标。

截图不能不经处理直接发送给 MLLM。核心层通过可配置 `ScreenshotPixelBudget` 同时限制
最长边和总像素数，只缩小、不放大，并记录：

- 原始截图像素尺寸；
- 目标窗口和裁剪区域；
- 实际上传图像尺寸；
- 裁剪与等比 resize 的精确比例；
- Android 截图缓冲区到屏幕像素的仿射变换；
- display ID、旋转角度、截图时间和 `frame_id`。

当前核心默认不做隐式 letterbox。如果具体模型 Provider 必须补边，padding 必须成为显式
仿射变换的一部分。Android 平台实现不得假设截图缓冲区尺寸等于手势屏幕尺寸：
MediaProjection 的虚拟显示、厂商实现、旋转、多窗口和系统缩放都可能改变对应关系。
Accessibility 整屏截图和 `dispatchGesture` 使用默认显示器物理像素；不得使用可能扣除
状态栏或导航栏的 `resources.displayMetrics` 作为手势尺寸。当前实现通过
`Display.getRealMetrics` 取得物理显示尺寸，再显式建立截图到手势屏幕的比例；两者相同时
变换应为 Identity。

模型输出优先使用相对于“实际上传图像”的 `0..1000` 归一化坐标，并回显
`frame_id`。这样模型内部自行缩放图像时，客户端无需猜测其内部像素尺寸。若 Provider
只能返回上传图像像素坐标，响应必须同时回显它认为的输入宽高；尺寸不一致时拒绝动作。

坐标反向映射固定为：

```text
模型归一化/上传图像坐标
→ 上传图像像素
→ 逆 resize / 逆裁剪
→ 原始截图像素
→ Android 截图到屏幕仿射变换
→ 手势屏幕像素
```

映射结果超出上传图像、截图裁剪区、当前目标窗口或屏幕时一律拒绝。节点树存在可操作
节点时仍优先执行节点 Action；MLLM 坐标用于节点定位/交叉验证或节点不可用时的受控
降级，不能绕过 Policy Engine。

Android 兼容实现需要区分：

- 当前 API 30+ 使用 Accessibility 整屏截图：先用无障碍根节点包名确认目标 App 在前台，
  捕获前把顶部控制层设为不可见，等待系统合成后截图，立即恢复控制层，再保留完整显示区域
  并 resize；不能按同包 active window 裁剪，因为部分 App 会把首页暴露为多个局部窗口；
  MLLM 推理期间控制层保持可用；
- API 34+ 后续可切换到指定 Accessibility 窗口截图，减少隐藏控制层的时间，但仍需保持
  相同坐标几何和动作安全门；
- API 29 没有 Accessibility 截图返回接口，需要单独设计经老人明确授权的
  MediaProjection 兼容路径。MediaProjection 选择的虚拟显示尺寸、系统等比缩放和居中
  偏移都必须写入 `captureToScreen`，不得套用 API 30+ 的 Identity 假设。

原始截图和上传图像只在单步内存中存在，不进入 Room、中台、日志、聊天历史或长期记忆。
支付页、密码、验证码、生物识别、`FLAG_SECURE` 或无法可靠过滤的敏感页面不截图、不上传，
直接停止自动操作并交给老人。

主 Agent 面向 GUI Agent 的逻辑 Tool 使用 `START/STATUS/PAUSE/RESUME/CANCEL` 操作。
当前 Tool 已注册到正式聊天 Tool Registry。主 Agent 对打开或操作美团、微信、淘宝的单一
及复合请求使用 `START`，Tool 创建 Todo 后立即返回，不阻塞聊天。`STARTED` 不代表任何
页面结果，主 Agent 不得扩写为已经打开、点击或下单。纯打开请求只有在无障碍观察确认目标
包处于前台后才完成；其他请求由 `AccessibilityGuiRunExecutor` 最多执行 20 个“观察—规划—单步
动作—重新观察”步骤。无法识别、未安装、无障碍未启用或 Android 10 暂无截图能力属于
`UNAVAILABLE`，不计为完整运行失败，也不触发第二次尝试或家属通知。

对包含受支持 App 名称和明确操作动词的指令，`GuiMainAgentToolRouter` 在调用主聊天模型前
确定性执行 `gui_agent START`，避免主模型先询问商家、口味等尚未从页面观察到的信息。仅询问
“怎么打开/如何操作”等使用方法时不自动执行。选购所需信息由 GUI Agent 打开目标 App、取得
当前截图后，只针对页面上的实际阻塞项逐一询问老人。

非纯打开任务的 `complete` 受执行器确定性约束：至少已有一个成功的真实设备动作，并且动作
后已经取得一张新截图。模型在任何动作前声称完成、付款或要求与当前画面无关的地址/口味等
信息会被拒绝并写入短期步骤反馈。导航类目标严格保持原范围，例如“打开美团并点外卖”只
导航并验证外卖页，不自动扩展成选餐或下单。

`OpenAiGuiVisionPlanner` 复用老人配置的 OpenAI 兼容连接与本地 API Key，但使用独立 GUI
system prompt、仅限当前 GuiRun 的短期步骤历史和 `feature=gui_agent` 用量归属。每次请求
只发送 resize 后的当前截图、最多 120 个当前无障碍节点和最近 8 个步骤摘要；不复用聊天
Agent 的消息上下文或长期记忆。当前共享 Tool 仅开放 `get_current_time`。

顶部控制条同时提供应用内 Compose 版本和经用户在系统无障碍设置中明确启用后的
`TYPE_ACCESSIBILITY_OVERLAY` 版本，已实现以下交互：

- 目标 App 正常运行时显示“暂停”；
- 收到人工操作暂停事件后显示“继续”；
- 从目标 App 返回银龄助手时暂停并显示“返回任务”，点击后重新打开目标 App 并恢复暂停前
  的 `WAITING_*` 或运行阶段；
- 始终提供“取消”，并在 UI 层进行二次确认；
- GUI 控制条首版不显示“按住说话”占位按钮；当前 ASR 入口仅属于主聊天页。后续真正接入
  GUI Agent 语音补充输入时再恢复该入口，并补齐暂停动作和 ASR 结果路由。

`GuiAccessibilityControlService` 同时负责系统覆盖层、目标窗口节点快照、API 30+ 截图、
节点点击、普通文本输入、节点滚动和坐标手势降级。节点路径和 `frame_id` 只在单帧有效；
窗口、点击、滚动、触摸开始或文字变化会使旧帧失效。执行动作前保留短事件抑制，成功动作
后再提供 5 秒页面过渡宽限，避免把 Agent 点击产生的延迟事件、页面加载和自动滚动误判为
人工操作；人工接管使用明确触摸开始或宽限期外的点击信号。目标 App 内页面/Activity 跳转
保持运行；非目标窗口事件经过 1.2 秒防抖并复核当前根节点包名，只有目标包持续离开前台才
暂停。启动阶段只有看见过目标窗口后才进行离开判断，避免刚启动即误暂停。
控制层只有在任务与目标 App 启动会话匹配后才显示，不会在目标包尚未解析或启动失败前先行
悬浮。

`click_node` 执行时先验证原树路径仍对应截图中的同一控件，再调用节点或可点击父节点的
`ACTION_CLICK`。动态页面导致路径变化时，只允许使用 `viewId`、文字或内容描述的精确匹配
结合当前位置距离重新解析当前节点；匹配成功但节点拒绝 `ACTION_CLICK` 时，才使用该实时
节点中心执行一次 `dispatchGesture`。完全找不到可信同语义节点时必须重新观察，禁止按旧截图
盲点。三条路径都复用密码、支付和提交订单确定性策略，并通过 `device_click` Debug 事件区分
`exact_node_action`、`semantic_rematch`、`coordinate_fallback` 和 `node_unresolved`。

为比较 Grounding 策略，Debug 构建提供进程内 `COORDINATE_ONLY` 实验开关。开启后 Planner
仅接收 resize 后截图、任务、当前步骤和短期历史，不接收节点列表，协议拒绝 `click_node`、
带 `node_id` 的输入和滚动；点击只能使用上传图像 `0..1000` 归一化坐标。普通文本输入必须
先通过坐标点击聚焦输入框，再执行 `input_text_focused`。该模式只改变模型目标定位，不关闭
端侧节点采集、敏感页面检测或支付策略；设置不持久化，Release 始终使用
`HYBRID_NODE_FIRST`。

动作协议要求 `click_point.x` 和 `click_point.y` 分别为单个 JSON 数字。为避免部分 MLLM
偶发返回 `"x":[x,y]`、`"point":[x,y]` 或 `"coordinates":[x,y]` 导致整步丢失，解析器
对这三种二元数组做有界兼容并记录 `mllm/coordinate_json_normalized`；数组长度错误、非有限
数字、越出 `0..1000` 或 JSON 截断仍拒绝执行并重新观察，不能凭残缺响应猜测坐标。

Debug 追踪必须记录 `accessibility/pause_requested`、`task_manager/task_paused` 和
`task_manager/task_resumed`，用于区分真正人工接管、目标 App 离开和页面过渡误判。

为便于真机定位链路断点，Debug 构建通过 `GuiDebugTrace` 暂存最近 100 条结构化事件，包括
`main_agent_tool/call`、`launch_intent_sent`、`retry_app_reset_started`、
`retry_app_relaunched`、`launch_observation`、`screen/captured`、
`mllm/request`、`mllm/raw_response`、`react/planned_action` 和设备动作结果。原始模型 JSON
最多保留 4000 字符，仅存在当前进程内；截图仅记录宽高和字节数，不记录图像数据。Release
构建禁用采集，任何构建都不把该追踪写入日志文件、Room、中台、聊天记忆或用量上下文。
第二次完整失败还记录 `terminal_cleanup_started/failed` 与
`family_escalation_started/succeeded/failed`，用于区分返回聊天页面失败和中台事件上报失败。

订单提交前必须展示摘要，老人本人在悬浮条点击“确认并继续”后才产生一次性的
`ORDER_SUBMISSION` 授权；授权只供下一次真实动作使用。“去支付/立即支付/确认支付”等
动作永不授权给 Agent。检测到收银台、支付密码、验证码或生物识别页面时，在截图上传前
转为 `WAITING_MANUAL_PAYMENT`，GUI Agent 停止自动操作并由老人本人处理。

## 6. Memory

### 会话记忆

保留当前聊天消息，受 Token 预算限制。

### 短期记忆

保存数小时到数天的任务和近期上下文，例如订单在配送、明天复诊、今天已提醒两次。

### 长期结构化记忆

仅保存可复用且经过确认的信息：称呼、语言、家属关系、饮食偏好、新闻偏好、常用地址和预算偏好。

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
