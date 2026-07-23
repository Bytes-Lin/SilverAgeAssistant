# 中台交付需求：绑定码重新生成与老人设备恢复绑定

## 1. 文档状态

- 需求来源：老人端应用数据丢失、换机或设备凭据失效后，由已登录家属重新生成绑定码，帮助老人恢复绑定；
- Android 状态：家属首页已实现“重新生成绑定码”，复用现有 `POST /api/v1/bindings/codes`；
- 中台现状（2026-07-17）：已支持生成新码、保留旧设备可用性，并在新设备成功绑定后原子轮换设备凭据；
- Base path：`/api/v1`；
- 交付建议：不新增路由，保持现有请求和响应兼容，扩展 `POST /devices/bind` 的受控恢复语义。

## 2. 现状核对

### 2.1 已支持

家属携带 Bearer access token 再次调用：

```text
POST /bindings/codes
```

```json
{
  "elder_id": "uuid",
  "client_request_id": "new-uuid"
}
```

中台当前会撤销同一家属、同一老人档案下仍未使用的旧码，并返回新的 6 位绑定码：

```json
{
  "binding_code": "012345",
  "expires_at": "2026-07-17T08:10:00Z",
  "elder_id": "uuid",
  "family_mobile_masked": "138****8000"
}
```

### 2.2 已完成扩展

当该家属与老人已经存在有效 `Binding` 时，老人端使用新码调用
`POST /devices/bind` 会进入受控恢复流程，不再仅因已有关系返回：

```text
409 DEVICE_BINDING_CONFLICT
```

当前实现复用原 `Binding`，保持关系、权限和紧急联系人设置不变。新码联合校验成功后，
中台在同一 SQLite 事务中消费新码、撤销该老人档案旧设备凭据、签发新凭据并写入
`DEVICE_REBOUND` 脱敏审计；失败或事务回滚时旧凭据继续有效。

## 3. 目标与安全边界

目标：只有持有新绑定码及生成该码的家属手机号，并确认共享范围的老人设备，才能替换该老人档案原有的设备凭据。

必须遵守：

- 重新生成绑定码本身不得撤销旧设备凭据，避免误触、网络失败或恶意请求导致老人设备立即离线；
- 只有新设备联合校验成功后，才在同一事务中消费新码并撤销旧设备凭据；
- 失败、超时、码过期或取消时，旧设备继续可用；
- 不改变原有家属与老人关系、权限和紧急联系人设置；
- 不返回或记录完整手机号、绑定码、旧凭据或新凭据摘要。

## 4. 目标流程

```text
家属已登录
  → POST /bindings/codes 生成新码，旧未使用码失效
  → 旧设备凭据仍可用
  → 老人在新安装或新手机提交新码 + 家属手机号 + 共享确认
  → POST /devices/bind 联合校验
  → 中台识别为同一家属、同一老人档案的恢复绑定
  → 原子消费新码、撤销旧设备凭据、签发新凭据
  → 新设备恢复同步，旧设备凭据立即失效
```

## 5. 接口契约

### 5.1 家属重新生成绑定码

沿用现有接口，无字段变更：

```text
POST /bindings/codes
Authorization: Bearer <family_access_token>
Content-Type: application/json
```

```json
{
  "elder_id": "uuid",
  "client_request_id": "uuid"
}
```

规则：

1. `client_request_id` 在当前家属和操作类型下幂等；同一 ID 重试返回同一绑定码和过期时间；
2. 使用新的 ID 才生成新码，并撤销该家属、该老人档案下其他未使用码；
3. 已存在有效绑定时仍允许生成新码；
4. 生成新码不撤销任何有效 `Binding` 或 `DeviceCredential`；
5. 只有对该老人档案具有创建/管理权限的家属可调用。

### 5.2 老人设备恢复绑定

沿用现有接口和字段：

```text
POST /devices/bind
Content-Type: application/json
```

```json
{
  "binding_code": "012345",
  "family_mobile_number": "<family-mobile>",
  "elder_display_name": "王阿姨",
  "sharing_consent": true,
  "device_id": "new-android-installation-uuid",
  "device_name": "王阿姨的手机",
  "client_request_id": "uuid"
}
```

服务端在完成原有绑定码、手机号、老人档案、共享确认与限流校验后，按以下规则处理：

| 当前状态 | 目标结果 |
|---|---|
| 该老人和家属没有有效绑定 | 按原有首次绑定流程创建 `Binding` 和设备凭据 |
| 已有同一家属、同一老人档案的有效绑定 | 进入恢复绑定，复用该 `Binding`，轮换关联设备凭据 |
| 请求 `device_id` 已有效绑定同一老人档案 | 允许凭据轮换，不创建重复 `Binding` |
| 请求 `device_id` 已有效绑定其他老人档案 | `409 DEVICE_BINDING_CONFLICT` |
| 新码属于其他家属或其他老人档案 | 按现有规则返回通用凭据错误，不泄露归属 |

恢复成功仍沿用现有 `DeviceBindResponse`，无需 Android 新增 DTO：

```json
{
  "binding_id": "uuid",
  "elder_id": "uuid",
  "family_account_id": "uuid",
  "family_mobile_masked": "138****8000",
  "relationship": "CHILD",
  "permissions": ["VIEWER", "HELPER", "EMERGENCY_CONTACT"],
  "device_credential": "opaque-secret",
  "bound_at": "2026-07-17T08:01:00Z"
}
```

`bound_at` 在恢复场景建议表示本次设备凭据生效时间；若为保持兼容必须保留关系首次建立时间，应新增可选字段 `device_bound_at`，并在 OpenAPI 中说明语义。

## 6. 事务、幂等与并发

恢复绑定必须在一个 SQLite 事务中完成：

1. 锁定或以条件更新方式消费仍有效的新绑定码；
2. 读取并确认现有 `Binding` 属于新码关联的同一家属和老人档案；
3. 撤销该老人档案原有的有效设备凭据；
4. 为请求 `device_id` 创建或轮换 `DeviceCredential`；
5. 保存幂等记录；
6. 写入不含秘密的 `DEVICE_REBOUND` 审计记录；
7. 提交事务后返回新凭据。

事务回滚时，绑定码、旧设备凭据和绑定关系必须全部保持原状态。

幂等与并发要求：

- 相同 `client_request_id` 重试返回相同 `binding_id` 和同一新 `device_credential`，不能再次轮换；
- 不同请求并发消费同一绑定码时只能一个成功；
- 旧凭据在成功事务提交后立即无法通过认证；
- 新凭据签发失败时不得先撤销旧凭据；
- 不创建重复的有效家属—老人 `Binding`。

## 7. 错误码

继续使用现有错误码：

| HTTP | `error.code` | 场景 |
|---:|---|---|
| 400 | `BINDING_CREDENTIALS_INVALID` | 手机号、绑定码或归属联合校验失败 |
| 400 | `SHARING_CONSENT_REQUIRED` | 未确认共享范围 |
| 409 | `BINDING_CODE_USED_OR_REVOKED` | 新码已使用或已被重新生成操作撤销 |
| 409 | `DEVICE_BINDING_CONFLICT` | 请求设备当前属于其他老人档案，不能自动接管 |
| 410 | `BINDING_CODE_EXPIRED` | 新码已过期 |
| 429 | `BINDING_ATTEMPTS_EXCEEDED` | 超过绑定尝试限制 |

同一家属、同一老人档案已经存在有效绑定，不再单独构成 `DEVICE_BINDING_CONFLICT`。

## 8. 验收测试

中台交付至少覆盖：

1. 已绑定家属可生成新码，响应为新码且旧的未使用码立即失效；
2. 仅生成新码时旧 device credential 仍可查询绑定和同步；
3. 新码 + 正确家属手机号可为同一老人恢复设备绑定；
4. 恢复成功后旧 credential 被拒绝，新 credential 可正常访问；
5. 恢复成功后 `binding_id`、关系、权限和紧急联系人设置保持不变；
6. 新码错误、手机号错误、码过期、未确认共享或请求失败时旧 credential 仍可用；
7. 新 `device_id` 已绑定其他老人档案时返回 `DEVICE_BINDING_CONFLICT`，不得接管；
8. 相同 `client_request_id` 重试返回相同结果，不重复轮换；
9. 并发使用同一新码只有一个请求成功，且数据库中只有一个有效设备凭据；
10. 审计记录包含操作者、老人档案、旧/新设备记录 ID 和时间，但不含明文码、完整手机号或凭据。

## 9. Android 联调判定

中台完成上述语义前：

- 家属端可以成功显示新绑定码；
- 老人端尝试恢复时仍可能看到“这部手机仍有旧绑定”的提示；
- 不应把“新绑定码已生成”描述为“老人设备已恢复”。

中台交付后，使用两个模拟器完成：初次绑定 → 家属重新生成码 → 清除老人端本地凭据/换新安装 ID → 新码恢复 → 旧凭据失效 → 通知、提醒和家属联系人补拉正常。
