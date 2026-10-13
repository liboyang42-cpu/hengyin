package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsPointsSetting;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.domain.UmsComment;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.common.exception.ServiceException;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 文本检查通过后的评论公开与首评奖励；媒体外呼由调用者在提交成功后执行。 */
@Service
public class CommentPublicationService {
    private final ContentInteractionGuard guard;
    private final UmsMemberMapper memberLocks;
    private final UmsCommentMapper reviewFacts;
    private final IUmsCommentService comments;
    private final IUmsMemberService members;
    private final ICmsPointsSettingService settings;
    private final IUmsMemberPointsDetailService details;

    public CommentPublicationService(ContentInteractionGuard guard, IUmsCommentService comments,
            IUmsMemberService members, ICmsPointsSettingService settings, IUmsMemberPointsDetailService details,
            UmsMemberMapper memberLocks, UmsCommentMapper reviewFacts) {
        this.guard = guard;
        this.memberLocks = memberLocks;
        this.reviewFacts = reviewFacts;
        this.comments = comments;
        this.members = members;
        this.settings = settings;
        this.details = details;
    }

    @Transactional(rollbackFor = Exception.class)
    public String publish(UmsComment comment) {
        // 所有 ownerType 都按玩家串行首评资格；先锁再建立查询快照，避免双方各只看到自身评论。
        memberLocks.lockMemberById(comment.getMemberId());
        String targetError = guard.lockTarget(comment.getOwnerType(), comment.getOwnerId(), comment.getReplyId());
        if (targetError != null) return targetError;
        // 3-11:主题/活动评价的对象必须真实存在(帖文由 guard 锁定校验)。
        boolean review = comment.getOwnerType() == 1 || comment.getOwnerType() == 2;
        if (review && !reviewFacts.reviewTargetExists(comment.getOwnerType(), comment.getOwnerId()))
            return "评论对象不存在或已删除";
        if (review && comment.getRating() != null
                && (comment.getRating() < 0 || comment.getRating() > 5))
            return "评分必须在0至5之间";
        comment.setStatus(1);
        comment.setDelFlag(0);
        if (comments.insertUmsComment(comment) <= 0) throw new ServiceException("添加评论失败");

        // 3-11:首评积分只给参与过(已支付或已核销报名)的主题/活动评价;帖文评论不发。没参与过的照样能评。
        if (!review || !reviewFacts.hasPaidOrVerifiedRegistration(
                comment.getOwnerType(), comment.getOwnerId(), comment.getMemberId())) return null;
        // 保持原有规则：同一玩家在同一对象下只有首条评论可获奖励，隐藏后不回收。
        UmsComment query = new UmsComment();
        query.setMemberId(comment.getMemberId());
        query.setOwnerType(comment.getOwnerType());
        query.setOwnerId(comment.getOwnerId());
        List<UmsComment> existing = comments.selectUmsCommentList(query);
        if (existing != null && existing.size() > 1) return null;
        CmsPointsSetting setting = settings.selectCmsPointsSettingById(4L);
        int points = setting == null || setting.getValue() == null ? 0 : setting.getValue().intValue();
        if (points == 0) return null;
        UmsMember member = members.selectUmsMemberById(comment.getMemberId());
        if (member == null) return null;
        if (!members.addPoints(member.getId(), points)) throw new ServiceException("评论奖励积分失败");

        UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
        detail.setMemberId(comment.getMemberId());
        detail.setCurrentPoints(member.getPoint());
        detail.setAfterPoints(member.getPoint() + points);
        detail.setChangePoints((long) points);
        detail.setChangeReason("活动评价/投票");
        detail.setEventType(4L);
        detail.setEventId(comment.getId());
        detail.setChangeType(1L);
        detail.setDelFlag(0);
        if (details.insertUmsMemberPointsDetail(detail) <= 0) throw new ServiceException("评论奖励流水写入失败");
        return null;
    }
}
