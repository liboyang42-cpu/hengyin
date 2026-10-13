package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.common.constant.ProductType;
import org.springframework.stereotype.Component;

/** H2：商家 member 发布的 FREE_EXPLORE 必须由一笔完整 type1 合作单转交给俱乐部。 */
@Component
public class MerchantPublishedTopicTransferGate {

    private final MmsMerchantMapper merchantMapper;
    private final ClubMapper clubMapper;
    private final CoopOrderLineupSource lineupSource;

    public MerchantPublishedTopicTransferGate(MmsMerchantMapper merchantMapper, ClubMapper clubMapper,
                                              CoopOrderLineupSource lineupSource) {
        this.merchantMapper = merchantMapper;
        this.clubMapper = clubMapper;
        this.lineupSource = lineupSource;
    }

    /** 开售提审时读取一次；审核通过后的 finalize 还会重新读取并调用另一重载。 */
    public void assertTransferred(CmsTopic topic) {
        if (!isMerchantPublishedFreeExplore(topic)) return;
        assertCompleteOrganizer(topic, lineupSource.readEffectiveLineup(topic.getId()));
    }

    /** finalize 使用刚从 coop_order 重读的阵容，避免审核回执或旧 invite 状态绕过。 */
    public void assertTransferred(CmsTopic topic, CoopOrderLineupSource.ReadResult lineup) {
        if (!isMerchantPublishedFreeExplore(topic)) return;
        assertCompleteOrganizer(topic, lineup);
    }

    private boolean isMerchantPublishedFreeExplore(CmsTopic topic) {
        if (topic == null || !Integer.valueOf(ProductType.FREE_EXPLORE).equals(topic.getProductType())) return false;
        Long publisherMemberId = topic.getMemberId();
        if (publisherMemberId == null) {
            throw new IllegalStateException("FREE_EXPLORE 发布者归属缺失");
        }
        if (topic.getClubId() != null && topic.getClubId() > 0) {
            Club club = clubMapper.selectClubById(topic.getClubId());
            if (club != null && publisherMemberId.equals(club.getMemberId())) return false;
        }
        MmsMerchant merchant = merchantMapper.selectMmsMerchantByMemberId(publisherMemberId);
        if (merchant == null) return false;
        if (merchant.getMemberId() == null || !publisherMemberId.equals(merchant.getMemberId())) {
            throw new IllegalStateException("商家发布者归属异常 memberId=" + publisherMemberId);
        }
        // 发布者事实一旦形成，不因商家后续停用/软删而消失；当前状态只影响新业务，不改写历史来源。
        return true;
    }

    private static void assertCompleteOrganizer(CmsTopic topic, CoopOrderLineupSource.ReadResult lineup) {
        if (lineup == null) throw new IllegalStateException("商家主题转交阵容读取失败");
        if (!lineup.getRepairReasons().isEmpty()) {
            throw new IllegalStateException("商家主题转交合作单无效: " + lineup.getRepairReasons().get(0));
        }
        int organizers = 0;
        for (CoopOrderLineupSource.LineupEntry entry : lineup.getEntries()) {
            if (entry == null || !"ORGANIZER".equals(entry.getRole())) continue;
            organizers++;
            if (!"CLUB".equals(entry.getSubjectType()) || entry.getSubjectId() == null || entry.getSubjectId() <= 0
                    || !"TOPIC".equals(entry.getScopeType()) || !topic.getId().equals(entry.getScopeId())
                    || !"COOP_ORDER".equals(entry.getContractRefType())
                    || entry.getContractRefId() == null || entry.getContractRefId() <= 0
                    || !topic.getMemberId().equals(entry.getInitiatorMemberId())) {
                throw new IllegalStateException("商家主题 type1 ORGANIZER owner/scope 冻结快照不完整");
            }
        }
        if (organizers != 1) {
            throw new IllegalStateException("商家发布的 FREE_EXPLORE 必须恰好一笔完整有效"
                    + " type1 ORGANIZER/TOPIC/GUIDED_TICKET 合作单，当前=" + organizers);
        }
    }
}
