package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 俱乐部帖文「带上一次游玩记录」引用的统一资格(F0098)。
 *
 * <p>写入时:被引对象必须<b>当前公开可见</b>(未删、已上架、非仅成员可见),且发帖人本人确有报名成功记录
 * —— 前端选择器只列「我参加过的」,但藏入口不是权限,伪造他人通关 ID 要在接口拒。</p>
 *
 * <p>读取时:帖子列表是公开接口,引用卡按<b>匿名口径</b>重新判当前可见性;引用后被删/下架/转仅成员,
 * 只回一个「引用已不可见」标记,不投影标题和封面。稳定原对象 ID 仍保留在帖子上,恢复公开后卡片自动回来。</p>
 */
@Component
public class ClubPostReferenceGuard
{
    public static final int REF_ACTIVITY = 1;
    public static final int REF_TOPIC = 2;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private CmsActivityMapper cmsActivityMapper;

    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;

    /** 发帖时校验引用;不合格抛 ServiceException(400 信息不全/类型不支持,403 非本人记录,404 不可见)。 */
    public void requireCitable(Long memberId, Integer refType, Long refId)
    {
        if (refType == null && refId == null)
        {
            return;
        }
        if (refType == null || refId == null || refId <= 0)
        {
            throw new ServiceException("引用信息不完整", 400);
        }
        if (refType != REF_ACTIVITY && refType != REF_TOPIC)
        {
            throw new ServiceException("暂不支持这种引用", 400);
        }
        if (!isPubliclyVisible(refType, refId))
        {
            // 不区分「不存在 / 已删 / 私密」:能猜 ID 不等于能确认对象存在。
            throw new ServiceException("引用的记录不存在或当前不可公开", 404);
        }
        long ownerType = refType == REF_ACTIVITY ? 2L : 1L;
        if (memberId == null || cmsRegistrationMapper.countSucceededJoin(memberId, ownerType, refId) <= 0)
        {
            throw new ServiceException("只能引用你自己参加过的记录", 403);
        }
    }

    /** requireCitable 抛出的状态码对应的机器错误码,供前端映射文案。 */
    public static String errorCodeOf(int code)
    {
        switch (code)
        {
            case 400: return "CLUB_POST_REF_INVALID";
            case 403: return "CLUB_POST_REF_NOT_JOINED";
            case 404: return "CLUB_POST_REF_UNAVAILABLE";
            default: return "CLUB_POST_REF_CHECK_FAILED";
        }
    }

    /** 匿名口径的当前可见性(帖子列表公开,引用卡不能比被引对象本身更公开)。 */
    public boolean isPubliclyVisible(Integer refType, Long refId)
    {
        if (refType == null || refId == null || refId <= 0)
        {
            return false;
        }
        if (refType == REF_TOPIC)
        {
            return isTopicPubliclyVisible(refId);
        }
        if (refType == REF_ACTIVITY)
        {
            CmsActivity activity = cmsActivityMapper.selectPublicCmsActivityById(refId);
            if (activity == null)
            {
                return false;
            }
            Long topicId = activity.getTopicId();
            return topicId == null || topicId <= 0 || isTopicPubliclyVisible(topicId);
        }
        return false;
    }

    private boolean isTopicPubliclyVisible(Long topicId)
    {
        // viewerMemberId=null:只放行上架 + 非「仅俱乐部成员可见」的主题(clubMemberOnlyGate 对匿名 fail-closed)
        return cmsTopicMapper.selectCmsTopicDetailById(topicId, null) != null;
    }
}
