package com.chengyinhub.business.service;

import java.util.Map;

/** 俱乐部端「商家与客户」弹层的商家 tab(Figma J7):本主题各节点的承接与审核态。 */
public interface IClubChapterRecruitService {
    /** 承接总览:节点承接态与(已确认商家的)联系方式。需要 club:activity:read。 */
    Map<String, Object> overview(Long principalMemberId, Long clubId, Long topicId);
}
