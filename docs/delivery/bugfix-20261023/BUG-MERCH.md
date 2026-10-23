# BUG-MERCH 上线前 bug 分诊与修复（2026-10-23）

范围：商家看板、经营分析、到店核销、结算。基线：`origin/master` @ `b84ed673`。
分诊规则：会让用户/商家碰到的错误行为、数据错误、资金风险 → FIX（带回归测试）；无用户可见影响的整洁性问题 → DEFER；
当前 master 已不成立 → ALREADY_FIXED / NOT_REPRODUCIBLE；需要产品/资金口径拍板 → OWNER（只做安全的 fail-closed 部分）。

## 汇总

| 分诊 | 数量 |
|---|---|
| FIX | 3 |
| OWNER | 7 |
| DEFER | 13 |
| ALREADY_FIXED | 1 |
| NOT_REPRODUCIBLE | 2 |

## 明细

| id | triage | what changed (file) | test | notes |
|---|---|---|---|---|
| A:P0-11 | FIX（资金，fail-closed） | `ApiClubSettlementController.java`：`/withdraw` 改用与 `/summary` 相同的取数（`selectSummaryByBeneficiary` + 新 `verifiedSettledTotal`），按已核验实付累加；任一行缺字段或实付未核验即整笔拒绝 | `ApiClubSettlementControllerTest`：`withdrawUsesTheSameVerifiedPaidTotalAsSummary`、`withdrawFailsClosedWhenAnySettledRowIsUnverified`、`withdrawFailsClosedWhenSettlementDataUnavailable` | 端点仍受 `withdrawal.fast-withdraw.enabled`（默认关）门控，未改开关。**需拍板**：提现金额以「实付」还是「应结+调整」为准、是否要写提现审计与差额展示 |
| A:P0-26 | FIX | `ApiPlayProgressController.java`：静态打卡码命中多个节点时拒绝打卡并记日志，不再静默取第一条 | 新增 `ApiPlayCheckinDuplicateCodeTest` | 唯一索引已由既有迁移 `migration_20260817_tandianri_node_checkin_code_unique.sql` 提供；基线 schema 未动（不改既有 SQL） |
| A:P0-36 | OWNER | — | — | A/B 两条商家指标链分别服务营销首页与店铺参谋两个不同页面，小程序没有同屏混用；兼容路由 `/api/merchant/funnel` 无小程序调用方。合并为单一指标组合器、退场兼容路由属口径与接口决策 |
| A:P0-37 | DEFER | — | — | `/dashboard` 的 `insight` 块（含 PV 曝光与 UV 到店）当前没有任何前端渲染（小程序 `pages/merchant/index` 只读 `revenue/revenue7d/pendingOrders`），无用户可见比值 |
| A:P0-45 | DEFER | — | — | 同上：`insight`（30 天）与 `revenue7d` 均未在页面展示；`insight.windowDays` 已声明窗口 |
| A:P0-46 | DEFER | — | — | 页面标签为「累计收入」，全量求和与口径一致、数值正确；内存求和仅是规模性能问题，上线体量无影响 |
| A:P0-47 | DEFER | — | — | `insight` 块无前端消费者，吞异常不影响任何页面；日志已有 warn |
| A:P0-48 | OWNER | — | — | 后台漏斗「日期可选、不限窗口」是此前刻意保留的合同（`AnalyticsControllerTest` 锁定）；数据源是日汇总表，行数有界。是否加窗口上限/去重声明需运营拍板 |
| B:P1-005 | OWNER | — | — | 给五阶段补 scope/eventAnchor 或改名属接口与口径变更 |
| B:P1-008 | DEFER | — | — | `topics` 只是喂给参谋的主题名列表，同名去重不影响 `activeOffers` 等计数；修复需改 mapper 结构 |
| B:P1-010 | DEFER | — | — | 既有迁移已带存在/形状断言且幂等；规则禁止改既有迁移 |
| B:P1-012 | OWNER | — | — | 加权核销率改为分档/饱和告警属指标口径决策 |
| B:P1-015 | DEFER | — | — | 共享核销模块抽取是重构；现有共享 helper 已覆盖各页 |
| B:P1-019 | NOT_REPRODUCIBLE | — | — | `guideBlocks` 在每个章节内单独 `wx:for`，key 为 `章节id-序号`，同一列表内序号唯一，章节 id 为空也不会撞键 |
| B:P1-020 | DEFER | — | — | 待办计数正确；逐主题 `.size()` 是性能问题，商家主题数小 |
| B:P1-021 | DEFER | — | — | 读侧已 fail-soft 回退；缺的是埋点事件（可观测性） |
| B:P1-027 | DEFER | — | — | 前端已不读 `pendingAdjustment`（有合同测试锁定），占位字段无可见影响 |
| B:P1-083 | FIX | `BizFunnelDailyMapper.xml`：`signup_start` 排序值 4（提交 5、支付 6 之前），不再落 `else 99` 排在漏斗末尾 | `BizFunnelDailyMapperSqlTest.readModelKeepsLegacyAndCommunityEngagementStepOrdersDistinct` 新断言 | 后台运营分析表按 step_order 排序，起点此前显示在最后 |
| B:P1-089 | ALREADY_FIXED | — | — | 启动脚本 `ry.sh:8`、`run-chengyinhub-admin.sh:51` 固定 `-Duser.timezone=Asia/Shanghai`；代码层显式时区属加固，改动会牵动按默认时区造数的既有测试，上线后再做 |
| B:P1-094 | OWNER | — | — | 零分母给 0 还是 null 是口径统一决策；且改漏斗写入需确认列可空 |
| B:P2-003 | OWNER | — | — | 安全面板策略种子需负责人冻结口径；前端在非 SUFFICIENT 时隐藏面板，已是 fail-closed |
| C:U-004 | NOT_REPRODUCIBLE | — | — | 重复下单/重复扣款不可复现：结算锁购物车行 + 删除行数==1 校验。剩余缺口是超时重试时回放原订单（需幂等键表+客户端改造），属上线后增强 |
| C:U-036 | OWNER | — | — | 模板市场标题判重是反刷量产品规则 |
| C:U-077 | DEFER | — | — | 敏感键过滤宽匹配只会多丢埋点属性（隐私方向 fail-closed），无用户可见影响；开发期告警属工具改进 |
| C:U-081 | DEFER | — | — | SQL 谓词去重复是重构 |
| C:U-139 | DEFER | — | — | 仅注释不一致 |

## 验证

- 本地：`mvn -pl chengyinhub-admin test -Dtest=ApiClubSettlementControllerTest,ApiPlayCheckinDuplicateCodeTest,ApiPlayCheckinLiveCodeTest,BizFunnelDailyMapperSqlTest,BizFunnelDailyMapperMyBatisTest,AnalyticsControllerTest,ClubSettlement*` → 43 run, 0 fail（6 个 MySQL IT 本地跳过）。
- 负控：回退三处主代码后，三个新回归用例均失败。
- 无新依赖、无新迁移、未动鉴权/RBAC/JWT/Redis 配置、未动小程序 UI 与 C00 保留文件、未提交台账。
- CI（PR #555，head `638ccdf8`，35 个检查全部 success/按变更范围 skipped）：run 39956437478（主 CI：Backend tests、小程序 20 分片与静态门、迁移门、gitleaks）、39956437342（Funds MySQL IT + 自检）、39956437433（Followup 39 backend cases）、39956437590（isolated-authenticated-redis-recovery）。
