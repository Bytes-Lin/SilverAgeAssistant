# 家属远程模型配置需求与接口

实现状态（2026-07-19）：中台三条 REST 接口、SQLite 配置与幂等记录、revision
乐观锁、绑定权限校验、脱敏审计和 OpenAPI 验收测试已完成。Android 已新增
`context_window_tokens` 的填写、下发、旧配置兼容和聊天进度接入；中台字段校验、幂等摘要、
双方读取响应和既有配置 32768 迁移回填也已完成。
REST 拉取是可靠事实来源，本轮不要求 WebSocket 在线才能同步配置。

## 1. 目标

允许已绑定家属为老人设备配置非敏感的模型请求参数。配置先可靠写入 FastAPI 中台，老人设备使用 device credential 拉取后保存到本地 `files/agent/model-config.json`，下一轮聊天直接生效。

该功能只同步以下非敏感信息：

- OpenAI 兼容服务 `base_url`；
- 模型名；
- 协议方言：`llama_cpp` 或 `standard`；
- 上下文长度 Token；
- 最大生成 Token；
- `temperature`、`top_p`、`top_k`；
- 固定关闭的日常聊天思考标记。

## 2. 不在范围内

模型 API Key、Authorization 请求头、Cookie、云厂商账号密码和其他模型凭证不得出现在：

- 家属提交请求；
- 中台数据库；
- 中台日志和审计 Payload；
- WebSocket 消息；
- 家属端配置查询响应；
- 老人端配置拉取响应。

API Key 继续只在老人设备上通过 Android Keystore + AES-GCM 加密保存。中台不得增加 `api_key`、`encrypted_api_key`、`credential`、`authorization` 等字段，也不得提供“临时保存后转发”的实现。

开发用 llama-server 未启用鉴权时可直接使用本功能。正式云端 Key 的家属辅助配置应另行采用不经过中台的线下二维码或近场方案。

## 3. 数据模型

每个老人档案最多保存一份当前配置：

```text
ElderModelConfiguration
- elder_id                 unique, foreign key
- schema_version           integer, current=1
- revision                 integer, starts at 1, monotonically increasing
- base_url                 string, max 500
- model                    string, max 120
- dialect                  LLAMA_CPP | STANDARD
- context_window_tokens    integer
- max_output_tokens        integer
- temperature              decimal
- top_p                    decimal
- top_k                    integer
- reasoning_enabled        boolean, must be false
- updated_by_family_id     foreign key
- created_at               UTC timestamp
- updated_at               UTC timestamp
- last_client_request_id   UUID
```

禁止使用浮点近似值参与权限或金额判断；采样参数可按 JSON number 保存，但响应必须稳定回显。

### 3.1 已有中台数据库升级

- Alembic 为现有模型配置表新增 `context_window_tokens INTEGER NOT NULL DEFAULT 32768`；
- 既有行统一回填 32768，迁移不得删除或重建老人配置；
- 请求 Pydantic schema、数据库模型、Repository、Service、响应 schema 和 OpenAPI 同步增加该字段；
- 幂等请求摘要必须包含 `context_window_tokens`，否则相同 `client_request_id` 改变上下文长度时无法识别冲突；
- revision 规则不变：成功修改上下文长度也视为一次配置更新并递增 revision。

## 4. 家属读取配置

```http
GET /api/v1/elders/{elder_id}/model-config
Authorization: Bearer <family_access_token>
Cache-Control: no-store
```

权限：

- access token 必须有效；
- 家属与 `elder_id` 存在有效绑定；
- 首版绑定家属均可配置；后续多家属场景建议增加 `MODEL_CONFIG_WRITE` 权限；
- 不返回老人设备上的 API Key 状态或任何密钥摘要。

成功响应：

```json
{
  "configuration": {
    "schema_version": 1,
    "base_url": "http://58.199.163.98:11435",
    "model": "qwen3_5",
    "dialect": "llama_cpp",
    "context_window_tokens": 32768,
    "max_output_tokens": 512,
    "sampling": {
      "temperature": 0.6,
      "top_p": 0.9,
      "top_k": 40
    },
    "reasoning_enabled": false
  },
  "revision": 3,
  "updated_at": "2026-07-18T04:00:00Z"
}
```

尚未配置时返回：

```http
404
```

```json
{
  "error": {
    "code": "MODEL_CONFIG_NOT_FOUND",
    "message": "尚未设置模型配置",
    "request_id": "uuid"
  }
}
```

## 5. 家属更新配置

```http
PUT /api/v1/elders/{elder_id}/model-config
Authorization: Bearer <family_access_token>
Content-Type: application/json
Idempotency-Key: <client_request_id>
```

请求：

```json
{
  "schema_version": 1,
  "base_url": "http://58.199.163.98:11435",
  "model": "qwen3_5",
  "dialect": "llama_cpp",
  "context_window_tokens": 32768,
  "max_output_tokens": 512,
  "sampling": {
    "temperature": 0.6,
    "top_p": 0.9,
    "top_k": 40
  },
  "reasoning_enabled": false,
  "expected_revision": 2,
  "client_request_id": "uuid"
}
```

规则：

- `client_request_id` 与 `Idempotency-Key` 必须一致；
- 相同幂等键和相同请求体返回第一次成功结果；
- 相同幂等键但请求体不同返回 `IDEMPOTENCY_CONFLICT`；
- 首次创建时 `expected_revision` 可为 `null`；
- 更新时若 `expected_revision` 与当前版本不一致，返回 `MODEL_CONFIG_REVISION_CONFLICT`；
- 成功事务中递增 `revision`，记录修改家属与 UTC 时间；
- 中台只校验并保存配置，不主动请求家属填写的模型地址；
- `reasoning_enabled` 必须为 `false`。

校验范围：

| 字段 | 规则 |
|---|---|
| `schema_version` | 必须为 `1` |
| `base_url` | `http://` 或 `https://`，1—500 字符，无用户名、密码、query 或 fragment |
| `model` | 去除首尾空白后 1—120 字符 |
| `dialect` | `llama_cpp` 或 `standard` |
| `context_window_tokens` | 1024—2000000，且不得小于 `max_output_tokens` |
| `max_output_tokens` | 64—8192 |
| `temperature` | 0—2 |
| `top_p` | 0—1 |
| `top_k` | 0—1000 |
| `reasoning_enabled` | 必须为 `false` |

成功响应格式与读取接口相同。

## 6. 老人设备拉取配置

```http
GET /api/v1/devices/me/model-config
Authorization: Bearer <device_credential>
Cache-Control: no-store
```

权限与行为：

- device credential 必须有效且属于一个已绑定老人档案；
- 只能返回当前设备所属老人的配置；
- 绑定撤销后不得继续读取；
- 响应格式与第 4 节一致；
- 尚未配置返回 `MODEL_CONFIG_NOT_FOUND`；
- 不返回修改家属账号、API Key 或其他无关资料。

Android 在以下时机调用：

1. 老人会话恢复并取得有效 device credential 后；
2. 进入“和我说话”页面时；
3. 后续收到可选的 `MODEL_CONFIG_AVAILABLE` WebSocket 提示时再通过 REST 补拉。

拉取失败时保留上次校验成功的本地 JSON，不清空可用配置。只有新配置通过端侧校验并原子写入后才切换；下一轮模型请求读取最新配置。

为兼容字段上线前已保存的配置，Android 读取缺少 `context_window_tokens` 的旧响应或旧本地 JSON 时使用 32768；中台完成升级后，新写入和所有读取响应都必须显式返回该字段。`schema_version` 本轮仍为 `1`，因为该新增字段有明确的向后兼容默认值。

## 7. 错误码

| HTTP | `error.code` | 说明 |
|---:|---|---|
| 400 | `INVALID_MODEL_CONFIG` | 字段缺失、范围错误或包含禁止字段 |
| 401 | `AUTHENTICATION_REQUIRED` | access token/device credential 无效 |
| 403 | `MODEL_CONFIG_FORBIDDEN` | 家属无权限或设备不属于该老人 |
| 404 | `MODEL_CONFIG_NOT_FOUND` | 尚未配置 |
| 409 | `MODEL_CONFIG_REVISION_CONFLICT` | 乐观锁版本冲突 |
| 409 | `IDEMPOTENCY_CONFLICT` | 幂等键请求体冲突 |
| 410 | `BINDING_REVOKED` | 家庭绑定已撤销 |

错误响应沿用统一 `{ "error": { "code", "message", "request_id" } }`。

## 8. 日志与审计

允许记录：

- request ID、elder ID、操作者 family ID；
- revision、模型名称、协议方言；
- 成功/失败、耗时和错误码。

不得记录：

- Authorization；
- 完整请求头；
- URL 中可能存在的 query、userinfo 或 fragment；
- API Key、Cookie 或其他凭证；
- 聊天内容和 system prompt。

审计记录应能回答“哪位家属在何时把哪个老人配置更新到哪个 revision”，但不复制保存完整配置 Payload。

## 9. 中台验收标准

- 两个绑定无关的家属无法读取或修改彼此老人配置；
- device credential 只能读取所属老人配置；
- 创建、更新、幂等重试和 revision 冲突行为稳定；
- 所有数值边界有 OpenAPI 和服务测试；
- `context_window_tokens` 能在家属写入、家属读取和老人设备读取三条链路稳定回显，且小于 `max_output_tokens` 时被拒绝；
- 请求出现 `api_key`、`authorization`、`credential` 等禁止字段时拒绝；
- 数据库、应用日志和测试快照中不出现模型 API Key；
- 配置写库成功后老人端能通过 REST 拉取同一 revision；
- 绑定撤销后家属写入和老人拉取都被拒绝；
- OpenAPI 文档明确 `reasoning_enabled=false` 且不包含任何密钥字段。
