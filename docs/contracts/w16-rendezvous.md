# W16 接头人协议（候选，默认关闭）

本文件只冻结本功能的状态、错误和幂等。未批准的资格、年龄、区域、时段、频率、会面安全策略不在这里给默认值。没有 `OPEN` 配置行时，入池、配对、会面和组队交接都不可用。本文不是生产接通说明。

## 不变量

- 只有本人能入池。请求体只有 `pool` 和 `eligibilityVersion`。有效期来自 `ticket-ttl` 配置，不来自请求。
- 配对只由服务端 `RendezvousPoolScheduler` 做。没有对外的 propose 接口，不能替对方入池，也不能提交坐标。
- 双方各自 `accept`。重复接受不改原来的时间戳。版本不一致返回 `STALE_PROPOSAL`。
- 陌生人读、接受、取消、看线索都是 `NOT_FOUND`，和票据不存在相同。
- 角色线索只在双方接受后发给本人。投影只有 `ticketId`、`roleClue`、`willLead`。
- 会面口令是关联用的 challenge，不是到场证据。确认请求只带 `challengeId`。`scanned`、`doubleClick`、经纬度不是合法字段，也不能当成成功。
- 组队只经既有 `IPlayTeamService.create` / `join`。交接账本的幂等键是 match id。未知结果记 `RECOVERY_REQUIRED`，不自动再调用创建。`GET .../handoff` 只读账本。
- 拉黑端口 `RendezvousImBlockMutation.insert` 和举报端口 `RendezvousReportStop.report` 只停这对共同提案。未改 `ImServiceImpl.block`、`ImServiceImpl.report`、`PlayTeamServiceImpl`。

## 默认关闭

控制器带 profile `w16-consent-contract`，并且要显式存在对应的 service bean。应用默认 profile 是 `druid`。没有 Bean、没有菜单权限、没有匿名路由。

策略维度：`eligibility-source`、`age`、`region`、`time-window`、`frequency`（必须有正的 `max_contacts`）、`meeting-safety`（必须有 `public_place`）、`ticket-ttl`（必须有正的毫秒数）。缺行、非 `OPEN`、或数值为空，一律拒绝。
