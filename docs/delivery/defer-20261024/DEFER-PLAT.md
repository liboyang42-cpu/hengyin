# DEFER-PLAT（platform / API contracts / shared helpers）— 2026-10-24

分支 `chore/defer-plat-20261024` → PR #594（draft）。按优先级自上而下处理，每项一个提交。
上一轮的 DEFER 分诊与理由见 `docs/delivery/bugfix-20261023/BUG-PLAT.md`。

| id | 优先级 | 状态 | 文件 | 测试 | 说明 |
|---|---|---|---|---|---|
| B:P1-003 | P1 | FIXED | `ApiCrowdfundingController.java` | `ApiGiftControllerTagContractTest` | 赠送控制器 Swagger Tag 改为「众筹礼品赠送」，与列表控制器分开。`/api/gift` 前缀保留（已发版客户端在用；`/list` 与 `/give` 不重叠），测试锁定前缀共用、Tag 不同、子路径不重叠。 |
| B:P1-034 | P1 | FIXED | `CommunityPostLifecyclePolicy.java`、`CommunityPostMapper.xml`（仅注释） | `CommunityPostVisibilityPolicyParityTest` | `canView` 的 Javadoc 写明它是纯访问资格判定（作者/lifecycle/audience/拉黑/屏蔽），不含负反馈；SQL `visibilityPolicy` = 这些访问规则 + 负反馈过滤，两者刻意不等价，且 `canView` 没有生产调用方。测试锁定这一分工。请 owner 知悉：`selectById`（详情）也引用了 `visibilityPolicy`，所以用户点过「不感兴趣」的帖子在详情页同样读不到。这与 BUG-PLAT 里「详情页不受影响」的说法不一致；行为未改。 |
| B:P1-076 | P1 | PARTIAL | `utils/{access-gate,citynode-redeem-outcome,deregister-flow,play-run-session}.js`、`components/cy/scene-{member-invite-history,qr-citynode,qr-group-code,qr-ticket,route-content}/index.js`；两处测试沙箱接入真实 `response-shape` | `biz-ok-shared-helper-contract.test.js` | 共享 utils 与 components/cy 共 9 个文件改走 `isBizOk`（局部 helper 改为别名，行号不偏移，所以 ui-integrity 基线和 action ledger 都不漂移）。剩余约 60 个文件在 merchant/club/play/roam/IM 等域页面，归各域 lane。`utils/marketing-consent-entry.js` 一旦加 require，action ledger 就会多出 POST 接口条目，所以保持原样。 |
| C:U-039 | P1 | PARTIAL | `ApiTemplateController.java` | `TemplatePackTypeTest#unrecognizedPackTypeIsDetectedForWarningButAbsentIsNot` | 非法 `pack_type` 不再静默：记一条 WARN，返回仍是「不过滤」（`TemplatePackTypeTest` 锁定的设计，兼容将来新增形态）。改成回 400 会改变客户端可见行为 → NEEDS_OWNER。 |
| C:U-062 | P1 | FIXED | `ServiceException.java`（新增 `ALLOWED_CODES`） | `ServiceExceptionCodeWhitelistContractTest` | 业务码白名单常量 + 全仓扫描闸：所有 `new ServiceException(msg, <字面量/HttpStatus.X>)` 的码值必须在白名单内（当前 4xx/5xx 共 13 个码，含负控验证）。变量/表达式码值由运行时决定，不在本闸范围。 |
| C:U-078 | P1 | PARTIAL | 新增 `sql/migration_cms_topic_node_status_comment_20261024.sql`、`ci/migration-dependencies.json`、`CmsTopicNode.java`（Javadoc） | 迁移门禁（分句 / 依赖序 / 布局） | 新增迁移更正列注释为「0不显示 1显示」（先校验列形状，再 INSTANT 执行，幂等）。未改动 `@Excel` 表头，避免后台导出出现变化。int/tinyint 类型统一属于重构，未做。 |
| C:U-134 | P1 | NEEDS_OWNER | `ServiceException.java`（仅 Javadoc，随 U-062 提交） | — | 不带码的构造（全仓约 554 处）由捕获方兜底为 500。改成默认 4xx、或禁用不带码的构造，都会改变客户端收到的业务码 → 需 owner 决定。 |
| C:U-148 | P1 | PARTIAL | 同 C:U-039 | 同 C:U-039 | 与 U-039 同一处，同一提交。 |
| C:U-180 | P1 | PARTIAL | 新增 `utils/enum-code.js`，接入 `utils/chapter-atmosphere.js` | `enum-code.test.js` | 单一适配层 `enumCode` / `enumLabel` 已就位，章节配色已接入（行为不变）。页面中约 38 处 `String(x).toUpperCase()` 散落在 club/play/merchant 等域，归各 lane 逐步迁移。`utils/square-share.js` 不带 trim，迁移后会放宽分享判断，所以没有迁移。 |
| C:U-016 | P2 | FIXED | `TopicSaleCanonicalV1.java` | `TopicSaleCanonicalV1Test#nonBooleanPolicyFlagsFailClosed` | 6 处 `asBoolean(true)` 收口为具名常量 `UNPROVEN_FLAG_COUNTS_AS_TRUE`，并注释 fail-closed 语义；测试覆盖非布尔值（对象/数组/乱写字符串）必须失败。 |
| C:U-063 | P2 | FIXED | 新增 `sql/migration_member_collectible_type_comment_20261024.sql`、`ci/migration-dependencies.json`、`MemberCollectible.java`（注释） | 迁移门禁 | 列注释补全 coll_type 1~5 及对应的 ref_id 含义（先校验列形状，再 INSTANT 执行，幂等；依赖 `member_collectible`）。「想去」端点尚不存在，无可修改的校验代码。 |

## 汇总
- FIXED 5：B:P1-003、B:P1-034、C:U-062、C:U-016、C:U-063
- PARTIAL 5：B:P1-076、C:U-039、C:U-078、C:U-148、C:U-180
- ALREADY_FIXED 0
- NEEDS_OWNER 1：C:U-134（另含 U-039/U-148 改回 400 的子问题）

## 待 owner 决定
- C:U-134：不带码的 ServiceException 是否默认回 400（现为 500）？是否要逐步禁用不带码的构造？
- C:U-039/U-148：非法 `pack_type` 是否改为回 400（现为不过滤 + WARN）？
- B:P1-034（知悉）：用户点过「不感兴趣」后，该帖详情页也读不到。这是不是想要的效果？

## 迁移（③，均未在任何库执行）
- `migration_cms_topic_node_status_comment_20261024.sql`（无依赖）
- `migration_member_collectible_type_comment_20261024.sql`（依赖 `member_collectible`）
两者都只改列注释；先校验列形状，不符即停；INSTANT 执行，可重跑。落库需按 chengyin-db-migrate 流程只读预检，并经业主确认。

## 未改动
- 无 wxml/wxss 改动；无鉴权/RBAC/JWT/Redis 改动；无新依赖；未改 C00 保留文件；未调高任何基线。

## CI
- 见 PR #594 的 check runs（run ID 在最终推送后补记）。
