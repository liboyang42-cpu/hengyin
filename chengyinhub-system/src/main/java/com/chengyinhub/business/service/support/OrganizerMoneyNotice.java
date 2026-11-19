package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * MC-034:钱相关的主办方站内信(城瘾小助手系统会话,MSG_CARD + SystemNoticeCard 结构)。
 *
 * <p>只在业务事务<b>提交后</b>由 {@link CommittedBusinessNotice} 在独立事务里投递:
 * 事务回滚就不发,投递失败只记日志 / 告警,绝不反过来影响结算、收款或锁价。
 * 本类自己的任何异常(组装卡片、注册回调)也一律吞掉并记日志。
 */
@Component
public class OrganizerMoneyNotice {
    private static final Logger log = LoggerFactory.getLogger(OrganizerMoneyNotice.class);

    private final ObjectProvider<IImService> imService;
    private final CommittedBusinessNotice committedBusinessNotice;

    public OrganizerMoneyNotice(ObjectProvider<IImService> imService, CommittedBusinessNotice committedBusinessNotice) {
        this.imService = imService;
        this.committedBusinessNotice = committedBusinessNotice;
    }

    /** 活动结算完成,分润到账。 */
    public void activitySettled(Long payeeMemberId, Long activityId, String activityName, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) return;
        String item = SystemNoticeCard.item("活动", activityName);
        String outcome = item + "已结算，分润 " + moneyText(amount) + " 已到账";
        send("activity-settled-notice", activityId, payeeMemberId, "活动分润已到账", outcome,
                result("ACTIVITY", activityId, outcome));
    }

    /** 有人买票成功。 */
    public void ticketPurchased(Long organizerMemberId, String bizType, Long bizId, Long registrationId, String title) {
        String outcome = "有人报名了" + SystemNoticeCard.item("ACTIVITY".equals(bizType) ? "活动" : "主题", title);
        send("ticket-purchased-notice", registrationId, organizerMemberId, "有新报名", outcome,
                result(bizType, bizId, outcome));
    }

    /** 终价已锁定。 */
    public void priceLocked(Long ownerMemberId, Long topicId, String topicName) {
        String outcome = SystemNoticeCard.item("主题", topicName) + "终价已锁定";
        send("price-locked-notice", topicId, ownerMemberId, "终价已锁定", outcome,
                result("TOPIC", topicId, outcome));
    }

    static String moneyText(BigDecimal v) {
        BigDecimal x = v.setScale(2, RoundingMode.HALF_UP);
        return (x.signum() < 0 ? "−¥" : "¥") + x.abs().toPlainString();
    }

    private static Map<String, Object> result(String bizType, Long bizId, String outcome) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("bizType", bizType);
        r.put("bizId", bizId);
        r.put("outcome", outcome);
        return r;
    }

    void send(String eventKey, Long businessId, Long toMemberId, String title, String sub, Map<String, Object> result) {
        if (toMemberId == null || toMemberId <= 0) return;
        try {
            String json = SystemNoticeCard.json(title, sub, result);
            Runnable dispatch = () -> {
                try {
                    committedBusinessNotice.dispatchInNewTransaction(eventKey, businessId, () -> {
                        IImService im = imService.getIfAvailable();
                        if (im == null) return false;
                        Long messageId = im.sendSystem(toMemberId, ImMessage.MSG_CARD, title, json);
                        return messageId != null && messageId > 0L;
                    });
                } catch (Throwable failure) {
                    // afterCommit 里抛出会让已提交的业务调用方看到异常,这里必须吞掉。
                    log.warn("主办方站内信投递登记失败 event={} businessId={} type={}",
                            eventKey, businessId, failure.getClass().getSimpleName());
                }
            };
            if (TransactionSynchronizationManager.isSynchronizationActive()
                    && TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { dispatch.run(); }
                });
            } else {
                dispatch.run();
            }
        } catch (Throwable failure) {
            log.warn("主办方站内信未登记 event={} businessId={} type={}",
                    eventKey, businessId, failure.getClass().getSimpleName());
        }
    }
}
