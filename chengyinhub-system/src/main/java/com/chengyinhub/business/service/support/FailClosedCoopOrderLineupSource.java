package com.chengyinhub.business.service.support;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.mapper.CoopInviteMapper;
import com.chengyinhub.business.mapper.CoopOrderMapper;
import com.chengyinhub.common.constant.CoopTermsAxes;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 生效 coop_order 的 W2 冻结合同快照适配器。role/scope/选集/证据/计酬只认冻结列，
 * 绝不从可变 shareMode/perksSnapshot 反推；任一合同轴不完整都点名 orderId 进入修复清单。
 */
@Component
public class FailClosedCoopOrderLineupSource implements CoopOrderLineupSource {

    private final CoopOrderMapper coopOrderMapper;
    private final CoopInviteMapper coopInviteMapper;

    public FailClosedCoopOrderLineupSource(CoopOrderMapper coopOrderMapper,
                                           CoopInviteMapper coopInviteMapper) {
        this.coopOrderMapper = coopOrderMapper;
        this.coopInviteMapper = coopInviteMapper;
    }

    @Override
    public ReadResult readEffectiveLineup(Long topicId) {
        if (topicId == null) throw new IllegalArgumentException("topicId 不能为空");
        List<CoopOrder> orders = coopOrderMapper.selectEffectiveOrdersByTopicId(topicId);
        if (orders == null || orders.isEmpty()) {
            return new ReadResult(Collections.<LineupEntry>emptyList(), Collections.<String>emptyList());
        }
        List<LineupEntry> entries = new ArrayList<LineupEntry>();
        List<String> repairReasons = new ArrayList<String>();
        for (CoopOrder order : orders) {
            if (order == null) {
                repairReasons.add("coop_order:<null> mapper 返回空合作单");
                continue;
            }
            List<String> problems = validate(topicId, order);
            validateOrganizerSource(topicId, order, problems);
            Boolean cashObligation = frozenCashObligation(order, problems);
            if (!problems.isEmpty()) {
                repairReasons.add("coop_order:" + order.getId() + " 冻结合同无效: "
                        + String.join(", ", problems));
                continue;
            }
            entries.add(new LineupEntry(order.getToType().toUpperCase(Locale.ROOT), order.getToId(),
                    order.getRoleSnapshot(), order.getScopeTypeSnapshot(), order.getScopeIdSnapshot(),
                    "COOP_ORDER", order.getId(), Boolean.TRUE.equals(cashObligation), order.getFromId()));
        }
        return new ReadResult(entries, repairReasons);
    }

    private static List<String> validate(Long topicId, CoopOrder order) {
        List<String> problems = new ArrayList<String>();
        if (order.getId() == null) problems.add("id 缺失");
        if (!topicId.equals(order.getTopicId())) problems.add("topicId 与查询主题不一致");
        if (!Integer.valueOf(0).equals(order.getStatus())) problems.add("status=0 未满足");
        if (!Integer.valueOf(0).equals(order.getDelFlag())) problems.add("delFlag=0 未满足");
        if (order.getFromId() == null || order.getFromId() <= 0) problems.add("fromId 缺失");
        if (order.getToId() == null || order.getToId() <= 0) problems.add("toId 缺失");
        if (blank(order.getToType())) problems.add("toType 缺失");
        if (blank(order.getRoleSnapshot())) problems.add("roleSnapshot 缺失");
        if (blank(order.getScopeTypeSnapshot())) problems.add("scopeTypeSnapshot 缺失");
        if (order.getScopeIdSnapshot() == null || order.getScopeIdSnapshot() <= 0) {
            problems.add("scopeIdSnapshot 缺失");
        }
        if (blank(order.getEligiblePurchaseKindsSnapshot())) {
            problems.add("eligiblePurchaseKindsSnapshot 缺失");
        }
        if (blank(order.getFulfillmentEvidenceRuleSnapshot())) {
            problems.add("fulfillmentEvidenceRuleSnapshot 缺失");
        }

        if ("ORGANIZER".equals(order.getRoleSnapshot())) {
            if (!"club".equals(order.getToType())) problems.add("ORGANIZER 要求 toType=club");
            if (!"TOPIC".equals(order.getScopeTypeSnapshot())) problems.add("ORGANIZER 要求 scopeTypeSnapshot=TOPIC");
            if (!topicId.equals(order.getScopeIdSnapshot())) problems.add("ORGANIZER 要求 scopeIdSnapshot=topicId");
            if (!"GUIDED_TICKET".equals(order.getEligiblePurchaseKindsSnapshot())) {
                problems.add("ORGANIZER 要求 eligiblePurchaseKindsSnapshot=GUIDED_TICKET");
            }
            if (!"COOP_GROUP_VERIFY".equals(order.getFulfillmentEvidenceRuleSnapshot())) {
                problems.add("ORGANIZER 要求 fulfillmentEvidenceRuleSnapshot=COOP_GROUP_VERIFY");
            }
        } else if ("SUPPLY".equals(order.getRoleSnapshot())) {
            if (!"merchant".equals(order.getToType())) problems.add("SUPPLY 要求 toType=merchant");
            if (!"REGISTRATION_SCAN".equals(order.getFulfillmentEvidenceRuleSnapshot())) {
                problems.add("SUPPLY 要求 fulfillmentEvidenceRuleSnapshot=REGISTRATION_SCAN");
            }
            if ("TOPIC".equals(order.getScopeTypeSnapshot())) {
                if (!topicId.equals(order.getScopeIdSnapshot())) problems.add("TOPIC SUPPLY 要求 scopeIdSnapshot=topicId");
                if (!"GUIDED_TICKET".equals(order.getEligiblePurchaseKindsSnapshot())) {
                    problems.add("TOPIC SUPPLY 要求 eligiblePurchaseKindsSnapshot=GUIDED_TICKET");
                }
            } else if ("NODE".equals(order.getScopeTypeSnapshot())) {
                if (!"EXPLORE_PASS".equals(order.getEligiblePurchaseKindsSnapshot())) {
                    problems.add("NODE SUPPLY 要求 eligiblePurchaseKindsSnapshot=EXPLORE_PASS");
                }
            } else if (!blank(order.getScopeTypeSnapshot())) {
                problems.add("SUPPLY scopeTypeSnapshot 只允许 TOPIC/NODE");
            }
        } else if (!blank(order.getRoleSnapshot())) {
            problems.add("roleSnapshot 只允许 ORGANIZER/SUPPLY");
        }
        return problems;
    }

    private void validateOrganizerSource(Long topicId, CoopOrder order, List<String> problems) {
        if (!"ORGANIZER".equals(order.getRoleSnapshot())) return;
        if (order.getInviteId() == null || order.getInviteId() <= 0) {
            problems.add("inviteId 缺失，无法证明 type1 来源");
            return;
        }
        CoopInvite invite;
        try {
            invite = coopInviteMapper.selectCoopInviteById(order.getInviteId());
        } catch (RuntimeException e) {
            problems.add("inviteId=" + order.getInviteId() + " 来源邀约读取失败");
            return;
        }
        if (invite == null) {
            problems.add("inviteId=" + order.getInviteId() + " 来源邀约不存在或已删除");
            return;
        }
        if (!order.getInviteId().equals(invite.getId())) problems.add("inviteId 与来源邀约不一致");
        if (!Integer.valueOf(1).equals(invite.getInviteType())) problems.add("来源邀约要求 inviteType=1");
        if (!Integer.valueOf(1).equals(invite.getStatus())) problems.add("来源邀约要求 status=1");
        if (!Integer.valueOf(0).equals(invite.getDelFlag())) problems.add("来源邀约要求 delFlag=0");
        if (order.getFromId() == null || !order.getFromId().equals(invite.getFromId())) {
            problems.add("fromId 与来源邀约不一致");
        }
        if (!topicId.equals(invite.getTopicId())) problems.add("来源邀约 topicId 与查询主题不一致");
        if (!"club".equals(invite.getToType()) || !Objects.equals(order.getToId(), invite.getToId())) {
            problems.add("来源邀约俱乐部归属与合作单不一致");
        }
    }

    private static Boolean frozenCashObligation(CoopOrder order, List<String> problems) {
        if (blank(order.getTermsSnapshot())) {
            problems.add("termsSnapshot 缺失");
            return null;
        }
        try {
            JSONObject terms = JSON.parseObject(order.getTermsSnapshot());
            String compensationMode = terms == null ? null : terms.getString("compensationMode");
            if (CoopTermsAxes.COMPENSATION_NONE.equals(compensationMode)) return false;
            if (CoopTermsAxes.COMPENSATION_FIXED_PER_FULFILLMENT.equals(compensationMode)
                    || CoopTermsAxes.COMPENSATION_SALES_PERCENT.equals(compensationMode)) return true;
            problems.add("termsSnapshot.compensationMode 非法");
        } catch (RuntimeException e) {
            problems.add("termsSnapshot 不可解析");
        }
        return null;
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
