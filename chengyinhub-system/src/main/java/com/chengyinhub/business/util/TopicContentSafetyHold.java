package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsTopic;

/**
 * 内容安全下架留置(AUDIT-G2-1)。
 *
 * <p>图片机审回调命中违规时,主题除 user_status=0 外还落 check_status=2 + {@link #REASON},
 * 记下「为什么下架」。status 保持不动(≠2):status=2 是人工驳回,走既有驳回口径。
 * 留置期间作者不能自助上架/编辑重发直接回到在架,只能提交一张 BIZ_TOPIC 复核单
 * (source={@link #SOURCE_RELIST}),人工审核通过才清掉留置(check_status=1)并恢复在架。
 *
 * <p>复用既有列,无 DDL:check_status 取值本就是 0 待审 / 1 通过 / 2 失败;
 * 历史读取闸(ApiPlayProgressController#topicHistoryBlocked)早已把 check_status=2 视为处置事实。
 */
public final class TopicContentSafetyHold {

    /** 下架原因(写入 cms_topic.reason,作者端可见) */
    public static final String REASON = "内容安全：主题图片疑似违规，已自动下架，需人工复核通过后才能重新上架";

    /** 作者申请复核上架的审核单来源 */
    public static final String SOURCE_RELIST = "content_safety_relist";

    /** 拒绝作者自助上架时的提示 */
    public static final String RELIST_REFUSED = "主题因图片未通过内容安全检测已被下架，不能直接上架。"
            + "已提交人工复核，请先更换违规图片；复核通过后将自动上架";

    private TopicContentSafetyHold() {}

    /** check_status=2 但未被人工驳回(status≠2)= 机审处置留置中,等待人工复核 */
    public static boolean isHeld(CmsTopic topic) {
        return topic != null && Integer.valueOf(2).equals(topic.getCheckStatus())
                && !Integer.valueOf(2).equals(topic.getStatus());
    }
}
