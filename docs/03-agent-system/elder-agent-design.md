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
- `send_family_message`
- `call_saved_contact`
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

## 10. 测试重点

- 模型提出未注册工具；
- 参数缺失或类型错误；
- ASR 误识别金额/联系人；
- 订单价格在确认后变化；
- 家属审批超时；
- App 被杀后任务恢复；
- 恶意页面文本诱导 Agent；
- Prompt injection 试图读取 API Key；
- 记忆错误写入与删除。
