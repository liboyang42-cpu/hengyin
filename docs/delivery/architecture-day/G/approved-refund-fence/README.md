# G 已批旧退款防双冲：先独立冻结

Parent `6df875d59f986cff6d90c7c9ad9c7903244c2078`。同一独占工作区的新分支 `codex/architecture-day-g-approved-refund-fence-20261024`。原6df与4d6 producer冻结不改；本片不合并producer，也不修改A/B/D、CI、ScheduleUtils或schema。Java/MySQL/CI全部 NOT_RUN。

实际接线：原 `settleTopic` topic首锁/CAS → `lockTopicCashBasis` 完整当前报名锁 → 新已批退款当前读 → 原basis/关账；直接 `freezeOriginals` → 原topic锁 → 复用既有EvidenceMapper锁主题/真实活动报名 → 同一新查询 → 原close/contribution/component写入。已批退款在任何新原件写入之前失败，topic CAS由原事务回滚；未查询快照净额或选择资金算法。

新SQL只认物理registration source与真实activity.topic_id；包括旧owner审批产生的status=1、已打款/未打款、已删活动及软删退款资金证据。不依赖新的close/组件存在，不信冗余refund.owner_*，不因响应或回执TTL释放身份。原pending退款读闸保留。A/e6先持同topic锁再选择hasFrozen owner：若旧owner先批准，G随后读到APPROVED而拒绝原件冻结；若G先提交原件，A随后选择新owner，仅SUCCESS同事务冲回。新拒结不是合法退款拒付，也不回滚已提交旧追收。

失败机制：旧来源层可能已保存paid2/2的contribution，旧owner随后批准并从原收款人追收。原freeze重放只比对旧来源载荷，仍可生成新components；SUCCESS新hook会再认领一次。现在无论来源是否已缓存/回执是否软删，必须先读现时已批历史，不能从旧来源重放绕过。

新增2条unit（原14保留，现16声明）；新 `Wp04ApprovedRefundFenceMySqlIT` 4普通声明、0实跑：旧追收+已有原来源拒绝新组件/余额与旧流水不变；真实活动父关系/错冗余owner/软删资金仍阻断零关账；数字碰撞的外国scope不误阻正常零关账；RR旧视图/新提交APPROVED与同事务L1当前读。复用原UUID fixture/gate `WP04_PAID_HALF_STATE_DDL_APPROVED=1`；root须只在这条精确suite设置批准。原14unit/10original-refund IT/4paid-half IT断言不删；只补必要mapper装配，将历史退款fixture批准时点放到原件冻结后。第一条IT使用实际registerVerified/processVerified/claim/mutation fence提交原2/2+DONE，不造inline绑定；旧owner追收行是明确历史夹具，不声称执行旧RefundService或providerSDK。

后续独立片已保留在未提交树：按H/Ae6完整父从范围恢复遗漏、原付款fact/hash MANUAL_REQUIRED、修正A patch真实checkedVerifiedPayment(...).reference()与早返回顺序；不混入本次guard。三项金额政策未答，不实现部分退款舍入/跨订单分币/亏损承担，不扣保证金、不自动调资金。完整late现金补入/最终恢复仍未完成，不把手工待办冒充闭环。

后续父从单/H接缝现作为单独增量交付，见 [RECOVERY.md](RECOVERY.md)；guard原始清单固定于24b，后续测试装配以recovery清单核验。
