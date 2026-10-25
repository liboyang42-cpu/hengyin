package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.CoopOrderStationScope;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.CoopSettlementSnapshot;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.ICoopOrderSettlementSnapshotReader;
import com.chengyinhub.business.service.support.CoopOrderLineupSource;
import com.chengyinhub.business.service.support.FulfillmentWindowResolver;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import java.util.Objects;

/** 冻结合同与已开售冻结票目录的窄适配器；不从缺失字段或 live share_mode 猜报酬。 */
@Service
public class FrozenCoopOrderSettlementSnapshotReader implements ICoopOrderSettlementSnapshotReader {
    private final CoopOrderLineupSource lineupSource;
    private final CmsTopicMapper topicMapper;
    private final CmsTopicNodeMapper nodeMapper;
    private final OmsTicketMapper ticketMapper;

    public FrozenCoopOrderSettlementSnapshotReader(CoopOrderLineupSource lineupSource,
            CmsTopicMapper topicMapper, CmsTopicNodeMapper nodeMapper, OmsTicketMapper ticketMapper) {
        this.lineupSource = lineupSource;
        this.topicMapper = topicMapper;
        this.nodeMapper = nodeMapper;
        this.ticketMapper = ticketMapper;
    }

    @Override
    public CoopSettlementSnapshot read(CoopOrder order) {
        try {
            return readFrozen(order);
        } catch (RuntimeException invalid) {
            if (invalid instanceof IllegalStateException && invalid.getMessage() != null
                    && invalid.getMessage().startsWith("MANUAL_REPAIR:")) throw invalid;
            throw new IllegalStateException("MANUAL_REPAIR:FROZEN_CONTRACT_READ_FAILED", invalid);
        }
    }

    private CoopSettlementSnapshot readFrozen(CoopOrder order) {
        if (order == null || order.getId() == null || order.getTopicId() == null
                || !Integer.valueOf(0).equals(order.getStatus()) || !Integer.valueOf(0).equals(order.getDelFlag())
                || !"v1".equals(order.getTermsVersion())) throw repair("ORDER_OR_TERMS_VERSION_UNPROVEN");
        CoopOrderLineupSource.ReadResult lineup = lineupSource.readEffectiveLineup(order.getTopicId());
        if (lineup == null || !lineup.getRepairReasons().isEmpty()) throw repair("FROZEN_LINEUP_INVALID");
        CoopOrderLineupSource.LineupEntry entry = lineup.getEntries().stream()
                .filter(row -> "COOP_ORDER".equals(row.getContractRefType())
                        && order.getId().equals(row.getContractRefId()))
                .findFirst().orElseThrow(() -> repair("ORDER_NOT_IN_FROZEN_LINEUP"));
        if (!Objects.equals(order.getToId(), entry.getSubjectId())
                || !Objects.equals(order.getRoleSnapshot(), entry.getRole())
                || !Objects.equals(order.getScopeTypeSnapshot(), entry.getScopeType())
                || !Objects.equals(order.getScopeIdSnapshot(), entry.getScopeId())
                || !Objects.equals(order.getFromId(), entry.getInitiatorMemberId())
                || !("merchant".equals(order.getToType()) && "MERCHANT".equals(entry.getSubjectType())
                    || "club".equals(order.getToType()) && "CLUB".equals(entry.getSubjectType()))) {
            throw repair("ORDER_LINEUP_ATTRIBUTION_CONFLICT");
        }
        CmsTopic topic = topicMapper.selectCmsTopicById(order.getTopicId());
        if (topic == null || !order.getTopicId().equals(topic.getId())
                || !Integer.valueOf(0).equals(topic.getDelFlag()) || topic.getSellingStartedAt() == null
                || !TopicLifecycleState.fromCode(topic.getLifecycle()).isSaleStarted()) {
            throw repair("SALE_CATALOG_NOT_FROZEN");
        }
        if (topic.getMemberId() == null || topic.getMemberId() <= 0
                || !topic.getMemberId().equals(entry.getInitiatorMemberId())) {
            throw repair("INITIATOR_PAYEE_UNPROVEN");
        }
        if (!validRate(topic.getFeePlatformRate()) || !validRate(topic.getFeeGuaranteeBaseRate())
                || !validRate(topic.getFeeGuaranteeStepRate()) || topic.getFeeGuaranteeMaxMerchant() == null
                || topic.getFeeGuaranteeMaxMerchant() < 0) {
            throw repair("TOPIC_FROZEN_FEE_PARAMETERS_MISSING");
        }
        if ("TOPIC".equals(entry.getScopeType())) {
            if (!topic.getId().equals(entry.getScopeId())) throw repair("TOPIC_SCOPE_MISMATCH");
        } else if ("NODE".equals(entry.getScopeType())) {
            CmsTopicNode node = nodeMapper.selectCmsTopicNodeById(entry.getScopeId());
            if (node == null || !entry.getScopeId().equals(node.getId())
                    || !topic.getId().equals(node.getTopicId())) throw repair("NODE_TOPIC_RELATION_UNPROVEN");
        } else throw repair("FROZEN_SCOPE_UNSUPPORTED");

        JSONObject terms = JSON.parseObject(order.getTermsSnapshot());
        if (terms == null || !terms.containsKey("shareMode") || !terms.containsKey("compensationMode")) {
            throw repair("FROZEN_TERMS_INCOMPLETE");
        }
        Integer mode = terms.getInteger("shareMode");
        String compensation = terms.getString("compensationMode");
        if (mode == null || !Objects.equals(mode, order.getShareMode())
                || !(mode == 0 && "NONE".equals(compensation)
                    || mode == 1 && "SALES_PERCENT".equals(compensation)
                    || mode == 2 && "FIXED_PER_FULFILLMENT".equals(compensation))) {
            throw repair("FROZEN_COMPENSATION_CONFLICT");
        }
        CoopSettlementSnapshot snapshot = new CoopSettlementSnapshot();
        snapshot.setOrderId(order.getId());
        snapshot.setTopicId(topic.getId());
        snapshot.setInitiatorMemberId(entry.getInitiatorMemberId());
        snapshot.setProductType(topic.getProductType());
        snapshot.setTermsVersion(order.getTermsVersion());
        snapshot.setCatalogFrozenAt(topic.getSellingStartedAt());
        snapshot.setCommercialContextType("TOPIC_EDITION");
        snapshot.setCommercialContextId(topic.getId());
        snapshot.setBeneficiaryRole("SUPPLY".equals(entry.getRole()) ? "MERCHANT" : "CLUB");
        snapshot.setBeneficiaryId(entry.getSubjectId());
        snapshot.setServiceUnit("SUPPLY".equals(entry.getRole()) ? "MERCHANT_PARTICIPATION" : "ORGANIZER_LEAD");
        snapshot.setScopeType(entry.getScopeType());
        snapshot.setScopeId(entry.getScopeId());
        snapshot.setEligiblePurchaseKinds(List.of(order.getEligiblePurchaseKindsSnapshot()));
        snapshot.setFulfillmentEvidenceRule(order.getFulfillmentEvidenceRuleSnapshot());
        snapshot.setCompensationMode(compensation);
        if (mode == 1) {
            BigDecimal rate = terms.getBigDecimal("shareRate");
            if (rate == null || rate.signum() < 0 || rate.compareTo(new BigDecimal("100")) > 0
                    || order.getShareRate() == null || rate.compareTo(order.getShareRate()) != 0) {
                throw repair("FROZEN_SHARE_RATE_UNPROVEN");
            }
            snapshot.setShareRate(rate);
        } else if (mode == 2) {
            BigDecimal fee = terms.getBigDecimal("fixedFee");
            if (fee == null || fee.signum() < 0 || fee.stripTrailingZeros().scale() > 2
                    || order.getFixedFee() == null || fee.compareTo(order.getFixedFee()) != 0) {
                throw repair("FROZEN_FIXED_FEE_UNPROVEN");
            }
            snapshot.setFixedFee(fee);
        }

        java.util.Optional<CoopOrderStationScope> billing =
                CoopOrderStationScope.read(order);
        if (billing.isPresent()) {
            snapshot.setBillingStationId(billing.get().getStationId());
            snapshot.setOrderTermsSha256(billing.get().getTermsHash());
        }

        // 既有票务写闸在售卖条款冻结后禁止修改时间；没有开售标记不读取作结算依据。
        OmsTicket probe = new OmsTicket();
        probe.setOwnerType(1L);
        probe.setOwnerId(topic.getId());
        probe.setDelFlag(0);
        boolean guided = "GUIDED_TICKET".equals(order.getEligiblePurchaseKindsSnapshot());
        probe.setMode(guided ? 1 : 2);
        if (guided) probe.setSubType("guided");
        List<OmsTicket> tickets = ticketMapper.selectOmsTicketList(probe);
        if (tickets == null || tickets.isEmpty()) throw repair("FROZEN_TICKET_WINDOW_MISSING");
        Date start = null;
        Date end = null;
        for (OmsTicket ticket : tickets) {
            if (ticket == null || ticket.getId() == null || !Long.valueOf(1).equals(ticket.getOwnerType())
                    || !topic.getId().equals(ticket.getOwnerId()) || !Integer.valueOf(0).equals(ticket.getDelFlag())
                    || !(guided ? Integer.valueOf(1).equals(ticket.getMode()) && "guided".equals(ticket.getSubType())
                        : Integer.valueOf(2).equals(ticket.getMode()))) {
                throw repair("TICKET_OWNER_UNPROVEN");
            }
            FulfillmentWindowResolver.Window window = FulfillmentWindowResolver.resolve(topic, ticket);
            if (start == null || window.getStartInclusive().before(start)) start = window.getStartInclusive();
            if (end == null || window.getEndExclusive().after(end)) end = window.getEndExclusive();
        }
        snapshot.setFulfillmentStartInclusive(start);
        snapshot.setFulfillmentEndExclusive(end);
        return snapshot;
    }

    private static boolean validRate(BigDecimal rate) {
        return rate != null && rate.signum() >= 0 && rate.compareTo(BigDecimal.ONE) <= 0;
    }

    private static IllegalStateException repair(String reason) {
        return new IllegalStateException("MANUAL_REPAIR:" + reason);
    }
}
