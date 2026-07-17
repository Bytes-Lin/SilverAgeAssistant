# 老人设备同步家属资料：中台需求与接口

实现状态：中台首版已于 2026-07-17 完成。接口代码位于 `MiddleServer/app/api/v1/routes/family_contacts.py`，迁移版本为 `0003_family_profile_updated_at`，专项验收位于 `MiddleServer/tests/test_family_contacts.py`。

## 1. 目标与边界

老人和家属完成绑定后，老人端“联系家人”必须显示真实家属称呼、关系和完整手机号，并能进入系统拨号页。资料必须由 FastAPI 中台按当前有效绑定关系返回，Android 不再内置测试联系人。

本需求中的“同步家属所有信息”是指当前产品允许老人查看、且拨号功能必需的家属资料白名单，不是数据库整行复制。严禁返回：

- access token、refresh token、device credential；
- 绑定码、验证码、密码摘要、密钥和会话信息；
- 风控、限流、内部审计字段；
- 其他老人档案、聊天原文或未授权隐私资料。

## 2. 中台交付范围

新增老人设备专用接口：

```text
GET /api/v1/devices/me/family-contacts
Authorization: Bearer <device_credential>
Accept: application/json
```

中台根据 device credential 确定唯一老人档案，只返回该老人当前有效绑定的家属。客户端不得提交或覆盖 `elder_id`，避免水平越权。

### 2.1 成功响应

```json
{
  "snapshot_version": "opaque-version-or-hash",
  "synced_at": "2026-07-17T09:30:00Z",
  "contacts": [
    {
      "binding_id": "5d19bcd3-0c50-4aa5-9199-8c420cb8918e",
      "family_account_id": "40ec9d35-c528-42a0-ad19-32b2e2483aa4",
      "display_name": "李女士",
      "mobile_number": "13800138000",
      "relationship": "CHILD",
      "permissions": [
        "VIEWER",
        "HELPER",
        "EMERGENCY_CONTACT"
      ],
      "emergency_contact": true,
      "bound_at": "2026-07-16T08:00:00Z",
      "profile_updated_at": "2026-07-17T08:30:00Z"
    }
  ]
}
```

字段规则：

| 字段 | 必填 | 规则 |
|---|---|---|
| `snapshot_version` | 是 | 不透明版本或稳定摘要；任一可见字段、权限或绑定状态变化后必须变化 |
| `synced_at` | 是 | 服务端生成的 UTC ISO 8601 时间 |
| `binding_id` | 是 | 当前绑定关系 ID |
| `family_account_id` | 是 | 家属账号 ID，只用于端侧稳定识别，不作为认证凭证 |
| `display_name` | 是 | 家属展示称呼，沿用注册校验规则 |
| `mobile_number` | 是 | 规范化后的完整手机号；首版为 `1[3-9]` 开头的 11 位号码 |
| `relationship` | 是 | `CHILD`、`RELATIVE`、`CAREGIVER` 或 `OTHER` |
| `permissions` | 是 | 当前绑定实际生效的权限编码，不能直接回显客户端申请值 |
| `emergency_contact` | 是 | 是否拥有当前有效紧急联系人权限，可由权限集合确定但响应中必须显式提供 |
| `bound_at` | 是 | 绑定生效时间，UTC ISO 8601 |
| `profile_updated_at` | 是 | 家属可见资料最后修改时间，UTC ISO 8601 |

没有有效联系人时返回 `200` 和空数组，不返回 `404`：

```json
{
  "snapshot_version": "empty-v1",
  "synced_at": "2026-07-17T09:30:00Z",
  "contacts": []
}
```

Android 当前会忽略未知响应字段，因此中台可先返回 `profile_updated_at`；后续资料编辑页面再利用该字段展示更新时间。

### 2.2 错误响应

沿用统一错误信封：

```json
{
  "error": {
    "code": "AUTHENTICATION_REQUIRED",
    "message": "Device credential is missing or invalid."
  }
}
```

| HTTP | `error.code` | 场景 |
|---:|---|---|
| 401 | `AUTHENTICATION_REQUIRED` | 缺少、无效或已吊销的 device credential |
| 403 | `FAMILY_CONTACTS_FORBIDDEN` | 凭证有效，但设备或老人档案不允许读取联系人 |
| 503 | `FAMILY_CONTACTS_UNAVAILABLE` | 中台暂时无法生成一致的联系人快照 |

中台不得在错误消息中回显手机号、凭证或绑定码。

## 3. 数据与一致性要求

- 仅查询 `ACTIVE` 家属账号、`ACTIVE` 绑定关系和当前凭证所属老人档案。
- 多名家属均满足条件时全部返回；排序建议为紧急联系人优先，其后按 `bound_at`、`binding_id` 稳定排序。Android 不依赖服务端顺序。
- 家属称呼、手机号、关系、权限或紧急联系人状态修改后，下一次请求必须返回新值和新的 `snapshot_version`。
- 绑定撤销、家属停用或权限收回后，下一次完整快照必须立即移除对应联系人；不能依赖增量删除事件。
- `mobile_number` 应直接来自家属账号的规范化号码，不新增无必要的明文副本。
- 若现有表没有可见资料更新时间，可新增 `updated_at`，或由所有可见字段计算稳定摘要；不得使用进程内计数作为唯一版本。
- 本接口是完整快照读取，不需要幂等键。后续可支持 `ETag` / `If-None-Match`，但首版不能因此改变上述 JSON 成功响应契约。

## 4. 权限、隐私与审计

- 只接受 device credential；家属 access token 不得调用本接口读取自己的或其他家属的完整号码集合。
- 现有面向家属的 `GET /bindings` 继续只返回脱敏手机号，不因本需求改成明文。
- 中台日志、异常追踪、指标标签和审计详情不得记录 `mobile_number`、Authorization 或完整响应体。
- 审计只记录请求主体 ID、老人档案 ID、返回联系人数量、`snapshot_version`、结果和时间。
- 响应设置 `Cache-Control: no-store`；生产环境仅允许 HTTPS。
- 老人撤销设备凭证后，旧凭证必须立即失去读取联系人能力。

## 5. Android 已完成的配套行为

- 启动应用和进入“联系家人”页面时请求此接口，页面提供手动刷新。
- 成功响应先以 Android Keystore AES-GCM 加密写入应用私有存储，再更新页面。
- 联系人快照排除 Android 云备份和设备迁移；手机号不写日志。
- 断网或中台暂时失败时保留并显示上一次加密快照，同时明确提示同步失败；服务端明确拒绝认证、联系人权限或绑定已撤销时立即清除缓存。
- 空快照会清除旧联系人显示，避免绑定撤销后继续拨号。
- 点击电话按钮使用 `Intent.ACTION_DIAL` 打开系统拨号页，不申请直接拨号权限。

Android 不再显示“小林”“小周”等内置测试对象；首次同步失败时联系人列表为空。

## 6. 中台实现建议

保持现有分层：

- route：认证、响应模型和 HTTP 映射；
- service：凭证主体校验、绑定过滤、权限投影和快照版本生成；
- repository：一次查询所需账号与绑定资料，避免逐联系人 N+1 查询；
- schema：严格声明所有响应字段、枚举和 UTC 时间。

需要同步更新：

- OpenAPI 快照与接口测试；
- repository/service/route 单元或集成测试；
- 如增加 `updated_at` 字段，提供 Alembic 迁移；
- 本文档和 [`api-contract.md`](api-contract.md) 的实现状态。

## 7. 验收标准

1. 一名老人绑定一名家属时，老人设备能取得真实称呼、完整手机号、关系和权限。
2. 一名老人绑定多名家属时，响应完整且无重复，顺序稳定。
3. A 老人设备不能通过参数或凭证读取 B 老人的家属。
4. 无效、过期、已吊销凭证分别按统一认证规则拒绝。
5. 撤销绑定、停用家属或收回权限后，新快照立即反映变化，版本发生改变。
6. 家属修改称呼或合法手机号后，新快照返回新值，旧值不再出现。
7. `GET /bindings` 仍只返回脱敏手机号；只有本设备接口返回完整号码。
8. 访问日志、错误日志、审计记录和测试快照中没有真实手机号或凭证。
9. OpenAPI 明确 device credential 认证、字段枚举、空数组语义和错误码。
10. Android 模拟器完成“绑定 → 同步联系人 → 显示完整号码 → 打开系统拨号页”的端到端测试。
