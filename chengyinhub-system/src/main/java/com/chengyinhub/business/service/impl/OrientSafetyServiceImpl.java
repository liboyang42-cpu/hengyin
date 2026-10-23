package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OrientSafetyRule;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.OrientMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IOrientSafetyService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.support.OrientRejection;
import com.chengyinhub.business.service.support.OrientSafetyPolicy;
import com.chengyinhub.business.service.support.OrientSwitches;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class OrientSafetyServiceImpl implements IOrientSafetyService {

    @Autowired
    private OrientMapper orientMapper;
    @Autowired
    private ICmsTopicService topicService;
    @Autowired
    private ICmsActivityService activityService;
    @Autowired
    private IUmsMemberService memberService;
    @Autowired
    private OrientSwitches switches;

    @Override
    public Map<String, Object> check(Long memberId, Long topicId, Long activityId) {
        switches.require(switches.safetyOn());
        OrientOwnership.requireLogin(memberId);
        CmsTopic topic = OrientOwnership.requireTopic(topicService, topicId);
        OrientOwnership.requireOrienteering(topic);
        OrientSafetyPolicy.Decision decision = decide(memberId, topic, activityId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("allowed", decision.allowed());
        data.put("blockers", decision.blockers);
        data.put("night", decision.night);
        data.put("insuranceRequired", decision.insuranceRequired());
        data.put("sosAvailable", decision.sosAvailable());
        return data;
    }

    @Override
    public void assertAllowed(Long memberId, CmsTopic topic, Long activityId) {
        if (!switches.safetyOn()) return;
        OrientSafetyPolicy.Decision decision = decide(memberId, topic, activityId);
        if (!decision.allowed()) {
            throw new OrientRejection(OrientRejection.SAFETY_BLOCKED, decision.blockers.get(0).get("message"));
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> saveRule(Long memberId, Long topicId, Integer nightStartMinute, Integer nightEndMinute,
                                        Integer weatherBlock, String weatherReason, Date weatherBlockUntil,
                                        Integer insuranceRequired) {
        switches.require(switches.safetyOn());
        OrientOwnership.requireTopicOwner(topicService, memberId, topicId);
        if (insuranceRequired != null && insuranceRequired != 0) {
            throw new OrientRejection(OrientRejection.INSURANCE_NOT_MANDATORY, "保险不能设为参与的必要条件");
        }
        int start = nightStartMinute == null ? OrientSafetyPolicy.DEFAULT_NIGHT_START : nightStartMinute;
        int end = nightEndMinute == null ? OrientSafetyPolicy.DEFAULT_NIGHT_END : nightEndMinute;
        if (start < 0 || start >= 1440 || end < 0 || end >= 1440) {
            throw new OrientRejection(OrientRejection.BAD_REQUEST, "夜间时段需在 0-1439 分钟之间");
        }
        String reason = weatherReason == null || weatherReason.trim().isEmpty() ? null : weatherReason.trim();
        if (reason != null && reason.length() > 200) {
            throw new OrientRejection(OrientRejection.BAD_REQUEST, "天气说明不能超过 200 字");
        }
        Date now = new Date();
        OrientSafetyRule rule = new OrientSafetyRule();
        rule.setTopicId(topicId);
        rule.setInsuranceRequired(0);
        rule.setNightStartMinute(start);
        rule.setNightEndMinute(end);
        rule.setWeatherBlock(Integer.valueOf(1).equals(weatherBlock) ? 1 : 0);
        rule.setWeatherReason(reason);
        rule.setWeatherBlockUntil(weatherBlockUntil);
        rule.setUpdateBy(memberId);
        rule.setUpdateTime(now);
        if (orientMapper.updateSafetyRule(rule) == 0) {
            rule.setCreateTime(now);
            try {
                orientMapper.insertSafetyRule(rule);
            } catch (DuplicateKeyException race) {
                orientMapper.updateSafetyRule(rule);
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("topicId", topicId);
        data.put("insuranceRequired", false);
        data.put("nightStartMinute", start);
        data.put("nightEndMinute", end);
        data.put("weatherBlock", rule.getWeatherBlock() == 1);
        data.put("weatherReason", reason);
        data.put("weatherBlockUntil", weatherBlockUntil);
        return data;
    }

    private OrientSafetyPolicy.Decision decide(Long memberId, CmsTopic topic, Long activityId) {
        Date now = new Date();
        Date start = now;
        Date end = now;
        if (activityId != null && activityId > 0) {
            CmsActivity activity = activityService.selectCmsActivityById(activityId);
            if (activity == null || !topic.getId().equals(activity.getTopicId())) {
                throw new OrientRejection(OrientRejection.NOT_FOUND, "场次不存在");
            }
            if (activity.getStartDate() != null) start = activity.getStartDate();
            end = activity.getEndDate() != null ? activity.getEndDate() : start;
        }
        UmsMember member = memberService.selectUmsMemberById(memberId);
        OrientSafetyRule rule = orientMapper.selectSafetyRule(topic.getId());
        // 仓内尚无监护关系数据源(survey C7-1),监护核验恒为未通过:未成年人在 owner 定方案前一律被拦。
        return OrientSafetyPolicy.evaluate(rule, start, end, now,
                member == null ? null : member.getBirthday(), false);
    }
}
