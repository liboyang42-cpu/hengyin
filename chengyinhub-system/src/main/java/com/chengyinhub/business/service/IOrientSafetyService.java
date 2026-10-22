package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;

import java.util.Date;
import java.util.Map;

/** 城市定向安全闸(Doc2 F1d):保险不强制;夜间/天气/年龄监护为真阻断;SOS 不受信誉限制。 */
public interface IOrientSafetyService {

    /** 参与前预检:{allowed, blockers[], night, insuranceRequired:false, sosAvailable:true}。 */
    Map<String, Object> check(Long memberId, Long topicId, Long activityId);

    /** 参与动作(任务提交;F1a 开局也应调用)前的硬闸。安全开关关闭时不拦。 */
    void assertAllowed(Long memberId, CmsTopic topic, Long activityId);

    /** 发布者设置自己主题的夜间窗口与天气停玩;insuranceRequired 只接受 0。 */
    Map<String, Object> saveRule(Long memberId, Long topicId, Integer nightStartMinute, Integer nightEndMinute,
                                 Integer weatherBlock, String weatherReason, Date weatherBlockUntil,
                                 Integer insuranceRequired);
}
