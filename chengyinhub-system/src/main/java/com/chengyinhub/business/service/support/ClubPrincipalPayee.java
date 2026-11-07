package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 场次/主题现金的收款人(2026-11-02 业主口径):钱只给人,不给俱乐部这个实体。
 *
 * <ul>
 *   <li>俱乐部场次({@code cms_activity.club_id > 0}):俱乐部<b>当前</b>主理人({@code club.member_id})。
 *       {@code cms_activity.member_id} 是开场时写死的创建人,转让主理人后不再是收款人。</li>
 *   <li>俱乐部自有主题({@code cms_topic.club_id > 0}):同上,俱乐部当前主理人。</li>
 *   <li>其余(个人/商家发布的场次与主题):发布者本人({@code member_id}),与改造前一致。</li>
 * </ul>
 *
 * <p>俱乐部已删或没有主理人时 fail-closed:抛出,让结算整单回滚下轮重试,不把钱回落给创建人。
 * 不加行锁:与 {@code ClubGovernanceServiceImpl.transferOwner} 并发时按读到的那一刻的主理人入账;
 * 退款冲回按实际入账人扣(见 {@code RefundServiceImpl}),所以过后转让也扣得对人。
 */
@Component
public class ClubPrincipalPayee {

    @Autowired
    private ClubMapper clubMapper;

    /** 场次现金(活动结算 eventType=3)的收款人。 */
    public Long forActivity(CmsActivity activity) {
        if (activity == null) {
            return null;
        }
        Long clubId = activity.getClubId();
        if (clubId == null || clubId <= 0) {
            return activity.getMemberId();
        }
        return principalOf(clubId, "activityId=" + activity.getId());
    }

    /** 俱乐部自有主题自玩通行证现金的收款人。 */
    public Long forTopic(CmsTopic topic) {
        if (topic == null) {
            return null;
        }
        Long clubId = topic.getClubId();
        if (clubId == null || clubId <= 0) {
            return topic.getMemberId();
        }
        return principalOf(clubId, "topicId=" + topic.getId());
    }

    private Long principalOf(Long clubId, String scope) {
        Club club = clubMapper.selectClubById(clubId);
        if (club == null || club.getMemberId() == null || club.getMemberId() <= 0) {
            throw new ServiceException("CLUB_PRINCIPAL_PAYEE_MISSING: " + scope + ", clubId=" + clubId);
        }
        return club.getMemberId();
    }
}
