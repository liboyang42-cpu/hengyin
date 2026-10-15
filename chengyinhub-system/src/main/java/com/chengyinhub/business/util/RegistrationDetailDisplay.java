package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.RegistrationEntitlement;
import com.chengyinhub.business.security.PiiCryptoService;
import com.chengyinhub.business.service.ICmsTopicChapterService;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;

/**
 * 订单详情 /info 出参展示 enrich(脱敏、支付方式文案、退款截止文案、权益章节名)。
 * 不改判定口径,只拼用户可见字段。
 */
public final class RegistrationDetailDisplay {

    private RegistrationDetailDisplay() {
    }

    public static void enrich(CmsRegistration registration, ICmsTopicChapterService chapterService) {
        if (registration == null) {
            return;
        }
        registration.setPhone(PiiCryptoService.maskPhone(registration.getPhone()));
        registration.setPaymentTypeLabel(paymentTypeLabel(registration.getPaymentType()));
        registration.setRefundDeadlineDisplay(refundDeadlineDisplay(registration.getRefundInfo(), registration));
        enrichEntitlements(registration.getEntitlements(), chapterService);
    }

    static String paymentTypeLabel(Integer paymentType) {
        if (paymentType == null) {
            return null;
        }
        if (paymentType == 0) {
            return "积分";
        }
        if (paymentType == 1) {
            return "微信";
        }
        if (paymentType == 2) {
            return "微信+积分";
        }
        return null;
    }

    static String refundDeadlineDisplay(RefundPolicy.Verdict verdict, CmsRegistration registration) {
        if (verdict == null) {
            return null;
        }
        if (verdict.refundable && verdict.deadline != null) {
            String deadlineText = "可免费取消至 " + formatCnDeadline(verdict.deadline);
            // F-W5-07:¥0 免费单没有「全额退/不退」可言,不能挂收费票的退款规则句。
            if (isFreeOrder(registration)) {
                return "免费报名，" + deadlineText;
            }
            // 5.9:带队场次单的退款卡要把规则本身说全(玩家在订单页也要看得到「未到场不退」);
            // ③ 快照单与主题单的展示保持原样。
            if (registration != null && RefundPolicy.isGuidedSessionOrder(
                    registration.getOwnerType(), registration.getPurchaseKind())) {
                return RefundPolicy.SESSION_REFUND_RULE_TEXT + "（" + deadlineText + "）";
            }
            return deadlineText;
        }
        return verdict.reason;
    }

    /** 实付 0 元且没有用积分抵扣 = 免费单;金额缺失(存量/未回填)按收费单展示,不擅自改口径。 */
    static boolean isFreeOrder(CmsRegistration registration) {
        if (registration == null || registration.getPayableAmount() == null
                || registration.getPayableAmount().signum() != 0) {
            return false;
        }
        Long points = registration.getPointUsed();
        return points == null || points <= 0L;
    }

    private static String formatCnDeadline(Date deadline) {
        SimpleDateFormat fmt = new SimpleDateFormat("M月d日 HH:mm");
        fmt.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
        return fmt.format(deadline);
    }

    static void enrichEntitlements(List<RegistrationEntitlement> entitlements,
                                   ICmsTopicChapterService chapterService) {
        if (entitlements == null || entitlements.isEmpty()) {
            return;
        }
        for (RegistrationEntitlement e : entitlements) {
            e.setStatusLabel(entitlementStatusLabel(e.getStatus()));
            if (chapterService == null || e.getChapterId() == null) {
                continue;
            }
            CmsTopicChapter chapter = chapterService.selectCmsTopicChapterById(e.getChapterId());
            if (chapter != null && chapter.getName() != null) {
                e.setChapterName(chapter.getName());
            } else {
                e.setChapterName("章节 " + e.getChapterId());
            }
        }
    }

    static String entitlementStatusLabel(Integer status) {
        if (status == null) {
            return "";
        }
        if (status == 0) {
            return "待核销";
        }
        if (status == 1) {
            return "已核销";
        }
        if (status == 2) {
            return "已失效";
        }
        return "";
    }
}
