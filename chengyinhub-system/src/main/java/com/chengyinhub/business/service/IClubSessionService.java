package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.dto.ClubSessionOpenRequest;

import java.util.List;

/** 俱乐部带队场次命令服务。 */
public interface IClubSessionService {
    /** 主理人可主动开团的生效合作主题。 */
    List<CmsTopic> selectOpenableTopics(Long clubId, Long memberId);

    /**
     * 主理人在生效俱乐部合作下主动开团。
     *
     * @return 新场次 id
     * @throws IllegalArgumentException 参数、主理人归属或合作单门禁不满足
     */
    Long open(ClubSessionOpenRequest request, Long memberId, String username);
}
