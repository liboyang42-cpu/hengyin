# 9b28 统一CI结算fixture修复

Parent: `9b284f0111b4337e388fc18d797b11c62112cd82`。对应 root 已报告的标准CI结算fixture失败；本片 Java/MySQL/CI **NOT_RUN**，不得称运行通过。

- 新 OriginalCloseRefundHistoryFixture 只装配 CmsTopicServiceImpl 新关账守卫实际调用的 CoopSettlementOriginalMapper 查询端口。调用者显式声明该topic的原退款历史；限定topic/方法，任何未建模查询直接失败；null原样表示读取不可用，不变空数组。不是全Mapper默认返回空，也不mock守卫成功、不开G4d。
- PreparedCoopPlanFixture.settle 为其明确无退款的原组合场景声明同topic历史，覆盖 GroupBasis6、PerMerchant5及共同helper既有CoopSettlementTest调用；原计划/计算/金额/断言不变。
- TopicSettlementIdempotencyTest显式100、SettlementRateConvergenceTest显式200、DoublePaymentReproductionTest显式TOPIC装配原退款历史。原真实FrozenReader/Claim及正金额、只经coopledger、重复零效果/不用activityId等断言不变。
- Wp04TopicSettlementEntryGuardsTest原12条不删不改断言；fixture补同一端口，新增2条证明已批退款与null读取均不能被当作空历史关账，原claims/应付/积分/保证金副作用零调用。普通@Test源码声明12→14，未实跑。
- ApiCoopDepositStatusTest原6条与409/原状态断言保留。Reference现构造器拒绝缺hash/id，修正旧“hash=null仍进入typed4一次”的过期verify为零次写入口调用；是加强边界，非默认准入。原正确引用正向场景保持。

生产Java/XML/SQL/政策/CI/registry均未改；不启用G4d、不改分账/部分退款取整/负利润规则。只做原case名与assertion调用文本保留、diff和源码范围静态检查；完整patch在本提交。root统一实际回归。
