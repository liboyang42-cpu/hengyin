package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopComplaint;

import java.math.BigDecimal;
import java.util.List;

/**
 * coop 客诉工单(责任链):平台单一入口受理 → 先行垫付 → 过错方扣划 → 关闭(解冻)。
 */
public interface ICoopComplaintService {
    /** 受理:玩家投诉建单(status=ACCEPTED),即冻结该主题结算放款。返回工单 id。 */
    Long create(CoopComplaint c);

    /** 某主题的工单列表(用于防刷:同投诉人对同主题已有未结工单则不重复建)。 */
    List<CoopComplaint> listByTopic(Long topicId);

    /** 后台工单列表(按 topicId/status/respondentMemberId 过滤)。 */
    List<CoopComplaint> selectList(CoopComplaint q);

    CoopComplaint getById(Long id);

    List<com.chengyinhub.business.domain.vo.CoopComplaintProgressVO> listMine(Long memberId);

    /** 先行垫付:平台基础赔付给投诉玩家(→余额),status=ADVANCED。 */
    int advance(Long id, BigDecimal amount, Long handlerId);

    /**
     * 关闭:客服认定过错方(respondentType/respondentMemberId,可空则用工单已存值),按 deductAmount 从过错方余额扣划
     * (best-effort,不足只扣可扣部分),status=CLOSED(解冻放款)。未结算金精确扣划为后续细化。
     */
    int close(Long id, String respondentType, Long respondentMemberId, Integer faultRatio,
              BigDecimal deductAmount, String note, Long handlerId);
}
