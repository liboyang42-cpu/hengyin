# W16 资格与配对限额（候选，默认关闭）

本文件只描述本组的资格事实边界和配对准入记账。未批准的年龄、区域、时段、频率 scope 和窗口不在这里给数值。没有当前规则版本、完整规则、本人事实或额度行时，入池判定和配对记账都拒绝。本文不是生产接通说明。

## 资格

- 客户端 `eligibilityVersion` 只做和服务端当前版本的一致性检查。服务端当前版本来自 `w16_eligibility_current`。没有这行，或 `version_id` 为空，已接通的目录拒绝。
- 旧版本上的 `OPEN` 行不能被客户端点名后生效。票据上写入的版本是服务端当前版本。
- `w16_policy_version` 里四行 `OPEN` 不够。已接通目录还要读 `w16_eligibility_rule` 和 `w16_eligibility_fact`。规则缺 `min_age`、区域或时间两端，拒绝。事实缺年龄、区域、版本不一致或 `expires_at <= now`，拒绝。
- `max_age` 为空只表示这条已批准规则没有上限，不是一个写死的年龄。迁移不插入任何规则。
- 时间窗口是规则上的绝对毫秒区间 `[window_start, window_end)`。代码不换算时区，也不填每天的默认时段。
- 一人一票的持续有效性走 `RendezvousConsentService.verifyParticipants`。它不读接触计数。

## 没有接通的事实来源

下列现有数据不是本组的权威资格，映射不读它们，也没有把它们抄进 `w16_eligibility_fact` 的写入器：

| 路径 | 原因 |
| --- | --- |
| `ums_member.birthday` | 会员自填生日。圈子夜间和商家洞察在用，没有复核版本 |
| `ums_member.area_id` | 自填所在地 |
| `ums_member_identity` | 实名密文，没有年龄、区域或规则版本结论 |
| `ums_member_address` | 收货地址 |
| `member_interest_profile.city_code` | 兴趣城市 |

`MyBatisRendezvousCatalog` 不传资格 mapper 时是接通且全部拒绝，不是全通过。`Catalog.facts()` 的默认是 `closed()`。显式返回 `unwired()` 也同样失败关闭：`eligible` 为 false，`requireClientVersion` 拒绝，`ttlVersion` 不采用客户端版本。四行 `OPEN` 不能补上这个缺口。测试目录若要表达「这些 actor 在夹具版本上已有事实」，必须自己覆盖 `facts()`。

## 配对限额

- 新配对准入只走 `RendezvousPoolScheduler.pairOnce`，再进 `RendezvousConsentService.propose`。propose 在同一 guard 事务里保存 match 之后必定调用 `Admission.charge`。`now` 是该事务内读取的服务端时刻。调度器挑选等待票的时刻不参与额度窗口。任一侧失败，match 和两侧计数一起回滚。
- 三参构造 `RendezvousConsentService(Store, Safety, Clock)` 没有接入额度。它的 Admission 在同一事务里抛 `POLICY_CLOSED`，match 和 reservation 随事务回滚。要完成新配对，调用方必须显式传入会记账的 Admission。
- 同一 `match_id + actor_id` 只记一次。
- 额度行在 `w16_contact_quota`。缺行、非 `OPEN`、scope 空白、`window_millis` 非正、`max_contacts` 非正，都是 `POLICY_CLOSED`。代码不把缺省窗口当成终身累计。
- 计数窗口是 `(now - window_millis, now]`，并且只数同一 pool 和同一 scope。
- `w16_contact_counter` 仍留在旧候选迁移里。本组配对不再写入它，也不再用它判断已预留 match 是否继续有效。
- `Safety.compatible` 对已接通策略只表示频率规则行仍然有效。它不再比较累计次数。已预留 match 的 read/accept 不因这次配对自己的计数变成 `SAFETY_STOPPED`。下一次新配对在额度用尽时拒绝。

## 组合根

`RendezvousPairingComposition` 上的属性 `chengyinhub.w16.pairing` 默认 `false`，不写入 `application*.yml`。属性不是 `true` 时不注册 Bean。属性为 `true` 时，`formal` 校验同一个 DataSource、SqlSession 和事务管理器，用 `MyBatisRendezvousConsentStore.Mode.FORMAL` 把 `ledger::charge` 接到四参 `RendezvousConsentService`，并把同一 `MyBatisRendezvousWaiting` 接到 `RendezvousPoolScheduler`。它不插入规则、事实或额度，也不自己调用 `pairOnce`。

资格只读 `w16_eligibility_fact` 的已复核年龄、地区和有效期，加上 `w16_eligibility_rule` 的时段。`ums_member` 的生日、姓名和地区不是资格证明。缺规则、缺事实或缺额度时入池或配对拒绝。

稳定构造：`RendezvousConsentService(Store, Safety, Clock)` 仍拒绝新配对。`RendezvousConsentService(Store, Safety, Clock, Admission)` 给 C 和 H。`terminateCurrentMatch(Transaction, long, long)` 给 H。`verifyParticipants` 的带 actor 重载给 B 和 C。

`ApiRendezvousActiveEntryController` 在属性打开且不处于 `w16-consent-contract` profile 时提供 join、current、读票、接受、拒绝和撤回。丢失的 join 用 current 读回同一张票。重复接受不增加回执。拒绝、撤回和超时释放 reservation，不删除已经记下的额度事件。未入池的人不能读到地点。

`evaluation` 仍是隔离试验，不是 Spring Bean。

## 给 B/C/H/I/J 的内部操作

都在调用方已经持有的 guard 事务里执行，不调用 `Store.transaction` 或 `read`。

- `verifyParticipants(tx, ticketId)` 不证明归属，也不要求双方接受。有 actor 的调用方用下面这一条。
- `verifyParticipants(tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now)`。`now` 是事务里已经取到的服务端时刻。空的 match 或 peer 表示没有提供该约束；提供了但不一致则失败关闭。`requireMutualAccepted` 为真时必须双方都有接受回执。不读接触计数，不写库。
- `terminateCurrent(tx, actor, peer, ticketId, reason, now)`。`EXIT` 要求 peer 为空且票据属于 actor，双方预约一起释放。`BLOCK` 与 `REPORT` 只停止双方当前共同 match，持久状态都是 `SAFETY_STOPPED`，不另存原因码。已终止再调用不写第二笔。缺一侧时在任何保存之前失败。
- H 使用 `void terminateCurrentMatch(Transaction tx, long actor, long other)`。它在已打开的 guard 事务里把两人当前共同 match 写成 `SAFETY_STOPPED`，两侧预约一起释放。没有共同 active match 时不写。再调用不增加 version。解除拉黑不会把这条 match 写回 `PROPOSED` 或 `MUTUAL_ACCEPTED`。

公开 HTTP 的 join、accept、cancel 路径不变。
