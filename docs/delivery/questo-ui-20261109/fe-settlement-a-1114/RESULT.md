# D-19 主办方结算：业主决定 A（2026-11-14）

依赖 [集成 PR #838](https://github.com/liboyang42-cpu/hengyin/pull/838)（`integ/wave-1114`），必须在该 PR 合入 master 之后合并本线；本工位不合并、不部署。

## 决定与实现

- 原无路径的跨俱乐部场次、四店探店日场次，票款结给场次主办方。俱乐部沿用现有主理人/冻结收款人解析；商家场次取场次发布者。主题原作者不增收现金，店铺由主办方线下结算，不生成逐店分账。
- 复用 `CmsActivityServiceImpl` 的场次领取、`recordFreeExploreSessionLedger`、`FreeExploreFeeAllocator` 与 `SettlementEntryServiceImpl`：冻结 `fee_platform_rate`、整场四舍五入一次并按余数分摊。3×9.50 在冻结 3% 时平台合计 0.86，主办方 27.64，逐票平台费 0.29/0.29/0.28。
- `selectActivitiesForSettlement` 把原待处理选单中已明确主办方路径的场次合入并按活动 ID 去重。该选单原有未结算、14 天、退款截止和历史 FINAL 排除条件保持，存量 `is_settlement=0` 无需迁移，下轮通过同一原子领取结算。
- 主结算及暂扣补结均跳过这两类的逐店核销费计划；收款人、现金和逐票账本使用同一路径，退款继续按真实入账凭据冲回。
- 已知两类不再出现在「结算待处理」列表；缺快照、合同不可读、缺主办方等未知情况仍拒结告警。owner/scope、平台账户检查和既有收款资格未改。
- 既有普通商家发布主题的承办场次已经有结算路径，本次不改其原收款规则。其他自由探索场次、退款留存等口径沿用 #833，本次不另造分摊实现。没有新依赖或 DDL。

## 测试与边界

- JUnit 新增跨俱乐部、四店俱乐部、四店商家三个向量：旧待处理选单恢复/去重、冻结费率不受当前费率变化影响、分位守恒、主题原作者不收款、不调店铺费组件、重复领取不再入账。
- 原「四店合同必须拒结」断言已由决定 A 正式取代；保留缺快照及坏合同拒结负控。
- 原 Funds MySQL IT 待处理用例更新为上述三类真实 SQL 向量，核对现金/逐票账本 97/3 分配、每单退款冲回两次仍只扣一次、结算重跑不重复、历史 FINAL 不重付、坏合同回滚、未满 14 天和全额退款不出款。保留套件 15 项登记及其他原资金用例，未改门禁计数或工作流。
- VPS 聚焦 JUnit 22/22 通过，失败/错误/跳过均为 0（MerchantPublishedFreeExploreSettlementTest、ActivitySettlementIdempotencyTest、FreeExploreFeeAllocatorTest）；快照树 `0d8104495d40ea89763d3892d724644e30bee42b`，预算构建器 exitCode=0。首次测试编译默认堆不足，保持容器 1536MiB 预算不变后使用 Maven 256MiB / 独立 javac 896MiB 完成，无源码/POM/门禁变更。
- 本 PR CI/Funds MySQL IT 及合入集成后的最终验证仍待回执；不能由源码/fixture 推导生产或真机验收。本线不操作生产，不启用功能，不修改 `ci/architecture/*` 或 workflows，不运行本地 Java/Docker 或 `ci/xcx-check.sh` selftests。

STATUS: PARTIAL - VPS/CI 验证中；待 #838 合并后合入 master 并取得最终全绿
