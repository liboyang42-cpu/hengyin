package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.UmsCommentMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * FX-10(9-18 拍板「收回」):报名退款成功后,收回靠这张票拿到的首评积分(event_type=4)。
 *
 * <p>收回对象 = 这张票能证明「参与过」的评价对象:主题票 → 该主题;场次票 → 该场次 + 所属主题。
 * 会员对该对象还有有效参与((已支付且报名有效) 或 已核销,含这张票自己核销过)就不收 —— 仍然参与过。
 *
 * <p>幂等:冲回复用 {@link RedemptionRewardClawbackService#clawPoints} —— 原发放行摘出判重键后再也找不到,
 * 同一笔首评积分最多扣一次;先锁会员行,并发退同一会员的多张票串行。积分已花掉不够扣时扣到 0,差额打日志。
 * 调用方必须在报名已置 registration_status=3 之后、同一事务里调用。
 */
@Service
public class FirstReviewPointsClawbackService {

    private static final Logger logger = LoggerFactory.getLogger(FirstReviewPointsClawbackService.class);
    static final long EVENT_FIRST_REVIEW = 4L;

    @Autowired private UmsMemberMapper umsMemberMapper;
    @Autowired private UmsCommentMapper umsCommentMapper;
    @Autowired private CmsActivityMapper cmsActivityMapper;
    @Autowired private RedemptionRewardClawbackService clawback;
    @Autowired private PlatformTransactionManager transactionManager;

    public void onRegistrationRefunded(CmsRegistration registration) {
        if (registration == null || registration.getMemberId() == null
                || registration.getOwnerType() == null || registration.getOwnerId() == null) {
            return;
        }
        Long memberId = registration.getMemberId();
        // 总控 9-18:退款不因积分回收失败而回滚。回收整段跑在 savepoint(NESTED)里:
        // 中途失败只回滚到 savepoint —— 不会留下「判重键已摘/分已扣但没流水」的半截 —— 退款照常提交,ERROR 留痕交人工。
        TransactionTemplate savepoint = new TransactionTemplate(transactionManager);
        savepoint.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        try {
            savepoint.executeWithoutResult(status -> clawAll(registration, memberId));
        } catch (org.springframework.dao.PessimisticLockingFailureException
                | org.springframework.transaction.TransactionException e) {
            // 死锁/锁等待超时会让 InnoDB 回滚整个外层事务,savepoint 已不存在 —— 吞掉就会在隐式新事务里
            // 提交退款的后半截。这类必须抛出,让整笔退款一起回滚重试。
            throw e;
        } catch (RuntimeException e) {
            logger.error("[积分回收失败] 退款收回首评积分失败,已回滚回收部分,退款照常,需人工核对 memberId={} registrationId={}",
                    memberId, registration.getId(), e);
        }
    }

    private void clawAll(CmsRegistration registration, Long memberId) {
        umsMemberMapper.lockMemberById(memberId);
        clawIfNoLongerParticipating(memberId, registration.getOwnerType(), registration.getOwnerId(), registration.getId());
        if (Long.valueOf(2L).equals(registration.getOwnerType())) {
            CmsActivity activity = cmsActivityMapper.selectCmsActivityById(registration.getOwnerId());
            if (activity != null && activity.getTopicId() != null) {
                clawIfNoLongerParticipating(memberId, 1L, activity.getTopicId(), registration.getId());
            }
        }
    }

    private void clawIfNoLongerParticipating(Long memberId, Long ownerType, Long ownerId, Long registrationId) {
        if (umsCommentMapper.hasValidParticipation(ownerType, ownerId, memberId)) {
            return;
        }
        List<Long> commentIds = umsCommentMapper.selectReviewCommentIds(ownerType, ownerId, memberId);
        if (commentIds == null) {
            return;
        }
        for (Long commentId : commentIds) {
            Map<String, Object> result = clawback.clawPoints(memberId, EVENT_FIRST_REVIEW, commentId,
                    "退款收回:活动评价/投票", "refund");
            if (result == null) {
                continue;
            }
            long shortfall = ((Number) result.get("shortfall")).longValue();
            if (shortfall > 0) {
                logger.warn("[积分回收不足] 退款收回首评积分,余额不够扣 memberId={} registrationId={} commentId={} requested={} deducted={}",
                        memberId, registrationId, commentId, result.get("requested"), result.get("deducted"));
            }
        }
    }
}
