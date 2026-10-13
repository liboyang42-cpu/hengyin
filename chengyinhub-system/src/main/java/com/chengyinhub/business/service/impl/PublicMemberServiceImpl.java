package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.domain.vo.PublicMemberVO;
import com.chengyinhub.business.mapper.PublicMemberMapper;
import com.chengyinhub.business.service.ICmsActivityLikeService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsTopicLikeService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.IPublicMemberService;
import com.chengyinhub.business.service.IUmsCreativeSquareLikeService;
import com.chengyinhub.business.service.IUmsMemberBalanceDetailService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.common.utils.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PublicMemberServiceImpl implements IPublicMemberService {

    @Autowired
    private PublicMemberMapper publicMemberMapper;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsActivityService cmsActivityService;

    @Autowired
    private IUmsMemberFollowService umsMemberFollowService;

    @Autowired
    private ICmsActivityLikeService cmsActivityLikeService;

    @Autowired
    private ICmsTopicLikeService cmsTopicLikeService;

    @Autowired
    private IUmsCreativeSquareLikeService umsCreativeSquareLikeService;

    @Autowired
    private IUmsMemberBalanceDetailService umsMemberBalanceDetailService;

    @Override
    public List<PublicMemberVO> selectPublicMemberList(String userType, String keyword) {
        Long type = parsePublicUserType(userType);
        List<PublicMemberVO> members = publicMemberMapper.selectPublicMemberList(type, keyword);
        for (PublicMemberVO member : members) {
            ensurePublicNickname(member);
            enrichListStatistics(member);
        }
        return members;
    }

    @Override
    public List<PublicMemberVO> selectInvitedPublicMemberList(Long inviterId) {
        if (inviterId == null || inviterId <= 0) {
            return java.util.Collections.emptyList();
        }
        List<PublicMemberVO> members =
                publicMemberMapper.selectInvitedPublicMemberList(String.valueOf(inviterId));
        for (PublicMemberVO member : members) {
            ensurePublicNickname(member);
        }
        return members;
    }

    @Override
    public PublicMemberVO selectPublicMemberById(Long memberId, Long viewerMemberId) {
        if (memberId == null || memberId <= 0 || viewerMemberId == null || viewerMemberId <= 0) {
            return null;
        }
        PublicMemberVO member = publicMemberMapper.selectPublicMemberById(memberId);
        if (member == null) {
            return null;
        }
        ensurePublicNickname(member);
        enrichDetailStatistics(member);
        member.setIsFollow(isFollowing(viewerMemberId, member.getId()) ? 1 : 0);
        return member;
    }

    @Override
    public PublicMemberVO selectPublicMemberById(Long memberId) {
        if (memberId == null || memberId <= 0) {
            return null;
        }
        PublicMemberVO member = publicMemberMapper.selectPublicMemberById(memberId);
        if (member == null) {
            return null;
        }
        ensurePublicNickname(member);
        // 匿名:不查关注关系,isFollow 保持 null(白名单里没有观看者关系这一项)
        enrichDetailStatistics(member);
        return member;
    }

    private Long parsePublicUserType(String userType) {
        if ("0".equals(userType)) {
            return null;
        }
        if ("2".equals(userType)) {
            return 2L;
        }
        return 1L;
    }

    private void ensurePublicNickname(PublicMemberVO member) {
        if (StringUtils.isBlank(member.getNickname())) {
            member.setNickname("用户" + member.getId());
        }
    }

    private void enrichListStatistics(PublicMemberVO member) {
        Long memberId = member.getId();
        member.setTopicNum(cmsTopicService.selectCmsTopicNumByMemberId(memberId));
        member.setActivityNum(cmsActivityService.selectCmsActivityNumByMemberId(memberId));
        member.setFollowNum(umsMemberFollowService.selectUmsMemberFollowNumByMemberId(memberId));
    }

    private boolean isFollowing(Long viewerMemberId, Long memberId) {
        UmsMemberFollow follow = new UmsMemberFollow();
        follow.setMemberId(viewerMemberId);
        follow.setFollowMemberId(memberId);
        return !umsMemberFollowService.selectUmsMemberFollowList(follow).isEmpty();
    }

    /** 与观看者无关的公开统计。观看者关系(isFollow)由调用方按是否登录另行决定。 */
    private void enrichDetailStatistics(PublicMemberVO member) {
        Long memberId = member.getId();
        enrichListStatistics(member);
        member.setFansNum(umsMemberFollowService.selectUmsMemberFansNumByMemberId(memberId));

        int activityLikes = cmsActivityLikeService.selectUmsMemberLikeNumByMemberId(memberId);
        int topicLikes = cmsTopicLikeService.selectUmsMemberLikeNumByMemberId(memberId);
        int squareLikes = umsCreativeSquareLikeService.selectUmsMemberLikeNumByMemberId(memberId);
        member.setLikeNum(activityLikes + topicLikes + squareLikes);

        if (StringUtils.isNotEmpty(member.getCategoryIds())) {
            String[] ids = member.getCategoryIds().split(",");
            member.setSysCategoryList(publicMemberMapper.selectPublicCategoriesByIds(ids));
        }

        Map<String, Object> params = new HashMap<>();
        params.put("memberId", memberId);
        Integer joinNum = umsMemberBalanceDetailService.selectJoinNum(params);
        member.setJoinUserNum(joinNum == null ? 0 : joinNum);
    }
}
