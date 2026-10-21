# W20 CITY 棋盘发布候选合同

本组只做平台草稿、有权限的审核、审批哈希绑定内容哈希后的冻结发布，以及玩家按区域读取同一冻结快照。它不是模板审核，不接积分支付或发奖，不建影子钱包，不实现 21 项经营动作。

候选接口没有写入 `SecurityConfig`，响应带 `candidate: true` 和 `transport: NOT_APPROVED`。匿名不可用。本文件不批准生产 transport，也不执行 DDL。

## 依赖

- 登录与方法权限：现有 `SecurityUtils`、`@PreAuthorize`、`PermissionService`（`@ss.hasPermi`）。
- 返回包装：`AjaxResult`。玩家读的 HTTP 状态与 body `code` 对齐 iOS `CITY_PLAYER_READ_V1`。
- 持久化端口：`CityBoardMapper` 与 `mapper/business/CityBoardMapper.xml`。表只存在于 `chengyinhub-admin/sql/migration_w20_city_board_candidate.sql`，本次不执行。
- 外部真源：`CityBoardSourceProbe` 生产实现固定返回 `UNAVAILABLE`。POI、商户、主题角色、私人 home、虚拟经营资格、地点许可都没有在本组接真表。
- 不依赖也不修改：模板/主题发布、`PointsAwardService`、商户 citynode、W19、`AppSession`、小程序、`pom`、`application*.yml`、workflow。

## 权限

权限串只用于方法鉴权，代码不比较角色名。候选 SQL 不插入 `sys_menu` 或 `sys_role_menu`，因此默认无人持有。

| 权限 | 入口 |
| --- | --- |
| `cityboard:draft:save` | `POST /business/cityboard/draft/save` |
| `cityboard:draft:reopen` | `POST /business/cityboard/draft/reopen` |
| `cityboard:draft:cancel` | `POST /business/cityboard/draft/cancel` |
| `cityboard:draft:query` | `GET /business/cityboard/draft/{id}`，仅所有者 |
| `cityboard:review:validate` | `POST /business/cityboard/review/validate`，可读取待审草稿 |
| `cityboard:review:reject` | `POST /business/cityboard/review/reject` |
| `cityboard:publish:freeze` | `POST /business/cityboard/publish` |
| `cityboard:operation:query` | `GET /business/cityboard/operation/{requestId}` |
| `cityboard:player:read` | 冻结权限矩阵里的区域范围，不是调用者角色 |

所有者来自登录用户，不来自 JSON。审核人必须不是草稿所有者。请求体里的 `approvalHash`、`approved`、`decision`、`owner`、`role` 直接拒绝。

## 状态

`DRAFT` 可保存。内容变化会增加版本并清掉审批。`validate` 只在发布校验通过后写成 `APPROVED`，审批哈希为：

`SHA-256(contentHash + "\n" + draftId + "\n" + version + "\n" + approverUserId + "\n" + requestId)`

`publish` 重新计算该哈希，并再次跑发布校验。通过后把草稿标为 `FROZEN`，插入不可变 release，并把该区域的当前指针切到新 release。旧 release 保留且 `current_flag=0`。之后改草稿或重开，不会改旧 release。

`reject` 与 `cancel` 写入状态和原因。`reopen` 把被拒或取消的草稿开回 `DRAFT`，或从某个 release 复制出新草稿。同一 `requestId` 加同一规范请求体返回原结果；请求体不同返回 `DUPLICATE_REQUEST`，原结果仍可按 key 读回。版本不符返回 `VERSION_CONFLICT` 且不写内容。

## 字段

平台规则白名单：`regionId`、`gameId`、`boardId`、`title`、`season`、`seasonSettlement`、`rules`、`dice`、`pointCost`、`serviceFee`、`protection`、`safety`、`pointRoles`、`points`、`tasks`、`locationPermits`、`permissionMatrix`。

玩家策略键（`strategy`、`join`、`capture`、`reward`、`wallet` 等）拒绝。商户经营事实键（`hours`、`capacity`、`landRight`、`ownership`、`merchantName`）拒绝。费率、概率、安全阈值键（`feeRate`、`probability`、`threshold`）拒绝，也不填默认值。费用、点数成本和预算必须由调用方给出整数；缺字段、负数、币种无法比较或费用超过同一币种预算，发布失败。

`PLATFORM_VIRTUAL` 点位不声称土地或商户。`POI`、`MERCHANT`、`PRIVATE_HOME` 必须有引用和地点许可。`THEME_ROLE`、`VIRTUAL_OPERATION` 必须有引用。引用由 probe 判定：`UNAVAILABLE`、`NOT_FOUND`、`DENIED` 都拒绝发布。生产 probe 不会返回 `FOUND`。

## 玩家读

对齐 iOS `Core/CityPlayerReading.swift`：

- `GET /api/city-game/current?regionId=`
- `GET /api/city-game/participation?regionId&boardId&seasonId&rulesReleaseId&revision`
- `GET /api/city-game/points` 使用同一组查询参数

没有当前 release 时，`current` 返回 HTTP 200，`data.status=NO_CURRENT_BOARD`，不带 `board`、`participationStatus`、`pointsStatus`、`participation`、`points`。

有当前 release 时，`board` 只含 iOS `CityReadBoard` 字段，`lifecycle=FROZEN`，`rulesHash` 是冻结内容哈希。`participationStatus` 与 `pointsStatus` 都是 `UNAVAILABLE`。本组没有参与账本，也没有只读积分投影；直接调用另两个接口且快照一致时返回 HTTP 503，`CITY_PARTICIPATION_UNAVAILABLE` 或 `CITY_POINTS_UNAVAILABLE`，不返回 `JOINED`，也不返回空点位数组。快照不一致返回 HTTP 409 `CITY_SNAPSHOT_CHANGED`。

## 未运行

- 候选 SQL 未对任何数据库执行。
- MyBatis XML 未对 MySQL 执行。
- 冻结与插入之间的事务回滚未做崩溃注入。
- iOS Swift 合同测试未在本环境运行。
- 未做生产发布、DDL、支付或合并。
