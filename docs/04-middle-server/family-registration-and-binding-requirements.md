# 中台交付需求：家属注册、绑定码与手机号联合校验

## 1. 文档状态

- 需求来源：Android 双角色初始化与家庭绑定流程；
- Android 状态：基础表单、字段校验和离线状态已实现；
- 中台状态：已实现首版，待 Android 网络层联调；
- Base path：`/api/v1`；
- 本文是中台开发和联调验收依据，字段或流程变更时需同步更新 Android DTO、`api-contract.md` 和 OpenAPI 测试。

首版实现日期：2026-07-16。实现位于 `MiddleServer/app/`，初始迁移位于 `MiddleServer/alembic/versions/`，自动化验收位于 `MiddleServer/tests/`。

## 2. 目标与边界

目标：家属完成身份注册并创建老人档案后，由中台生成一次性 6 位绑定码。老人设备使用“绑定码 + 生成该码的家属手机号”共同校验，校验成功后建立老人、家属和设备之间的授权关系。

本流程不处理：

- 老人模型 API Key；
- 完整聊天、录音、提醒详情或医疗信息；
- 支付密码、验证码或生物识别信息；
- 家属端对老人状态的默认访问授权。权限必须在绑定成功后由服务端校验。

## 3. Android 已采集字段

### 3.1 家属端注册资料

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `family_display_name` | string，1..20 | 是 | 家属称呼 |
| `family_mobile_number` | string | 是 | 家属登录身份，也是联合绑定校验因子 |
| `elder_display_name` | string，1..20 | 是 | 老人档案称呼 |
| `elder_mobile_number` | string | 是 | 创建和识别老人档案使用 |
| `relationship` | enum | 是 | `CHILD\|RELATIVE\|CAREGIVER\|OTHER`，不提供“配偶”选项 |
| `emergency_contact` | boolean | 是 | 是否申请紧急联系人权限；最终权限由服务端决定 |
| `client_request_id` | UUID | 是 | 客户端生成，作为幂等键 |

### 3.2 老人端绑定资料

| 字段 | 类型 | 必填 | 说明 |
|---|---|---:|---|
| `elder_display_name` | string，1..20 | 是 | 老人设备本地称呼 |
| `family_mobile_number` | string | 绑定时是 | 必须是生成绑定码的家属手机号 |
| `binding_code` | 6 位数字字符串 | 绑定时是 | 中台生成的一次性绑定码，保留前导零 |
| `sharing_consent` | boolean | 绑定时必须为 `true` | 同意共享提醒确认、报平安和紧急事件等授权摘要 |
| `device_id` | UUID/string | 是 | Android 安装生成的稳定设备 ID，不使用硬件序列号 |
| `device_name` | string | 否 | 便于家属识别设备，不参与安全判断 |
| `client_request_id` | UUID | 是 | 客户端生成，作为幂等键 |

Android 当前按 11 位中国大陆手机号做格式预检。中台仍必须统一完成号码规范化和有效性验证，并兼容 OpenAPI 声明的 `+86`/`0086` 输入。数据库比较、唯一索引和限流均使用规范化后的号码，响应与日志只返回掩码。

## 4. 推荐服务端流程

```text
家属直接注册（轻量联调版不做短信验证）
  → 创建老人档案
  → 生成一次性绑定码
  → 家属把绑定码告知老人
  → 老人提交绑定码 + 家属手机号 + 设备信息 + 共享同意
  → 中台原子校验并创建 Binding
  → 签发可吊销的 device credential
  → 双端查询绑定结果
```

### 4.1 家属注册

新增或实现：

```text
POST /auth/family/register
```

当前轻量联调版本不接入短信 OTP，也不提供开发验证令牌端点。家属手机号经格式校验和规范化后直接作为账号标识，但属于用户自报信息，数据库中的 `mobile_verified_at` 保持为空，不得向客户端或业务日志宣称已验证。正式对外部署前必须重新评估冒用手机号注册风险，并按需要接入真实验证 Provider。

示例请求：

```json
{
  "display_name": "小林",
  "mobile_number": "<family-mobile>",
  "client_request_id": "uuid"
}
```

示例响应只返回账号 ID、掩码手机号和认证令牌，不回显完整手机号。

### 4.2 创建老人档案

沿用：

```text
POST /elders
```

示例请求：

```json
{
  "display_name": "王阿姨",
  "mobile_number": "<elder-mobile>",
  "relationship": "CHILD",
  "emergency_contact": true,
  "client_request_id": "uuid"
}
```

服务端必须验证当前家属账号对新档案的创建权限。老人手机号在同一租户/业务规则下发生重复时，应返回可处理的冲突错误，不能静默合并两个老人档案。

### 4.3 生成绑定码

沿用：

```text
POST /bindings/codes
```

请求必须使用已认证的家属 access token：

```json
{
  "elder_id": "uuid",
  "client_request_id": "uuid"
}
```

示例响应：

```json
{
  "binding_code": "012345",
  "expires_at": "2026-07-16T02:10:00Z",
  "elder_id": "uuid",
  "family_mobile_masked": "138****8000"
}
```

生成规则：

- 使用密码学安全随机源生成 6 位数字字符串；
- 明文只在创建响应中返回一次，数据库只保存带服务端密钥/盐的安全摘要；
- 必须保留前导零；
- 默认有效期建议 10 分钟，配置值必须在响应中通过 `expires_at` 明确；
- 一次性使用，绑定成功后立即作废；
- 家属可主动重新生成或撤销，重新生成后旧码立即失效；
- 生成、撤销和使用均写审计记录，但审计日志不得包含明文绑定码或完整手机号。

### 4.4 老人设备联合绑定

沿用：

```text
POST /devices/bind
```

示例请求：

```json
{
  "binding_code": "012345",
  "family_mobile_number": "<family-mobile>",
  "elder_display_name": "王阿姨",
  "sharing_consent": true,
  "device_id": "android-installation-uuid",
  "device_name": "王阿姨的手机",
  "client_request_id": "uuid"
}
```

联合校验必须全部满足：

1. 家属手机号格式有效并能规范化；
2. 绑定码存在、未过期、未使用、未撤销；
3. 绑定码所属家属账号的规范化手机号与请求手机号一致；
4. 绑定码关联的老人档案仍有效；
5. `sharing_consent == true`；
6. 设备没有与冲突老人档案绑定，或请求符合受控的重新绑定规则；
7. 当前尝试没有超过设备、手机号和网络来源的限流阈值。

成功响应：

```json
{
  "binding_id": "uuid",
  "elder_id": "uuid",
  "family_account_id": "uuid",
  "family_mobile_masked": "138****8000",
  "relationship": "CHILD",
  "permissions": ["VIEWER", "HELPER", "EMERGENCY_CONTACT"],
  "device_credential": "opaque-secret",
  "bound_at": "2026-07-16T02:01:00Z"
}
```

消费绑定码、创建绑定关系和签发 device credential 必须在同一数据库事务中完成。相同 `client_request_id` 重试应返回同一业务结果，不得创建重复绑定。

## 5. 状态机

```text
FamilyDraft
  → FamilyRegistered
  → ElderProfileCreated
  → CodeActive
  → Bound | CodeExpired | CodeRevoked

ElderUnbound
  → VerifyingCodeAndFamilyMobile
  → Bound | VerificationFailed | RateLimited
```

客户端只有收到 `/devices/bind` 成功响应并安全保存 device credential 后，才显示“已绑定”。网络超时、未知结果或 WebSocket 消息均不能单独作为绑定成功依据；超时后应通过幂等请求或 `GET /bindings` 查询最终状态。

## 6. 错误码与老人端文案

| HTTP | `error.code` | 客户端建议文案 |
|---:|---|---|
| 400 | `INVALID_MOBILE_FORMAT` | “手机号格式不正确，请检查后重试。” |
| 400 | `SHARING_CONSENT_REQUIRED` | “绑定前，请先确认共享范围。” |
| 404/400 | `BINDING_CREDENTIALS_INVALID` | “手机号或绑定码不正确，请重新检查。” |
| 410 | `BINDING_CODE_EXPIRED` | “绑定码已过期，请让家人重新生成。” |
| 409 | `BINDING_CODE_USED_OR_REVOKED` | “绑定码已失效，请让家人重新生成。” |
| 409 | `DEVICE_BINDING_CONFLICT` | “这部手机已绑定其他档案，请先联系家人处理。” |
| 429 | `BINDING_ATTEMPTS_EXCEEDED` | “尝试次数较多，请稍后再试。” |
| 503 | `SERVICE_TEMPORARILY_UNAVAILABLE` | “暂时无法连接，请稍后重试。” |

为避免枚举家属手机号，手机号不存在、手机号与绑定码不匹配、绑定码不存在等情况统一返回 `BINDING_CREDENTIALS_INVALID`，响应不得说明哪一项错误。

## 7. 安全、隐私与限流

- 所有接口只允许 HTTPS；
- 当前手机号未经真实性验证，只适用于本地开发和受控联调，不应据此授予高风险权限；
- Authorization、完整手机号、验证码、绑定码和 device credential 不进入日志、错误堆栈、事件 Payload 或崩溃报告；
- 完整手机号按敏感字段保存，API 默认只返回掩码；
- device credential 使用高熵随机值，服务端只保存可验证摘要并支持吊销；
- `/devices/bind` 至少按设备 ID、规范化家属手机号和网络来源联合限流；建议单组凭据 10 分钟最多 5 次失败；
- 比较绑定摘要时使用避免时序泄漏的验证方式；
- 服务端不接受客户端自报权限，`permissions` 由关系、紧急联系人选择和服务端策略确定；
- 老人手机号不得用于推断健康状态，也不得默认授权其他家属访问。

## 8. 幂等与数据约束

- 家属注册、老人档案创建、绑定码生成和设备绑定均接受 `Idempotency-Key`，或将 `client_request_id` 映射为幂等键；
- 同一 actor 下的幂等键建立唯一约束；
- 同一设备只能有一个当前有效的老人设备凭证，重新绑定必须显式撤销旧凭证；
- 同一绑定码只能成功消费一次；
- 所有时间使用 UTC ISO 8601；
- 手机号必须以规范化值建立索引，展示值与规范化值分离；
- 绑定关系至少保存 `binding_id`、`elder_id`、`family_account_id`、关系、权限、创建时间、撤销时间和审计来源。

已存在有效绑定时的换机或本地凭据丢失恢复已通过受控规则实现，不由首次绑定规则隐式接管其他老人档案。重新生成绑定码与设备凭据原子轮换的完整流程见 [`device-rebinding-requirements.md`](device-rebinding-requirements.md)。

## 9. OpenAPI 与自动化验收

中台交付至少覆盖：

1. 家属可直接注册，手机号被规范化且响应不回显完整号码；
2. 绑定码为 6 位字符串并保留前导零；
3. 正确绑定码 + 错误家属手机号绑定失败；
4. 错误绑定码 + 正确家属手机号绑定失败；
5. 正确组合可绑定并签发 device credential；
6. 绑定码过期、撤销或已使用后均不能再次绑定；
7. 相同幂等键重试不创建重复账号、老人档案或绑定；
8. 并发消费同一绑定码只有一个请求成功；
9. 超过失败阈值返回 429；
10. 响应、日志和审计记录不包含明文绑定码、完整手机号、验证码或 device credential；
11. 家属不能查询未绑定老人，老人设备凭证不能访问其他老人；
12. `GET /bindings` 能让双端在超时后恢复最终绑定状态。

## 10. Android 后续联调项

- 为上述端点建立 DTO、Repository 和可替换 Mock；
- 家属端展示真实绑定码、有效期、重新生成和撤销入口；
- 老人端提交手机号与绑定码后展示提交中、失败、限流和已绑定状态；
- device credential 通过 Android Keystore 支持的本地加密方案保存；
- App 重启后通过安全本地状态和 `GET /bindings` 恢复角色及绑定结果；
- 技术错误码映射为本文中的简短中文，不向老人展示 HTTP 状态码或服务端堆栈。
