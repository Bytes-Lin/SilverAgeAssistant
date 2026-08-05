# 开发流程与 Git 管理

## 1. 仓库

```bash
git clone https://github.com/Bytes-Lin/SilverAgeAssistant.git
cd SilverAgeAssistant
```

Codex 项目配置只有在仓库被标记为 trusted 后才会加载。所有开发说明统一从根目录 `README.md`、`AGENTS.md` 和 `docs/` 读取。

## 2. 分支

- `main`：可运行、受保护；
- `develop`：可选集成分支；
- `feat/<scope>-<description>`；
- `fix/<scope>-<description>`；
- `docs/<description>`。

提交尽量小而完整，例如：

```text
feat(android-chat): add recording state machine
feat(server-sync): persist client events idempotently
fix(emergency): keep local dial path independent of REST
```

## 3. Issue 与里程碑

每个功能 Issue 写明：

- 用户场景；
- 范围与非范围；
- 验收标准；
- 安全和权限影响；
- 测试计划；
- 相关文档。

从 `docs/06-development/milestones/` 中复制对应步骤到 GitHub Issue。根 `README.md` 负责维护“模块 → 代码目录 → 文档 → 里程碑”的总映射。

## 4. Codex 使用

建议从仓库根目录启动，让 Codex 统一读取根 `AGENTS.md`。

Android 任务示例：

```text
读取 AGENTS.md、README.md、docs/02-android/android-ui-and-features.md 和 docs/06-development/milestones/M01-android-ui.md，完成老人首页的 Mock UI；不要接网络。只修改 AndroidAgent/ 和必要文档，运行单元测试和 debug 构建，最后审查 diff。
```

FastAPI 任务示例：

```text
读取 AGENTS.md、README.md、docs/04-middle-server/fastapi-communication.md、docs/04-middle-server/api-contract.md 和 docs/06-development/milestones/M04-family-communication.md，实现事件创建与幂等测试；只修改 MiddleServer/ 和相关文档。
```

## 5. Pull Request

PR 必须包含：

- 改动摘要；
- 关联 Issue；
- 截图或录屏（UI）；
- 测试命令和结果；
- 权限或数据变化；
- 已知限制；
- 文档更新。

## 6. 环境和秘密

- `.env`、`local.properties`、签名文件和真实 Key 不提交；
- 当前轻量阶段不要求维护 `.env.example`；新增配置项时在根 README 或对应设计文档中记录名称、用途和安全要求，不写入真实值；
- CI 使用仓库 secrets；
- Android Debug 允许选择本地 llama-server；Release 默认禁用明文 HTTP。

## 7. 开发阶段顺序

Android 双角色基础 UI 已完成，FastAPI 已进入 M04。当前按明确需求小步实现家属注册与设备绑定，不提前扩展事件同步、WebSocket 等未进入任务范围的功能；中台首版使用 SQLite，不建设 PostgreSQL、Redis 或 `infra/`。

1. 工程骨架和 Mock UI；
2. 本地模型或 Mock 模型问答；
3. 真实云端 ASR、LLM、TTS；
4. Room 提醒；
5. FastAPI 与双端绑定；
6. 状态同步；
7. SOS；
8. 天气和新闻播报；
9. Agent Tool Use；
10. 无障碍和支付审批；
11. 图像风险接口。
