package com.chengyinhub.business.service.support;

import java.util.*;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Owns self-host activity shell and existing ticket-service coordination; joins the original caller transaction. */
@Service
public class TopicSelfHostActivityService {
    private static final Logger logger = LoggerFactory.getLogger(TopicSelfHostActivityService.class);
    @Autowired
    private CmsTopicMapper cmsTopicMapper;
    @Autowired
    private com.chengyinhub.business.service.IOmsTicketService omsTicketService;
    @Autowired
    private CmsActivityMapper cmsActivityMapper;
    @Autowired
    private CmsRegistrationMapper cmsRegistrationMapper;
    @Autowired
    private com.chengyinhub.business.service.support.SettlementDebtLedger settlementDebtLedger;
    private static <T> List<T> nullToEmpty(List<T> rows) { return rows == null ? Collections.<T>emptyList() : rows; }

    public void refreshSelfHostActivity(Long topicId, Long memberId, String username) {
        CmsTopic fresh = cmsTopicMapper.selectCmsTopicById(topicId);
        if (fresh == null) {
            return;
        }
        CmsActivity probe = new CmsActivity();
        probe.setTopicId(topicId);
        probe.setMemberId(memberId);
        probe.setDelFlag(0);
        List<CmsActivity> activities = nullToEmpty(cmsActivityMapper.selectCmsActivityList(probe));
        if (activities.isEmpty()) {
            ensureSelfHostActivityShell(fresh, topicId, memberId, username);
            return;
        }
        for (CmsActivity activity : activities) {
            assertSoldActivityTimeLocationUntouched(fresh, activity);
            CmsActivity patch = new CmsActivity();
            patch.setId(activity.getId());
            patch.setName(fresh.getName());
            patch.setDescription(fresh.getDescription());
            patch.setImgUrl(fresh.getImgUrl());
            patch.setCategoryIds(fresh.getCategoryIds());
            patch.setAddressName(fresh.getAddressName());
            patch.setStartDate(fresh.getStartDate());
            patch.setEndDate(fresh.getEndDate());
            patch.setUpdateBy(username);
            patch.setUpdateTime(new Date());
            cmsActivityMapper.updateCmsActivity(patch);

            omsTicketService.syncTopicTicketsToActivity(topicId, activity.getId(), memberId);
        }
    }

    public void ensureSelfHostActivityShell(CmsTopic fresh, Long topicId, Long memberId, String username) {
        if (fresh == null) {
            return;
        }
        if (!Integer.valueOf(1).equals(fresh.getStatus())) {
            return;
        }
        boolean selfHost = fresh.getMerchantStatus() == null || fresh.getMerchantStatus() != 1;
        if (!selfHost) {
            return;
        }
        CmsActivity probe = new CmsActivity();
        probe.setTopicId(topicId);
        probe.setMemberId(memberId);
        probe.setDelFlag(0);
        if (!nullToEmpty(cmsActivityMapper.selectCmsActivityList(probe)).isEmpty()) {
            return;
        }
        generateSelfHostActivity(fresh, topicId, memberId, username);
    }

    public void ensureSelfHostActivityShell(Long topicId) {
        if (topicId == null || topicId <= 0L) {
            return;
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        if (topic == null) {
            return;
        }
        // 同步 master(#1078)后总控裁定 C:本方法在审核通过回调里、加入 review() 事务。generateSelfHostActivity
        // 开头的欠款逾期闸会抛 ServiceException,把审核结论一起回滚、审核台永远通不过 —— 这里先判:
        // 审核期间才变逾期 ⇒ 审核照常通过落库,这次不建壳(无票可售),欠款补足后下次编辑时 refreshSelfHostActivity 补建。
        if (settlementDebtLedger.hasOverdueDebt(topic.getMemberId())) {
            logger.warn("审核通过但主理人有逾期未补足欠款,本次不建自办活动壳 topicId={} memberId={}",
                    topicId, topic.getMemberId());
            return;
        }
        ensureSelfHostActivityShell(topic, topicId, topic.getMemberId(), topic.getCreateBy());
    }

    private void assertSoldActivityTimeLocationUntouched(CmsTopic fresh, CmsActivity activity) {
        if (activity == null || activity.getId() == null) {
            return;
        }
        boolean timeChanged = !sameSecondOrBothNull(fresh.getStartDate(), activity.getStartDate())
                || !sameSecondOrBothNull(fresh.getEndDate(), activity.getEndDate());
        boolean placeChanged = !Objects.equals(fresh.getAddressName(), activity.getAddressName());
        if ((timeChanged || placeChanged) && countActivityOpenSaleRegistrations(activity.getId()) > 0) {
            throw new ServiceException("该主题已有用户下单，时间与地点不能修改；文字、图片仍可改");
        }
    }

    private int countActivityOpenSaleRegistrations(Long activityId) {
        int n = 0;
        for (int status : new int[] { 0, 1, 2 }) {
            CmsRegistration q = new CmsRegistration();
            q.setOwnerType(2L);
            q.setOwnerId(activityId);
            q.setPaymentStatus(status);
            List<CmsRegistration> rows = cmsRegistrationMapper.selectCmsRegistrationList(q);
            n += rows == null ? 0 : rows.size();
        }
        return n;
    }

    private boolean sameSecondOrBothNull(Date left, Date right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.getTime() / 1000L == right.getTime() / 1000L;
    }

    private void generateSelfHostActivity(CmsTopic topic, Long topicId, Long memberId, String username) {
        // 9-16 拍板第 2 条:欠款逾期未补足 ⇒ 不能上架新活动。
        settlementDebtLedger.requireNoOverdueDebt(memberId);
        // 1) 生成活动：承接方=发布者本人，关联本主题，直接审核通过+上架(用户可报名)
        CmsActivity activity = new CmsActivity();
        activity.setMemberId(memberId);
        // activity.clubId activates the existing member gate. Public club topics
        // must remain public; only an explicitly member-only topic inherits it.
        if (Integer.valueOf(1).equals(topic.getClubMemberOnly())) {
            if (topic.getClubId() == null || topic.getClubId() <= 0L) {
                throw new ServiceException("成员限定主题的俱乐部资料不完整，无法生成场次");
            }
            activity.setClubId(topic.getClubId());
        }
        activity.setTopicId(topicId);
        activity.setName(topic.getName());
        activity.setDescription(topic.getDescription());
        activity.setImgUrl(topic.getImgUrl());
        activity.setCategoryIds(topic.getCategoryIds());
        activity.setAddressName(topic.getAddressName());
        activity.setStartDate(topic.getStartDate());
        activity.setEndDate(topic.getEndDate());
        activity.setStatus(1L);          // 审核通过
        activity.setPublishStatus(1L);   // 直接上架
        activity.setDelFlag(0);
        activity.setCreateBy(username);
        activity.setCreateTime(new Date());
        if (cmsActivityMapper.insertCmsActivity(activity) != 1
                || activity.getId() == null || activity.getId() <= 0L) {
            throw new IllegalStateException("自办活动创建失败，未生成有效活动身份");
        }
        Long activityId = activity.getId();

        // 2) 把主题票(ownerType=1)复制为活动票(ownerType=2)，否则活动无票可买(与进池调配中标共用同一 helper)
        omsTicketService.copyTopicTicketsToActivity(topicId, activityId, memberId);
    }
}
