# W16 角色内容与撤权（候选，默认关闭）

本组只描述角色内容的读取与发放。没有新的 HTTP 路由。`GET /api/rendezvous/tickets/{ticketId}/secret` 仍由既有入池控制器序列化 `RendezvousRoleSecretService.Projection`。本文件不是生产接通说明，也不给剧情、目标或角色文案。

## 不变量

- 不调用 `RendezvousConsentService.read`。那会另开一条同意事务。读取只进入角色存储自己的那一次 consent guard 事务。
- 在这条事务里先确认票据属于调用者。陌生人与不存在的票据都是 `NOT_FOUND`。
- 然后调用事务内安全端口。端口未接线时使用 `RendezvousRoleContentGuard.Closed`，直接 `SECRET_UNAVAILABLE`，不写发放记录。
- 本组端口保持不变：

```text
void recheck(RendezvousConsentService.Transaction tx, long actor, String ticketId, long now)
```

- A 组在 `37b4b3459ea946d301896abac3ad507e117f3bf6` 冻结的调用，以及本组适配器的精确映射（同一 `tx`，不新开事务，不调用 `consent.read`）：

```text
OngoingVerdict verdict = groupA.verifyParticipants(tx, actor, ticketId, null, null, true, now);
// A 的 Rejection 原样抛出。其它运行时失败变成 SECRET_UNAVAILABLE，不保留 cause。
// 只有 verdict 的运行时类型是 OngoingVerdict，且 safe && owned && mutual，才通过。
// null、其它类型、以及任一标志不为真，都是 SECRET_UNAVAILABLE。
// 票据不存在或属于陌生人时，A 返回 safe=false 且 owned=false，本组不把它改写成 NOT_FOUND。
```

Java 接线是方法引用。生产构造不要使用它：

```text
new RendezvousRoleContentGuardAdapter(groupA::verifyParticipants)
```

服务在守卫通过之后仍调用 `requireOwnedMutual` 读取 match 行，用来选择角色侧。缺行、非属主、未双方同意或已到期在这里拒绝。它不代替 A 的资格、拉黑和地点判断。

服务构造仍使用 `RendezvousRoleContentGuard.Closed`。适配器类存在，但没有实例被注册。内容发布权威是另一项，状态为 NOT_CONNECTED，不因守卫类型可编译而视为已接通。

- 安全核验通过后才解析内容。内容只来自同一事务里可见的发布行：绑定状态必须是 `BOUND`，发布状态必须是 `ISSUED`，并且只取调用者这一侧的 `role_key` 正文。缺绑定、错 match、错角色、下线、撤销、空正文都是 `CONTENT_UNAVAILABLE`。
- 不生成剧情，不把随机字符串当作线索。没有发布行时结果是不可用。
- 发放记录只保存目标、发布版本、角色键和 `secretRef`。`secretRef` 形如 `r.` 加 22 位 URL-safe 令牌，只用于关联本人发放，没有剧情语义，不能填进 `roleClue`。
- `roleClue` 是当前仍为 `ISSUED` 的本人角色正文。重试返回同一个 `secretRef`、同一个目标和同一个发布版本。发布变为 `OFFLINE` 或 `REVOKED`，或绑定变为 `REVOKED` 之后，不再返回旧正文。
- 投影、`toString` 和异常消息不含对方正文、对方 `secretRef`、对方身份或地点。异常不携带 cause。

## 投影字段

成功时公共字段为 `ticketId`、`roleClue`、`willLead`、`goalId`、`publishVersion`、`roleKey`、`secretRef`。既有控制器会把这些字段原样放进 JSON。本组没有改控制器。`CONTENT_UNAVAILABLE` 不是控制器已列的错误码，现有分支会落到 HTTP 503 `W16_UNAVAILABLE`。

## 候选表

`chengyinhub-admin/sql/migration_w16_role_content_candidate.sql` 只建表，不插入发布、绑定或正文。空表即不可用。本文件未对任何库执行。

没有批准的发布写入者。服务和 mapper 不提供发布、下线或绑定写入。测试夹具里的正文不是产品内容。

## 尚未接通

- 守卫：A 组的 `verifyParticipants` 还没有接到本组进程。生产构造使用 `Closed`，因此即使库里后来有发布行，未接线时也不会发秘密。适配器只说明合并时的方法引用，默认不是接通。
- 发布权威：NOT_CONNECTED。与守卫分开。仓库里没有可复用的、已绑定到接头 match 的合作目标发布源。游戏会话角色线索不是这个绑定。服务和 mapper 不发布。空候选表即不可用。
