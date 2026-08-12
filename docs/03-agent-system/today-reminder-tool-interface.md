# 今日提醒 Agent Tool 接口

## 1. 定位

`list_today_reminders` 是老人端聊天 Agent 的只读低风险 Tool。它直接读取老人手机 Room 中的
今日提醒快照，不请求中台、不读取 Compose UI，也不使用长期 Memory 推测实时状态。

Room 是老人提醒的端侧事实来源。家属命令必须先经 REST 补拉并以 `command_id` 幂等写入
Room，之后才会出现在本 Tool 中。断网时 Tool 仍可读取已保存数据。

## 2. 调用定义

```json
{
  "name": "list_today_reminders",
  "description": "读取老人手机中今天的提醒及其等待确认、稍后提醒或已完成状态。",
  "parameters": {
    "type": "object",
    "properties": {},
    "additionalProperties": false
  }
}
```

首版不接受日期、提醒 ID或完成状态参数，防止模型越权读取其他日期或修改提醒。

## 3. 返回结构

```json
{
  "ok": true,
  "local_date": "2026-08-11",
  "timezone": "Asia/Shanghai",
  "count": 2,
  "pending_count": 1,
  "snoozed_count": 0,
  "completed_count": 1,
  "items": [
    {
      "deadline_at": "2026-08-11T00:00:00Z",
      "local_deadline_time": "上午 8:00",
      "title": "服药提醒",
      "detail": "请按家人设置的计划服药。",
      "source_display_name": "小林",
      "status": "pending"
    }
  ]
}
```

`status` 仅允许：

- `pending`：等待老人确认；
- `snoozed`：老人已选择稍后提醒；
- `completed`：老人执行了完成确认操作。

Tool 不输出 Room 主键或中台命令 ID，避免模型伪造后续状态修改调用。

## 4. Agent 使用规则

- 老人询问“今天有什么事”“下一条提醒是什么”“刚才的提醒完成了吗”时必须调用 Tool；
- Tool 返回空列表时明确回答今天暂无提醒；
- 未完成提醒可由 Agent 用简短文字或 TTS提醒老人，但不得自动标记完成；
- 只有老人明确点击或确认后，确定性 Android 逻辑才能写入 `COMPLETED`；
- `completed` 只表示确认动作，不得表述成“确认已服药”或医疗事实；
- Tool 结果属于当前轮短期上下文，不写入长期 Memory，也不上传中台聊天记录。

Tool 的原始 `items` 保留今日三种状态，供“刚才的提醒完成了吗”等状态核对使用。面向老人
回答“今天还有什么没做”时，只能罗列 `pending` 和 `snoozed`；不得把 `completed` 再说成
未完成。“未确认完成”只描述端侧确认状态，不能推断老人客观上没有做这件事。

主聊天 Agent 对“我今天还有什么事没做”“今天有什么安排”等明确的今日未完成事项列表查询
使用确定性路由，在调用聊天模型前执行本 Tool。创建提醒、修改提醒、查询明天事项以及“怎么
查看提醒”等操作方法问题不走该路由，避免误触发。“下一条提醒是什么”“刚才的提醒完成了吗”
仍由主模型组织针对性回答，但系统提示要求模型必须先调用本 Tool 获取实时状态。

已知代码一致性问题：`TodayRemindersTool` 当前返回字段 `local_deadline_time`，而
`TodayRemindersToolResultPresenter` 读取的是 `local_time`。因此确定性列表回复可能省略时间，
但标题、详情和状态仍可展示。后续实现修复时应统一字段名并补回归测试，不能同时长期保留两个
含义相同的字段。

## 5. Android 接口

实现类：`TodayRemindersTool`，依赖注入 `ReminderRepository`：

```kotlin
class TodayRemindersTool(
    private val repository: ReminderRepository,
) : AgentTool
```

执行器调用 `repository.reminders.first()` 获得已按手机本地日期过滤并排序的不可变快照。
首页、今日提醒页面和 Tool 共享同一 Repository，禁止各自维护重复提醒缓存。

`deadline_at` 是家属设置的完成截止时间。截止时老人仍未确认完成，Android 使用每条提醒
独立的 WorkManager 周期任务发送本地高优先级通知，之后每 1 小时重复一次；完成后立即取消。
WorkManager 可能受省电策略影响而延迟，不承诺秒级准点。
