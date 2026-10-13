package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.PublicMemberVO;

import java.util.List;

public interface IPublicMemberService {

    List<PublicMemberVO> selectPublicMemberList(String userType, String keyword);

    List<PublicMemberVO> selectInvitedPublicMemberList(Long inviterId);

    PublicMemberVO selectPublicMemberById(Long memberId, Long viewerMemberId);

    /**
     * 匿名读公开会员资料:不带观看者,因此不查也不返回 isFollow 等观看者关系字段。
     */
    PublicMemberVO selectPublicMemberById(Long memberId);
}
