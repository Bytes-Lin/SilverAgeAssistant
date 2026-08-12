# Agent Tools 与能力清单

> 代码核对日期：2026-08-12。本文件只记录当前 Android 装配代码中的真实能力，不把规划中
> 但尚未注册的 Tool 计为已实现。装配入口为 `MainActivity.kt` 和
> `SafetyMonitoringService.kt`。

## 1. 概念边界

- **Agent Tool**：实现 `AgentTool`，具有强类型 JSON Schema、风险等级、执行策略，并注册到
  某个 `AgentToolRegistry`，模型可以通过 Tool Call 请求使用；
- **确定性 Tool 路由**：在主聊天模型调用前，根据明确用户语句调用已注册 Tool，不是新增
  Tool；当前用于 GUI 操作和今日提醒查询；
- **GUI Action**：GUI Planner 返回的单步动作，由端侧执行器和安全门校验，不属于共享
  `AgentToolRegistry`；
- **内部能力**：状态监控 Agent 按固定状态机调用的接口，MLLM 不能自由选择或传参调用。

## 2. 主聊天 Agent

主聊天 Agent 当前 Registry 包含：

| Tool | 风险/策略 | 作用与边界 |
|---|---|---|
| `get_current_time` | 低风险，立即执行 | 返回老人设备当前日期、星期、时间和时区；无参数；与 GUI Agent 共享同一无状态实例。 |
| `get_weather` | 低风险，立即执行 | 通过设备粗略位置和共享 `WeatherRepository` 返回实时天气、今天及未来三天；不把经纬度交给模型。 |
| `list_today_reminders` | 低风险，立即执行 | 只读老人端 Room 提醒快照，返回 `pending/snoozed/completed`；“今天还有什么没做”等问法由确定性路由调用并只展示未确认项。不能修改提醒或代替老人确认完成。 |
| `call_family_contact` | 中风险，准备后本地确认 | 按家属称呼或关系读取本机加密联系人，唯一命中后创建待确认拨号；手机号不进入模型上下文，老人确认后才启动电话。 |
| `report_family_situation` | 中风险，本地策略后立即执行 | 向中台上报身体不适、家庭请求、疑似跌倒/昏迷或其他异常；执行器生成时间和幂等 ID并确定性修正严重级别。GUI 失败事件不能由模型伪造。只有中台 Reporter 可用时才注册。 |
| `gui_agent` | 中风险，本地策略后立即执行 | 以 `START/STATUS/PAUSE/RESUME/CANCEL` 异步创建、查询和控制单个 GUI 任务；`STARTED` 只表示创建成功，不表示 App 或页面操作完成。 |

当前确定性路由：

- `GuiMainAgentToolRouter`：包含美团、微信或淘宝以及明确操作动词的请求，在主模型前执行
  `gui_agent START`；方法咨询、否定表达不自动操作；
- `TodayRemindersMainAgentToolRouter`：明确查询今日提醒或未完成事项时，在主模型前执行
  `list_today_reminders` 并生成适老化结果。

## 3. GUI Agent

GUI Agent 使用独立 Registry。当前唯一共享 Agent Tool 为：

| Tool | 作用 |
|---|---|
| `get_current_time` | 为订餐、购物等当前 GuiRun 提供设备本地日期、时间和时区。 |

天气、提醒、联系人、家属上报及 `gui_agent` 本身不注入 GUI Agent。两个 Agent 只共享时间
Tool 实例和底层 ASR/TTS Provider，不共享 Registry、system prompt、聊天上下文、长期记忆或
模型用量归属。

GUI Planner 可返回以下单步 Action：

| Action | 作用与限制 |
|---|---|
| `click_node` | 点击当前 `frame_id` 对应的无障碍节点；节点失效时可语义重匹配或在受控条件下点击实时节点中心。 |
| `click_point` | 点击当前截图中的 `0..1000` 归一化坐标；端侧反向映射到物理屏幕。 |
| `input_text` | 向当前帧的指定普通输入节点输入文本。 |
| `input_text_focused` | 纯坐标实验中，向前一步已经取得焦点的普通输入框输入文本。 |
| `scroll` | 在当前页面向前或向后滚动，可指定节点。 |
| `back` | 执行系统返回。 |
| `wait` | 等待 250—3000 毫秒后重新观察页面。 |
| `ask_elder` | 当前页面存在必要歧义或订单提交前，暂停并向老人询问一个问题。 |
| `ready_for_payment` | 停止自动化并把付款交给老人本人。 |
| `use_tool` | 调用 GUI Registry 中已注册的共享 Tool；当前实际只能调用 `get_current_time`。 |
| `complete` / `fail` | 基于当前新截图返回完成证据，或如实结束本次 GuiRun。 |

所有设备动作都受当前帧、安全页面、人工接管、订单确认和运行许可校验。支付密码、短信验证
码和生物识别不能被读取或填写。

## 4. 独立状态监控 Agent

状态监控 Agent **没有提供给 MLLM 的 Tool Registry**。每次检测按固定顺序执行
“取图 → 分析 → 保存状态 → 阈值告警”，使用以下内部能力：

| 内部能力 | 作用 |
|---|---|
| `SafetyImageSource` | 获取一张最新静态图像；当前装配为 `MockSafetyImageSource`，真实 `NetworkCameraImageSource` 尚未实现。 |
| `SafetyVisionAnalyzer` | 调用独立 OpenAI 兼容 MLLM，只返回严格的正常/异常 JSON 和简短说明。 |
| `SafetyDetectionStateRepository` | 保存六小时窗口、最近结果、连续异常次数和已通知标记。 |
| `FamilySituationReporter` | 连续两次异常后向中台创建一次紧急事件。概念上与聊天/GUI 复用同一 Reporter 类型，但各运行组件分别持有实例。 |
| `attachEvidence` | 为已创建的异常事件上传触发上报的单张证据图像。 |
| `EmergencySmsSender` | 连续三次异常后向本机加密快照中的紧急联系人发送一次短信。 |

状态监控 MLLM不能决定是否通知家属、发送短信或上传图像；阈值和去重完全由 Kotlin 状态机
决定。

## 5. 尚未注册的规划能力

以下能力仍是规划项，不得在产品说明中描述为当前可调用 Tool：

- 创建、修改、取消本地提醒；
- 新闻摘要 Tool；
- 独立商品/餐品搜索、订单摘要和订单提交 Tool；
- 金额阈值及家属审批 Tool；
- SOS Tool；
- RAG 检索 Tool；
- 真实局域网摄像头 Tool。

当前 `DefaultSystemPromptProvider` 的能力介绍仍包含“设置、修改和取消提醒”“查询热点新闻”等
泛化表述，但这些 Tool 尚未注册。实际可调用范围必须以 Registry 为准；后续应同步收紧 system
prompt，避免模型尝试调用不存在的 Tool 或用文字假装执行。

## 6. GUI 调试开关

GUI 调试能力由构建字段 `BuildConfig.GUI_DEBUG_ENABLED` 统一控制。Debug 构建从未入库的
`AndroidAgent/dev.properties` 读取 `guiDebugEnabled`，默认 `false`；Release 构建始终强制
为 `false`。关闭后隐藏应用内和跨 App 调试 UI、停止收集 `GuiDebugTrace`，并固定使用
`HYBRID_NODE_FIRST`；不影响 GUI Agent 正常执行和老人可见的任务控制条。
