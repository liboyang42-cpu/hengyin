package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.service.ICoopOrderSettlementSnapshotReader;

/**
 * 显式不可用的负控实现，不再注册为运行时 bean；实际接线由 FrozenCoopOrderSettlementSnapshotReader 提供。
 * 以下保留替换前的历史排查记录。
 *
 * <p><b>★2026-09-11:接通过一次,又撤回了。把结论留在这里,免得下一轮从头再查一遍。</b>
 *
 * <p>「冻结列还没有」这句旧注释<b>是错的</b>:{@code CoopInviteServiceImpl.freezeOrderContractSnapshot}
 * 在接受邀约那一刻就把 role / scope / 选集 / 证据规则 / 报酬三件套全写进了 coop_order
 * (生产库 24 列俱在),履约窗也能从 {@code support.FulfillmentWindowResolver} 现算。
 * 也就是说,<b>照着冻结列拼一个 snapshot 出来是成立的,难点不在这一层。</b>
 *
 * <p>真正拦住结算的是它下游的两道<b>恒假</b>闸,只把本类接通会让结果从
 * 「抛 manualRepair、一行不落」变成「静默结 ¥0」——而且更坏:
 * {@code CmsTopicServiceImpl.settleCoopTopic} 循环开头的 {@code countByOrderId(o.getId()) > 0}
 * 会把那条 ¥0 行当成「已结过」,这张合作单<b>此后永远不会再被结算</b>。
 * 宁可继续停在人工台。两道闸是:
 *
 * <ol>
 *   <li>{@code CoopSettlementContract.eligible} 第一句要求 {@code ownerType == 1}
 *       (主题票),而俱乐部办团卖的<b>恒是场次票</b>(主理人开一场 → cms_activity → 复制票 →
 *       报名落 owner_type=2)。取证 SQL({@code CoopSettlementEvidenceMapper.selectCandidates})
 *       特地把场次壳查出来了,Java 这一层又把它丢掉 —— 两侧口径本身就不一致。</li>
 *   <li>{@code scopeCovered} 只在 {@code scopeType='TOPIC_EDITION'} 时短路为真,否则拿
 *       {@code scopeId} 去比 {@code coop_group_member_verify.activity_id};而冻结列里写的是
 *       {@code 'TOPIC'/'NODE'}、scopeId 是 topicId/nodeId ⇒ 结构上恒假。
 *       {@code organizerScopeCovered} 同一形状,所以 ORGANIZER_LEAD 那支也过不去。</li>
 * </ol>
 *
 * <p>另有一处口径分叉要一并定:本类若接通,{@code commercialContextType} 该写什么 ——
 * {@code settlement_claim} 的列注释与唯一键认的是 {@code TOPIC_EDITION/ACTIVITY}
 * ({@code migration_settlement_claim_20260811.sql:7,20}),而同一份经济事实在
 * {@code MerchantPayableServiceImpl} 里写的是 {@code TOPIC_EDITION}。再引入第三种拼写
 * 会让唯一闸看不见冲突。
 *
 * <p>⇒ 结算要真跑通,得连着改分账判据(动钱的路),不是把这个 seam 接上就完事。
 * 那一轮要单独做,带真库端到端用例:<b>断言「落了一行且金额不为零」,而不是断言字段搬运对了。</b>
 */
public class UnavailableCoopOrderSettlementSnapshotReader implements ICoopOrderSettlementSnapshotReader {
    @Override
    public CoopSettlementSnapshot read(CoopOrder order) { return null; }
}
