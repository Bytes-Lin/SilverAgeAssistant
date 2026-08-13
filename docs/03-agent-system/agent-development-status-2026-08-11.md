# Agent 系统开发进度（2026-08-11）

本文按当前仓库中的可执行代码记录 Agent 开发状态。“已实现”表示主要代码链路和对应测试
已经存在，不等于完成全部真机兼容、安全审计或产品验收。详细规则仍以同目录的专题设计文档
为准。

## 1. 主聊天 Agent

已实现：

- OpenAI 兼容流式聊天、取消、错误映射、原请求重试和最多 3 轮 Tool Call 编排；
- 强类型 Tool schema、Tool Registry、低风险工具执行策略和确定性 Tool 路由；
- 独立 system prompt、本地启动 Memory 快照、Summary、8 轮 Window Context 和实时 Token
  占用统计；
- 窗口满 8 轮时压缩旧 5 轮并保留最近 3 轮；窗口提前超过 60% 预算时压缩整个窗口为
  1 个合成轮次；Summary 达到 10% 预算时单独压缩；
- 滑动窗口压缩同时生成 Summary 增量和长期记忆候选，只有能由老人原话 evidence 支持、
  不含敏感字段且满足长度/预算限制的稳定事实才追加到 `files/agent/MEMORY.md`；
- Memory 每个 Android 进程只读取一次。进程内新增事实不改变当前缓存，下一次冷启动后才进入
  system prompt；Summary、Window 和 Tool 轨迹不持久化；
- 主聊天推理与上下文压缩分别记录模型用量，圆形进度显示当前实际组装上下文占配置窗口的
  比例；模型返回 `prompt_tokens` 后用实际值校准本地估算。

尚未完成：

- 跨进程聊天历史恢复；
- 结构化 MemoryItem、来源/置信度/有效期治理和老人可见的记忆管理页；
- 通用 Policy Engine、跨工具可恢复任务状态机和 RAG。

## 2. 已注册的主聊天 Tools

代码对应的完整 Tool、GUI Action 和状态监控内部能力清单见
[`agent-tools-and-capabilities.md`](agent-tools-and-capabilities.md)。

| Tool | 状态 | 当前边界 |
|---|---|---|
| `get_current_time` | 已实现 | 读取设备本地日期、星期、时间和时区；主聊天与 GUI Agent 共享实例 |
| `get_weather` | 已实现 | 使用设备粗略位置、Open-Meteo 和共享缓存，不向模型暴露经纬度 |
| `list_today_reminders` | 已实现 | 只读 Room 今日快照；列表型问法确定性调用，只向老人列出 `pending/snoozed` |
| `call_family_contact` | 已实现 | 本地加密联系人解析，号码不进入模型；拨号前等待老人本地确认 |
| `report_family_situation` | 条件注册 | 中台 `FamilySituationReporter` 可用时注册；提交一般/紧急家属事件，使用确定性事件类型和幂等请求 |
| `gui_agent` | 已实现核心链路 | 异步创建、查询、暂停、继续和取消 GUI Todo；`STARTED` 不代表操作成功 |

`list_today_reminders` 的原始 Tool 结果保留 `pending/snoozed/completed`，用于回答某条提醒
是否已确认；“今天还有什么没做”等回答只能列出尚未确认完成项。“确认完成”只表示老人执行
了确认操作，不能推断已经服药或客观完成了对应行为。

当前有一个已知字段问题：Tool 返回 `local_deadline_time`，确定性结果整理器读取
`local_time`，因此列表回复可能缺少时间；状态和文字内容不受影响，待统一字段并补测试。

主 Agent system prompt 当前仍泛化描述了创建/修改提醒和新闻查询等规划能力，但对应 Tool
尚未注册；正式能力边界必须以 Registry 为准，后续需收紧提示词。

待开发 Tools：本地创建/修改提醒、新闻摘要、更多生活服务以及受 Policy Engine 约束的订单
准备/审批工具。

## 3. 语音交互

已实现：

- 老人端全局语音开关和家属模式隔离；
- Qwen ASR WebSocket、内存 PCM `AudioRecord`、连接中松手排队、取消和麦克风释放；
- Qwen TTS WebSocket、PCM 播放、AudioFocus 和单播放器仲裁；
- 聊天按住说话、完整回答单次播报、家属新通知播报和新闻前五条播报；
- GUI 无障碍覆盖层按住说话：录音时暂停执行许可，ASR 文本只进入当前 GuiRun 短期历史；
- GUI 普通动作只播报下一步说明，商品/订单确认和付款交接才播报必要详情；
- GUI 任务切换到第三方 App 后仍保持老人模式语音会话，全局开关关闭会停止录音和播报。

待验证或待开发：系统 TTS 显式降级、多厂商设备兼容、API 29 完整验收、后台/来电边界的
更大规模真机测试。

## 4. GUI Agent

已实现的核心架构与执行能力：

- 主 Agent 通过异步 `gui_agent` Tool 创建任务，GUI 执行不阻塞聊天；
- 单设备单 GUI 任务独占屏幕，Todo 只持久化任务内容、状态、完整失败次数和协助事件 ID；
- 美团、微信、淘宝目标解析、包名启动、前台窗口验证和第二次完整运行前回桌面重启目标 App；
- 用户授权后的 `AccessibilityService`、跨 App 顶部覆盖层、暂停/继续/返回任务、按住说话和
  二次确认取消；
- 人工触摸接管、持续离开目标 App、主 Agent 控制和录音共用执行暂停门；目标 App 内正常
  Activity/WebView 页面跳转经过防抖后保持连续执行；
- API 30+ 整屏截图、控制条排除、敏感页拒绝上传、最长边/总像素预算、JPEG 压缩和内存清理；
- 当前截图 `frame_id`、无障碍节点树、节点优先混合定位、纯坐标实验模式和归一化坐标反向映射；
- 节点点击、坐标点击、文本输入和滚动；每个真实动作后重新截图验证，旧帧动作会被拒绝；
- 独立 GUI system prompt、独立 MLLM 用量、最近步骤短期历史和共享时间 Tool；不读取主聊天
  上下文或长期 Memory；
- 完整 GuiRun 正常推进时不设固定 ReAct 总步数上限；同一页面的同一步骤允许尝试 5 次，
  第 6 次仍重复且没有页面进展时返回本次运行失败；
- 第一次完整失败告知老人并自动开始第二次；第二次完整失败退出目标 App、返回聊天页、向
  家属幂等上报紧急协助事件，并向老人显示确定性失败反馈；
- 提交订单前必须经过老人确认门，支付、密码、验证码和生物识别页面停止自动化并交给老人。
- GUI Planner 支持 `click_node/click_point/input_text/input_text_focused/scroll/back/wait`、
  `ask_elder/ready_for_payment/use_tool/complete/fail`；这些是受执行器安全门约束的 GUI Action，
  不是主聊天 Tool。`use_tool` 当前只能访问 GUI Registry 中的 `get_current_time`；
- `BuildConfig.GUI_DEBUG_ENABLED` 统一控制调试面板、覆盖层调试摘要、追踪采集和纯坐标实验；
  Debug 默认关闭，Release 强制关闭，不影响正常 GUI 执行。

当前限制：

- 美团等真实 App 的页面结构和广告/弹窗变化较大，打开 App 与部分搜索/加购动作已有真机
  成功记录，但完整外卖/网购流程成功率尚未达到验收标准；
- API 29 尚缺 MediaProjection 授权截图兼容路径，API 34+ 尚未切换指定窗口截图；
- OCR 观察、稳定页面状态抽象、可恢复 GUI Run、金额阈值和家属订单审批尚未完成；
- 第二次完整失败后的家属协助链路已接通，但仍需覆盖离线重试、重复事件和更多异常退出测试。

## 5. 独立状态监控 Agent

已实现独立前台服务、动态检测间隔、mock 图像源、OpenAI 兼容 MLLM 严格 JSON、六小时状态
窗口、连续两次异常上报、连续三次异常短信、证据图像上传和家属复核。该 Agent 不共享主聊天
上下文或 Memory，也没有提供给 MLLM 的 Tool Registry；图像源、分析器、状态存储、家属事件、
证据上传和短信由 Kotlin 状态机按固定顺序调用。

尚未完成真实局域网摄像头发现、鉴权、抓图、多摄像头选择和正式设备后台稳定性验收。

## 6. 当前建议开发顺序

1. 继续真机调试 GUI 观察、节点/坐标定位和重复步骤检测，建立可重复的有限白名单流程；
2. 完善结构化 Memory 治理和用户可见管理能力；
3. 建立通用 Policy Engine，并把金额阈值、订单确认和家属审批接入确定性策略；
4. 补齐 API 29 截图兼容、OCR/VLM 后备观察和 GUI 可恢复任务状态；
5. 扩展本地提醒创建/修改、新闻摘要等低风险 Tools；
6. 完成语音多设备、API 29、系统 TTS 降级和 Agent 安全回归测试。
