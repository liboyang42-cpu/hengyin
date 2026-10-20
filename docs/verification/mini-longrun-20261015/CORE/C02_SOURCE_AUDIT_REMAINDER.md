# C02 剩余调用静态审查

状态：SOURCE_REVIEW_ONLY / NOT_RUN。未运行 Node、Java、Maven、DB、lint、语法检查或行为验证；不将本审查计为新增已验证 bug。未修改业务源码、资金规则或控制表。

## legacy 草稿账号边界

`chengyinhub-xcx/utils/publish/pro-editor-draft.js` 的 `saveDraft`（84–91）要求非空 memberId 并写入信封。legacy 指旧的无账号前缀存储键，不表示旧信封没有 owner。`loadDraft`（120–129）先读账号桶、再回读旧键，之后比较信封 memberId 和请求 memberId；只读 fallback 不迁移、不重写旧桶。已有 `pro-editor-draft.test.js` 的默认有效信封为 memberId=42；legacy 反例为 owner=7、请求账号=8，保留 member_mismatch 断言。

真实 fabu 新草稿启动在 index.js:733–734 设置账号后遇空账号立即返回；既有主题启动在769–770同样阻断。active 指针恢复验证信封，扫描只接受对应非空账号；删除也要求非空请求账号和匹配信封。因此，B 的非空账号不能恢复 A 信封，也不能恢复真正无 memberId 的旧信封。没有从当前登录账号推断历史 owner。

工具边界：直接调用 loadDraft 时若请求 memberId 为空，且旧信封 memberId 同为空，字符串比较相等，工具可返回 ready；这不是已找到的真实页面空账号读取链。真正无 owner 的旧草稿不存在可证明历史归属的事实；如要恢复或指定归属，应为 BLOCKED_DECISION，不自动认领、不删除旧桶。本轮不新增兼容或留存政策。

## B03 原有覆盖及 NULL 来源反证

真实历史7项位于 `chengyinhub-admin/src/test/java/com/chengyinhub/business/service/B03AssetMybatisIntegrationTest.java`：前4项覆盖已有 RR 视图/会员缓存与并发扣减后的奖励快照、不同事件并发流水连续、同事件只提交一次、空余额或缺失会员无虚假入账；后3项覆盖商家应付批次并发冲回/冻结后的状态 CAS 及正常批次推进，不是 clawPoints 余额快照集成覆盖。另 `B03PointsAwardServiceTest.java` 的单测覆盖锁读、空会员余额、已发跳过和唯一冲突传播。本轮仅阅读，不重跑、不继承为当前源码验证结果。

PointsAwardServiceImpl.award 已用 selectPointsForUpdate 当前锁读；SQL 禁用缓存并刷新缓存。真实非空事件唯一键 uk_member_event_once(member_id,event_type,event_id) 约束重复来源；并发失败插入使事务回滚，而不是仅依赖先查。addPoints 的零行更新会抛异常。不得将该已有修复重新计为 bug。

NULL eventId 在 SQL 省略事件谓词且 MySQL 唯一键允许多个 NULL，但已读真实调用未发现可达 NULL：ApiPlayProgressController 的节点奖励用 node.id；ApiRegistrationController/ApiClubLeadController 用已持久 registration.id；TopicCompletionAwardServiceImpl 由调用方传入活动 scope（无活动标准化为0）或 registration.ownerId。没有根据手造 NULL 测试宣称线上幂等缺陷。作品结算专用 awardPublisherSettlement 的全局 topic-once 是已有合同，不能改为每作者再发。

冲回将旧收入 event_id 摘空并写 clawed 业务键，负流水使用 NULL eventId 和 clawback 业务键；重复冲回找不到旧非空来源，重新核销可按原合同再奖。这里的 NULL 是明确的冲回账务表示，不是漏写来源。

## 交 E04：clawPoints 的 RR 旧余额读取

精确源：`chengyinhub-system/src/main/java/com/chengyinhub/business/service/impl/RedemptionRewardClawbackService.java:105–126`。调用方已锁会员，但113仍普通 selectUmsMemberById，再以该 point 算 before/deducted 和负流水。`PerkLedgerServiceImpl.reverseRedemption:415–417` 的事务先普通 selectById，可建立 RR 一致性视图；FirstReviewPointsClawbackService:65、84 也复用锁会员后的共享 clawPoints。流水 selectGrantForClawback（mapper213）和 detachGrant（227）不刷新会员一致性视图。

可达交错：T1 早期普通读取建立余额100的 RR 视图；T2 另一事件奖励5并提交到105；T1 随后锁会员、摘除待冲回10分的收入，再普通读仍得到100。实际扣减更新作用于当前余额得到95，但负流水记100→90。并发消费也可使基于旧余额的扣到0计算超过实际余额，引发条件扣减失败。锁定读和普通一致性读混用是缺口；不能以“已锁会员”当作 RR 视图刷新。

既有 FirstReviewPointsClawbackMybatisIntegrationTest 的退款、重复/并发退款、其它有效参与、消费后归零及嵌套回滚等场景，未见“预建 RR 视图后另事务改余额”的交错。此处是源码可达缺口报告，尚无本轮执行复现。

建议交原 owner 的最小边界：余额读取复用已有 selectPointsForUpdate，保留当前 null→0、Math.max、扣到0、来源范围与奖励政策；由该 owner 增加真实 RR 交错回归。本 worker 未编辑 EXTRA/E04 文件，未假定已修。C00 已正式转交：[PR16 comment6194350389](https://github.com/liboyang42-cpu/hengyin/pull/16#issuecomment-6194350389)。

## 收束

通用积分规则查询未筛 status/delFlag，但真实运营页明确只调 value、0停止，页面不提供状态开关；不足以据字段存在就另造启停政策或直接改选规则。本轮不将其列为已证实业务缺陷。

C02 当前授权域内静态候选 EXHAUSTED。保留源 owner，等待新故障或明确登记的真实入口；不制造同族重复事项。
