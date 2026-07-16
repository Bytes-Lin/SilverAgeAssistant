# Codex 配置与使用

## 1. 已包含文件

- 根目录 `AGENTS.md`：全仓库开发规则，包含 Android、FastAPI、Agent、安全和测试约束；
- 根目录 `README.md`：开发模块、代码目录、相关文档和里程碑的统一入口；
- `.codex/config.toml`：项目审批、沙箱和子代理并发配置；
- `.codex/agents/*.toml`：Android、后端、Agent 和 QA 子代理。

`AndroidAgent/` 与 `MiddleServer/` 不维护局部 `AGENTS.md` 或 `README.md`。Codex 应从仓库根目录启动，并以根 `AGENTS.md` 为统一规则来源。

## 2. 推荐启动方式

从仓库根目录启动 Codex，然后明确引用根 README 中对应模块的文档和当前里程碑：

```text
读取 AGENTS.md、README.md、docs/02-android/android-ui-and-features.md 和 docs/06-development/milestones/M01-android-ui.md。先检查仓库，再实现老人端 Mock 首页和对话页。只完成里程碑范围，运行测试和构建，最后审查 diff。
```

服务端任务示例：

```text
读取 AGENTS.md、README.md、docs/04-middle-server/fastapi-communication.md、docs/04-middle-server/api-contract.md 和 docs/06-development/milestones/M04-family-communication.md。实现事件创建与幂等测试，只修改 MiddleServer/ 和相关文档。
```

## 3. 子代理用途

- `android-engineer`：Compose、Room、系统权限和设备功能；
- `backend-engineer`：FastAPI、REST、WebSocket 和持久化；
- `agent-engineer`：工具、记忆、RAG、Policy；
- `qa-reviewer`：只读安全和质量审查。

## 4. 约束

项目配置不固定具体 Codex 模型，避免账户或环境不支持。模型选择放在个人 `~/.codex/config.toml` 或启动参数中。

不要把百炼 API Key、数据库密码或签名信息放入 Codex Prompt、`AGENTS.md` 或提交内容。

## 5. 文档维护规则

- 项目级入口、模块与文档映射更新根 `README.md`；
- 全局开发约束更新根 `AGENTS.md`；
- 具体产品和技术设计更新 `docs/`；
- 不在代码目录新增重复说明文件。
