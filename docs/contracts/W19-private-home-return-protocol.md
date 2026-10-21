# W19 私人据点回家协议 v1（冻结）

协议标识：`private-home.return.v1`。本文冻结的是本轮**新协议**，不是既有 GET/PUT/DELETE。iOS 对接以本文字段、错误码和幂等规则为准。未写入本文的字段视为非法，服务端拒绝且不回显原文。

本文件不包含生产数值。半径、停留、新鲜度、精度上限、冷却、proof TTL、位置证据保留 TTL、资源 scope/cap/type 都是必填版本化配置；缺任一值时对应路径保持关闭，不得用代码里的数字顶上。权威实现可装配，默认不注册、不接通。

## 1. 开关（默认关闭）

| 开关 | 缺省 | 作用 |
| --- | --- | --- |
| `app.private-home.owner-api-enabled=true` | 不注册 HTTP | 才暴露下面的路由 |
| `app.private-home.authority-enabled=true` | 不注册权威与资源适配器 | 才装配权威；配置不完整时仍拒绝 |

两个开关都未写入 `application.yml`。默认关闭不是生产接通。

## 2. 保留的既有合同（非本轮新协议）

路由仍是 `/api/native/home`。身份只来自当前登录会话，请求体里的 `ownerId` 一律拒绝。

| 方法 | 作用 | 幂等键 | 冲突比较字段 |
| --- | --- | --- | --- |
| GET | 读本人据点 | 无 | 无 |
| PUT | 设置/改点 | `(owner, requestId)` | `operation=SET` 与 `expectedVersion` |
| DELETE | 删除并清坐标 | `(owner, requestId)` | `operation=DELETE` 与 `expectedVersion` |

PUT 体：`requestId`、`expectedVersion`、`label`（1–40，无控制字符）、`latitude`、`longitude`（十进制，标度 ≤ 6，范围合法）、`datum=WGS84`。未知字段拒绝。

DELETE 体：`requestId`、`expectedVersion`。未知字段拒绝。

成功时 HTTP 200，`AjaxResult.data` 为：

```json
{"requestId":"…","decision":"SAVED|DELETED|VERSION_CONFLICT|HOME_CHANGE_COOLDOWN|PLACE_NOT_ALLOWED","version":0}
```

同一 `requestId` 再次提交：比较字段一致则返回**原收据**，不应用第二份坐标或标签。比较字段不一致则 `REQUEST_CONFLICT`。坐标和标签不入库、不做指纹（避免把位置摘要写进收据）。

写路径使用据点行版本锁（`expectedVersion` 对不上则 `VERSION_CONFLICT`）和同一本地事务。删除会把坐标、标签、datum 清空，版本加一，并把本人仍为 READY 的 proof 标为 REVOKED、抹掉权威引用，同时删掉本人的位置证据索引。删除不依赖恢复配置。

GET 只返回本人据点。响应 `Cache-Control: no-store`、`Pragma: no-cache`。响应不含 `ownerId`。

以下路径**故意不存在**（保持 404），以免把新协议伪装成旧接口：

- `POST /api/native/home/verify`
- `POST /api/native/home/restore`

## 3. 新协议：手动到家验证

`POST /api/native/home/return-verifications`

这是一次性手动核验，不建立后台轨迹、不注册定时追踪。

```json
{
  "requestId": "1-64 位 [A-Za-z0-9._:-]",
  "expectedVersion": 0,
  "scope": "1-64 位同一字符集",
  "evidenceHandle": "1-128 位同一字符集",
  "observation": {
    "latitude": "0.000000",
    "longitude": "0.000000",
    "datum": "WGS84",
    "accuracyMeters": 0,
    "observedAtEpochMillis": 0,
    "dwellMillis": 0,
    "permission": "GRANTED|DENIED",
    "network": "KNOWN|UNKNOWN"
  }
}
```

未知字段（含塞进 observation 的字段）拒绝为 `HOME_REQUEST_INVALID`。

判定顺序（命中即停，不继续装成成功）：

1. 请求形态非法 → `HOME_REQUEST_INVALID`
2. 权威 Bean 不存在 → `HOME_AUTHORITY_UNAVAILABLE`
3. 版本化位置/资源策略未启用或任一必填值缺失、越界、类型不是 STAMINA/ENERGY、scope 像资金/赞助/购买权益 → `HOME_RULES_UNAVAILABLE` 或 `RESOURCE_NOT_AUTHORIZED`（见第 7 节）
4. `permission=DENIED` → `HOME_LOCATION_DENIED`（不再读取坐标）
5. `network` 不是 KNOWN/UNKNOWN → `HOME_REQUEST_INVALID`；`UNKNOWN` → `HOME_NETWORK_UNKNOWN`
6. 精度缺失、为负或大于配置上限 → `HOME_ACCURACY_INSUFFICIENT`
7. 观测时间早于新鲜度窗口，或超前当前时钟 5 秒以上 → `HOME_LOCATION_STALE`
8. 停留短于配置 → `HOME_DWELL_INSUFFICIENT`
9. 坐标非法 → `HOME_POINT_INVALID`；与据点的球面距离大于半径（边界上算在内）→ `HOME_OUT_OF_RADIUS`
10. 距本人上一次该 scope 的 proof 签发未满验证冷却 → `HOME_VERIFY_COOLDOWN`
11. 通过后才进入既有 proof 签发。据点非 ACTIVE、版本不符、未到生效时间，沿用既有码：`HOME_VERSION_INVALID`、`HOME_NOT_EFFECTIVE`。权威不发达到凭证 → `ARRIVAL_NOT_VERIFIED`。

成功时 `data`：

```json
{
  "requestId": "…",
  "decision": "VERIFIED",
  "proofId": "…",
  "scope": "…",
  "status": "READY",
  "issuedAt": 0,
  "expiresAt": 0,
  "version": 0
}
```

成功响应不含纬度、经度、标签、datum、`ownerId`、`grantId`、`evidenceHandle`。

幂等键 `(owner, requestId)`。已落库字段只比较 `expectedVersion`（即 proof 上的 homeVersion）和 `scope`。不一致 → `REQUEST_CONFLICT`。一致 → 返回原 proof，不重新验证、不第二次签发、不把第二份观测写进去。观测原文和 evidenceHandle 不落库。

同一事务内写入 proof，并写入位置证据索引（只有 owner、proofId、到期时刻，没有坐标）。proof 行版本/账户不在本接口改动。

## 4. 新协议：按 scope 恢复资源

`POST /api/native/home/resource-restores`

```json
{"requestId":"…","proofId":"…","scope":"…"}
```

未知字段拒绝。恢复在**同一个本地数据库事务**里完成：锁定据点行、锁定该 owner+scope 的资源行、原子消费 READY proof、账户 CAS（`version` 匹配才把 current 写成 cap）、写入 receipt。receipt 同时是账本事件。任一步失败或提交前权威/策略/过期发生变化，整笔回滚，proof 仍为 READY，余额不变，不留下半笔 receipt。

成功或已记录的拒绝都返回 `data`：

```json
{
  "requestId": "…",
  "receiptId": "…",
  "proofId": "…",
  "scope": "…",
  "decision": "RESTORED|PROOF_INVALID|PROOF_CONSUMED|PROOF_EXPIRED|PROOF_REVOKED|PROOF_SCOPE_CHANGED|HOME_VERSION_INVALID|HOME_NOT_EFFECTIVE|RESTORE_COOLDOWN",
  "version": 0,
  "beforeValue": null,
  "afterValue": null,
  "amount": null
}
```

只有 `RESTORED` 带 before/after/amount。响应同样不含据点坐标、标签、ownerId、grantId。

幂等键 `(owner, requestId)`。已落库则比较 `proofId` 与 `scope`。不一致 → `REQUEST_CONFLICT`。一致 → 返回原 receipt，**不再入账**。这就是丢响应后的重放。

资源行必须已经存在于 `player_private_home_resource`。本协议不创建账户、不发奖、不把金钱、赞助预算、购买权益或别的资金表映射成体力。scope 或 type 命中资金/赞助/购买权益词表时直接 `RESOURCE_NOT_AUTHORIZED`。配置里的 cap 与行上的 cap 不一致时同样拒绝，不改写 cap。

## 5. 新协议：本人结果读回

| 方法 | 路由 |
| --- | --- |
| GET | `/api/native/home/return-verifications/{requestId}` |
| GET | `/api/native/home/resource-restores/{requestId}` |

只按当前会话 owner 查询。没有记录时 `HOME_RESULT_ABSENT`（错误体，不是 `decision=RESTORED` 的成功）。别人的 requestId 同样是 `HOME_RESULT_ABSENT`，不返回对方数据。读回不消费 proof、不改余额。`Cache-Control: no-store`。

丢响应时客户端应先 GET 读回；读回命中则使用原 proof/receipt。只有确认不存在时才允许用**同一个** requestId 重放 POST，由第 3、4 节的幂等保证只生效一次。

## 6. 错误体

业务拒绝使用现有 `AjaxResult.error`：HTTP 200，`code=500`，`msg` 为下表符号码。不回显 SQL、坐标、供应商原文。无法识别的异常压成 `HOME_UNAVAILABLE`。

符号码（新协议在既有码之外追加的用 ★ 标出）：

`HOME_REQUEST_INVALID`、`HOME_UNAUTHORIZED`、`HOME_AUTHORITY_UNAVAILABLE`、`HOME_RULES_UNAVAILABLE`、`HOME_UNAVAILABLE`、`HOME_TRANSACTION_REQUIRED`、`HOME_WRITE_CONFLICT`、`HOME_POINT_INVALID`、`HOME_LABEL_INVALID`、`HOME_VERSION_INVALID`、`HOME_NOT_EFFECTIVE`、`HOME_CHANGE_COOLDOWN`、`HOME_VERIFY_COOLDOWN` ★、`HOME_LOCATION_DENIED` ★、`HOME_NETWORK_UNKNOWN` ★、`HOME_ACCURACY_INSUFFICIENT` ★、`HOME_LOCATION_STALE` ★、`HOME_DWELL_INSUFFICIENT` ★、`HOME_OUT_OF_RADIUS` ★、`HOME_RESULT_ABSENT` ★、`HOME_RETENTION_DISABLED`、`HOME_RETENTION_POLICY_REQUIRED`、`HOME_RETENTION_POLICY_CHANGED`、`HOME_RETENTION_CLOCK_INVALID`、`PLACE_NOT_ALLOWED`、`VERSION_CONFLICT`、`REQUEST_CONFLICT`、`AUTHORITY_CHANGED`、`ARRIVAL_NOT_VERIFIED`、`RESOURCE_NOT_AUTHORIZED`、`RESOURCE_ADAPTER_UNAVAILABLE`、`RESOURCE_ACCOUNT_UNAVAILABLE`、`RESOURCE_WRITE_FAILED`。

`decision` 字段里还会出现 `SAVED`、`DELETED`、`VERIFIED`、`RESTORED`、`PROOF_INVALID`、`PROOF_CONSUMED`、`PROOF_EXPIRED`、`PROOF_REVOKED`、`PROOF_SCOPE_CHANGED`、`RESTORE_COOLDOWN`。这些是收据结论，不是异常码。

## 7. 必填版本化配置与待决

前缀 `app.private-home.location-policy.`。`enabled` 不为 true，或下表任一值缺失/非法，新验证与依赖该策略的权威调用保持关闭。

| 编号 | 键 | 约束 | 缺失时 | 本次是否写入生产值 |
| --- | --- | --- | --- | --- |
| D1 | resource-scope | 令牌，且不得像资金/赞助/购买权益 | 关闭 | 否 |
| D2 | resource-cap | 正整数 | 关闭 | 否 |
| D3 | resource-type | 仅 `STAMINA` 或 `ENERGY` | 关闭 | 否 |
| D4 | radius-meters | > 0 | 关闭 | 否 |
| D5 | dwell-millis | ≥ 0 | 关闭 | 否 |
| D6 | freshness-millis | > 0 | 关闭 | 否 |
| D7 | accuracy-max-meters | > 0 | 关闭 | 否 |
| D8 | verify-cooldown-millis | ≥ 0 | 关闭 | 否 |
| D9 | change-cooldown-millis | ≥ 0 | 关闭 | 否 |
| D10 | restore-cooldown-millis | ≥ 0 | 关闭 | 否 |
| D11 | proof-ttl-millis | > 0 | 关闭 | 否 |
| D12 | location-retention-ttl-millis | > 0 | 关闭 | 否 |
| D13 | activation-delay-millis | ≥ 0 | 关闭 | 否 |
| D14 | version | 策略版本令牌 | 关闭 | 否 |
| D15 | placement-rule-version | 仅认识 `OWNER_PIN_ONLY`；区域多边形未实现 | 拒绝落点 | 否 |
| D16 | arrival-grant-mode | 仅认识 `MANUAL_OWNER_FIX`；否则不发达到凭证 | 不发 proof | 否 |

另有清理开关，缺省关闭，且没有 HTTP、没有调度器：

| 编号 | 键 | 缺失时 | 本次是否写入生产值 |
| --- | --- | --- | --- |
| D17 | `app.private-home.retention.enabled` + `version` + `expired-reference-grace-millis` | 不清理过期权威引用 | 否 |

仍未批准、本协议不实现、也不用默认值代替的产品规则：年龄、地区多边形、时段、频率、会面安全策略。金钱、赞助预算、购买权益不得映射为体力或 ENERGY。

## 8. 删除、证据索引与保留

位置证据索引表只有 `owner_id`、`proof_id`、`expires_at`。没有纬度、经度、标签或原始 evidenceHandle。

- 删除据点：同一事务清坐标、撤回 READY proof、删除该 owner 的证据索引。
- `cleanupMyExpiredReferences`：仅内部调用。保留策略不完整则直接拒绝且不写。按已批准的 grace 计算 cutoff，把到期的 READY 权威引用标为 EXPIRED 并清空 grant 引用；同时删除 `expires_at <= now` 的证据索引行。不删除据点、proof、mutation、receipt 行，因此重放、冷却和版本墓碑还在。
- 不做后台连续定位。
