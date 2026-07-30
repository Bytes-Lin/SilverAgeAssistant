# Android 粗审与 Agent 开发交接记录

> 核对日期：2026-07-30
> 核对范围：`AndroidAgent/` 与 Android/Agent/中台接口相关开发文档
> 代码基线：`main`，审查开始时为 `65865d6`；最近一次包含 Android 核心代码的提交为 `9a0e581`

## 1. 结论

当前 Android 工程可以作为后续 Agent 系统和 GUI Agent 开发的基线。单 APK 双角色、注册绑定和会话恢复、文字流式 MLLM、基础长期记忆、四个 Agent Tool、天气、家属通知与提醒、联系人同步、模型配置、全局用量统计以及独立状态监控 Agent 均有真实代码实现，不只是界面占位。

本次没有修改 Android 运行代码。开始 GUI Agent 前，应先让 Android 安全事件协议追上中台最新契约；否则一旦中台返回 GUI 点单协助事件，家属端可能无法解析整批事件。

## 2. 已核实的功能

| 功能域 | Android 实现状态 | 文档对照 |
|---|---|---|
| 工程与导航 | 单 APK、老人/家属双角色导航，`minSdk=29` | 与 Android 功能文档一致 |
| 注册与绑定 | 家属注册、老人档案、绑定码、重新生成绑定码、设备绑定 | 与初始化和中台绑定文档一致 |
| 会话恢复 | 正式会话状态与加密凭证分离保存，启动时恢复角色和首页 | 与 Android 功能文档一致 |
| 模型接入 | OpenAI Chat Completions 兼容协议、SSE、可取消请求、llama.cpp 方言、采样参数 | 与模型接入文档一致 |
| 文字聊天 | 左右气泡、打字/系统手写入口、上下文圆环、失败重试 | 与 Android 功能文档一致；聊天正文仍只在进程内 |
| Agent Tool | `get_current_time`、`get_weather`、`call_family_contact`、`report_family_situation` | 与当前 TODO 完成项一致 |
| 长期记忆 | 私有 `files/agent/MEMORY.md`，绑定资料和联系人摘要写入 system prompt | 与首版记忆文档一致 |
| 天气 | 粗略定位、两级城市解析、Open-Meteo 当前及未来三天、60 分钟共享缓存 | 与首页和天气 Tool 文档一致 |
| 提醒 | Room 保存，家属通知/一次性提醒补拉、ACK、完成/稍后样式与排序 | 与当前提醒首版文档一致 |
| 家属协同 | 通知、提醒、联系人、模型配置、用量、今日状态、紧急事件 | 主要链路已接中台 |
| 用量 | MLLM 调用全局包装记录、Room 暂存、每小时 WorkManager 上报、手动即时上报 | 与用量文档一致 |
| 状态监控 | 独立前台服务、动态间隔/关闭、六小时窗口、连续异常策略、短信和图像证据 | 与开发阶段 mock 方案一致 |
| 本地敏感数据 | token、device credential、模型 API Key、家属联系人使用 Keystore/AES-GCM；相关文件排除备份 | 与安全边界一致 |

## 3. 审查发现与优先级

### P1：Android 尚未适配最新安全事件协议

中台已经支持：

- `GUI_ORDER_ASSISTANCE_REQUIRED`；
- `resolved_at`；
- `scope=active_emergencies`；
- `POST /elders/{elder_id}/safety-events/{event_id}/resolve`。

Android 当前仍存在以下旧协议实现：

- `SafetyEventType` 没有 `GUI_ORDER_ASSISTANCE_REQUIRED`；
- `SafetyEvent` 没有 `resolvedAt`；
- JSON 解析直接调用 `SafetyEventType.valueOf(...)`，遇到新事件类型会抛异常，可能导致整批事件刷新失败；
- 家属端只请求 `scope=today`，紧急事件无法跨日保留；
- “今日状态”和“紧急事件”没有“已完成/删除”按钮，也没有 resolve Repository 方法；
- 紧急事件页文案仍限定为“今天”。

新会话应先完成该协议适配并补充 Repository、ViewModel、Compose 和解析测试，再开始让 GUI Agent 真实创建兜底事件。

### P1：GUI Agent 的确定性基础尚未实现

当前只有通用 Tool 注册与最多三轮的模型/Tool 编排。文档中规划的以下组件仍不存在：

- 通用 `PolicyEngine`；
- 可持久化、可恢复的 Agent Task 状态机；
- AccessibilityService 与启用引导；
- 页面节点树观察、敏感字段过滤和受控动作执行器；
- 页面状态/价格变化后的确认失效机制；
- GUI 任务超时控制与家属兜底事件创建器。

GUI Agent 不能直接扩展为“模型自由点击”。应先建立任务、观察、动作、策略、确认和审计边界。

### P2：system prompt 描述的能力多于当前注册工具

system prompt 已描述提醒、下单、音乐、打开应用等能力，但当前 Tool Registry 只注册时间、天气、电话和家属报告。虽然模型请求中的 `tools` 列表只包含真实工具，提示词仍可能使模型用自然语言声称可以执行尚未实现的操作。

后续应由 Tool Registry 或 Capability Snapshot 动态生成“当前可用能力”提示，避免静态 prompt 与实现继续漂移。

### P2：上下文长度目前只有显示和配置，没有预算执行

聊天页可显示服务端返回的 prompt Token 占比，但 `AgentChatCoordinator` 仍把完整进程内历史加入请求，没有预检裁剪、摘要、结构化短期记忆或超限恢复。长对话最终仍可能由模型服务返回上下文超限。

GUI Agent 开发前至少需要共享的 `ContextBudgetManager`；聊天和 GUI Agent 可以使用不同策略，但应共用 Token 估算、保留规则和压缩接口。

### P2：长期记忆是完整 Markdown 截断注入，不是按问题检索

`MarkdownAgentLongTermMemory.markdownForPrompt()` 最多读取前 8000 字符并整段加入 system prompt。文档中“只使用当前问题需要的最少记忆”目前只是提示模型遵守，并没有端侧检索实现。结构化 `MemoryItem`、确认写入、来源和有效期仍待开发。

### P2：状态监控仍固定使用 mock 图像

`SafetyMonitoringService` 在主源码中直接实例化 `MockSafetyImageSource`，因此 debug/release 代码路径都没有真实网络摄像头来源。该行为符合当前开发测试阶段文档，但在形成可发布构建前必须改为显式的 source factory/功能配置，避免测试图片被误当成真实现场。

### P2：Release 运行配置尚未闭环

release 构建的中台地址、模型地址和模型名为空，明文 HTTP 被禁用；当前没有面向发布环境的中台地址注入和签名/CI 配置。该项已在 TODO 中标记未完成，不影响当前 debug 联调，但不能把 release APK 描述为可部署版本。

### P3：静态检查警告

Lint 为 0 error、9 warning。需要后续关注的业务相关警告是电话 Intent 的 package visibility 查询；其余主要为 target/compile SDK 新版本提示、依赖版本提示、Compose Modifier 参数顺序和 KTX 风格提示。无需在 GUI Agent 开发前做无关依赖升级。

## 4. 已知占位与未完成范围

以下页面或能力仍是占位/基础接口，不应在新会话中误判为已完成：

- ASR、TTS、录音和语音播放闭环；
- 跨会话聊天历史、上下文压缩和完整记忆治理；
- 本地提醒创建/编辑、AlarmManager 到时触发、真正的稍后重调度；
- 生活助手、音乐、SOS；
- 通用审批、紧急联系人远程管理；
- 真实局域网摄像头；
- GUI Agent、外卖和网购执行；
- RAG 和新闻。

## 5. 构建与检查记录

使用现有 Android Studio JBR、现有 Android SDK 和本机 Gradle 缓存，以离线模式执行：

```powershell
.\gradlew.bat --offline testDebugUnitTest lintDebug assembleDebug --console=plain
```

结果：

- `BUILD SUCCESSFUL`；
- JVM 单元测试：92 个，0 failure，0 error，0 skipped；
- Android lint：0 error，9 warning；
- debug APK 构建成功；
- 未运行连接模拟器/真机的 `connectedDebugAndroidTest`。

构建期间 Android Studio/另一 Gradle 进程占用了 Kotlin 增量缓存。Gradle 自动回退到无守护进程的非增量编译并最终成功；这不是本次发现的业务代码编译错误。

## 6. 新 Codex 会话建议起点

新会话开始后按以下顺序推进：

1. 阅读本交接记录、`docs/08-roadmap/todo.md`、Android 功能文档、Agent 设计和最新安全事件接口文档；
2. 先适配 `GUI_ORDER_ASSISTANCE_REQUIRED`、活动紧急事件查询和 resolve 全链路；
3. 定义共享 Agent 基础：`AgentTask`、`PolicyDecision`、`ContextBudget`、Tool 审计和失败/超时状态；
4. 建立 Accessibility 观察与执行接口，只在 mock App 中跑通；
5. 实现 GUI 任务超时后的确定性停止和幂等家属兜底事件；
6. 再进入真实外卖/网购 App 的有限白名单流程。

首个 GUI Agent 版本必须停在支付或系统认证前，不读取或填写支付密码、验证码和生物识别信息。
