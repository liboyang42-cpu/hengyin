package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.domain.vo.PublicMemberVO.PublicCategoryVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface PublicMemberMapper {

    List<PublicMemberVO> selectPublicMemberList(@Param("userType") Long userType,
                                                @Param("keyword") String keyword);

    List<PublicMemberVO> selectInvitedPublicMemberList(@Param("inviterId") String inviterId);

    PublicMemberVO selectPublicMemberById(@Param("memberId") Long memberId);

    List<PublicCategoryVO> selectPublicCategoriesByIds(
            @Param("categoryIds") String[] categoryIds);
}
