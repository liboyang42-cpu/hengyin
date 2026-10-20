package com.chengyinhub.business.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.CmsPointsSettingMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.mapper.UmsMemberPointsDetailMapper;
import com.chengyinhub.business.service.ICmsPointsSettingService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.exception.ServiceException;

/**
 * 统一积分发放
 *
 * @author cy
 * @date 2026-06-19
 */
@Service
public class PointsAwardServiceImpl implements IPointsAwardService
{
    @Autowired
    private ICmsPointsSettingService cmsPointsSettingService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private UmsMemberMapper umsMemberMapper;

    @Autowired
    private IUmsMemberPointsDetailService umsMemberPointsDetailService;

    @Autowired
    private UmsMemberPointsDetailMapper umsMemberPointsDetailMapper;

    /** H8 专用读写只走 mapper 上的专用语句，不经通用 service —— 见两个 mapper 里的隔离说明。 */
    @Autowired
    private CmsPointsSettingMapper cmsPointsSettingMapper;

    @Override
    @Transactional
    public int award(Long memberId, Long eventType, Long eventId, String reason)
    {
        if (memberId == null || memberId <= 0 || eventType == null) {
            return 0;
        }

        // 幂等：同 (memberId, eventType, eventId) 已发过则跳过
        UmsMemberPointsDetail probe = new UmsMemberPointsDetail();
        probe.setMemberId(memberId);
        probe.setEventType(eventType);
        if (eventId != null) {
            probe.setEventId(eventId);
        }
        List<UmsMemberPointsDetail> existed = umsMemberPointsDetailService.selectUmsMemberPointsDetailList(probe);
        if (existed != null && !existed.isEmpty()) {
            return 0;
        }

        // 读积分规则（type=1 积分规则 + eventType）
        CmsPointsSetting q = new CmsPointsSetting();
        q.setType(1L);
        q.setEventType(eventType);
        List<CmsPointsSetting> settings = cmsPointsSettingService.selectCmsPointsSettingList(q);
        int points = 0;
        if (settings != null && !settings.isEmpty()) {
            CmsPointsSetting s = settings.get(0);
            if (s.getPointsNum() != null) {
                points = s.getPointsNum();
            } else if (s.getValue() != null) {
                points = s.getValue().intValue();
            }
        }
        if (points <= 0) {
            return 0;
        }

        // 余额 UPDATE 本来就有行锁；提前做当前锁读，让流水快照与该次真实余额变更同序。
        // 普通 select 即使放到 lockMemberById 后，仍可能命中外层事务的 RR 快照/一级缓存。
        Long before = umsMemberMapper.selectPointsForUpdate(memberId);
        if (before == null) {
            throw new ServiceException("积分会员不存在或余额为空，memberId=" + memberId);
        }

        umsMemberService.addPoints(memberId, points);

        UmsMemberPointsDetail d = new UmsMemberPointsDetail();
        d.setMemberId(memberId);
        d.setCurrentPoints(before);
        d.setAfterPoints(before + points);
        d.setChangePoints((long) points);
        d.setChangeReason(reason);
        d.setEventType(eventType);
        d.setEventId(eventId);
        d.setChangeType(1L); // 1=收入
        d.setDelFlag(0);
        d.setCreateTime(new Date());
        umsMemberPointsDetailService.insertUmsMemberPointsDetail(d);

        return points;
    }

    @Override
    @Transactional
    public int awardPublisherSettlement(Long memberId, Long topicId)
    {
        if (memberId == null || memberId <= 0 || topicId == null || topicId <= 0) {
            throw new ServiceException("作品结算奖励缺少有效会员或主题，拒绝结算");
        }

        // H8 专用读路径：通用 selectCmsPointsSettingList 不查 rule_version(迁移未落库时
        // 那条 Vo 会让全站积分读 1054)。这里必须走带 rule_version 的专用语句 ——
        // 缺列就在这里响亮 1054，绝不能拿通用查询回来的 null 版本号继续发奖。
        List<CmsPointsSetting> rules =
                cmsPointsSettingMapper.selectPublisherSettlementRules(EVENT_PUBLISHER_SETTLEMENT);
        if (rules == null || rules.size() != 1) {
            throw new ServiceException("作品结算积分规则必须唯一启用，eventType="
                    + EVENT_PUBLISHER_SETTLEMENT + ", count=" + (rules == null ? 0 : rules.size()));
        }
        CmsPointsSetting rule = rules.get(0);
        int points = requirePublisherSettlementRule(rule);

        if (umsMemberService.lockMemberById(memberId) == null) {
            throw new ServiceException("作品结算积分会员不存在，memberId=" + memberId);
        }
        Long before = umsMemberMapper.selectPointsForUpdate(memberId);
        if (before == null) {
            throw new ServiceException("作品结算积分会员回读失败，memberId=" + memberId);
        }
        UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
        detail.setMemberId(memberId);
        detail.setCurrentPoints(before);
        detail.setAfterPoints(before + points);
        detail.setChangePoints(Long.valueOf(points));
        detail.setChangeReason("作品结算奖励");
        detail.setEventType(EVENT_PUBLISHER_SETTLEMENT);
        detail.setEventId(topicId);
        detail.setRewardBusinessKey(PUBLISHER_SETTLEMENT_KEY_PREFIX + topicId);
        detail.setPointsRuleId(rule.getId());
        detail.setPointsRuleVersion(rule.getRuleVersion());
        detail.setPointsRuleValue(rule.getValue());
        detail.setPublisherSettlementTopicId(topicId);
        detail.setChangeType(1L);
        detail.setDelFlag(0);
        detail.setCreateTime(new Date());
        try {
            if (umsMemberPointsDetailMapper.insertPublisherSettlementPointsDetail(detail) != 1) {
                throw new ServiceException("作品结算积分流水写入失败，topicId=" + topicId);
            }
        } catch (DuplicateKeyException duplicate) {
            // H8 的幂等域是 topic，不是 member。发布资格在两条结算路之间变更时，
            // 也只能认全局唯一键已经选出的那一笔，不得给第二个人再发。
            UmsMemberPointsDetail existing = umsMemberPointsDetailMapper.selectByEventTypeAndEventId(
                    EVENT_PUBLISHER_SETTLEMENT, topicId);
            if (existing == null) {
                existing = umsMemberPointsDetailMapper.selectByRewardBusinessKey(
                        memberId, detail.getRewardBusinessKey());
            }
            if (isSamePublisherSettlement(existing, detail)) {
                return 0;
            }
            throw duplicate;
        }
        if (!umsMemberService.addPoints(memberId, points)) {
            throw new ServiceException("作品结算积分余额更新失败，memberId=" + memberId);
        }
        return points;
    }

    private int requirePublisherSettlementRule(CmsPointsSetting rule)
    {
        if (rule == null || rule.getId() == null
                || !Long.valueOf(1L).equals(rule.getType())
                || !EVENT_PUBLISHER_SETTLEMENT.equals(rule.getEventType())
                || !Long.valueOf(1L).equals(rule.getStatus())
                || !Long.valueOf(0L).equals(rule.getDelFlag())
                || rule.getRuleVersion() == null || rule.getRuleVersion() <= 0
                || rule.getValue() == null || rule.getValue().compareTo(java.math.BigDecimal.ZERO) <= 0) {
            throw new ServiceException("作品结算积分规则无效，eventType=" + EVENT_PUBLISHER_SETTLEMENT);
        }
        try {
            return rule.getValue().intValueExact();
        } catch (ArithmeticException invalid) {
            throw new ServiceException("作品结算积分规则必须是正整数，value=" + rule.getValue());
        }
    }

    private boolean isSamePublisherSettlement(UmsMemberPointsDetail existing,
            UmsMemberPointsDetail expected)
    {
        return existing != null
                && expected.getEventType().equals(existing.getEventType())
                && expected.getEventId().equals(existing.getEventId())
                && expected.getRewardBusinessKey().equals(existing.getRewardBusinessKey())
                && Long.valueOf(1L).equals(existing.getChangeType());
    }
}
