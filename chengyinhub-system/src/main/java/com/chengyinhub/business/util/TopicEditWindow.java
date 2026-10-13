package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;

import java.util.Date;

/**
 * 创作者编辑自己主题的「编辑窗口」三档口径。
 *
 * <p>真源 = vault《项目主页_主办与承接双身份方案_20260804》§6(2026-08-04 用户拍板):
 * <pre>
 *   「主题 update 在『已开卖』时的字段白名单 → 可改:名称/副标题/描述/封面/轮播图/类别/营销类型;
 *     不可改:开始结束日期 / 章节站点结构 / 价格。要改这三样只能下架 → 退款 → 重发,不做在编辑里」
 * </pre>
 *
 * <p>⚠️ 别把这条与同文档 §3.9「编辑窗口(<b>承接方</b>,D5)」搞混 —— 那节管的是
 * {@code cms_registration_merchant} 的报名编辑,不适用于主题 update。
 *
 * <p><b>判定顺序:先判档位,再判日期。</b>三档:
 * <ol>
 *   <li>{@link Scope#FULL} —— 草稿({@code is_draft=1})或 待审({@code status=0})或 驳回({@code status=2})
 *       ⇒ 全字段可改(章节/节点/票可整体重建),<b>不看 startDate</b>。这一档下主题从未公开开卖,重建无资损面。</li>
 *   <li>{@link Scope#WHITELIST} —— 已过审({@code status=1},含已上架)且未开始 ⇒ 只允许改白名单文案与图,
 *       日期 / 章节站点结构 / 票价一律拒收。已售票的主题被改这三样就是资损与客诉。</li>
 *   <li>只读 —— 已过审 <b>且</b> 已开始({@code startDate ≤ now})⇒ 抛异常。</li>
 * </ol>
 *
 * <p><b>为什么「已开始」不放在最前面拦</b>(2026-08-05 拍板,别照「一律只读」的字面改回去):
 * 「主题已开始」在业务上指的是<b>活着的、已经公开开卖的</b>主题;§6 那条白名单的整段语境也是
 * 「<b>已开卖</b>时」—— 它本来就不管草稿。而一个从没公开过的草稿,{@code startDate} 只是表单上的
 * 一个值,作者进编辑器<b>就是为了改它</b>。
 *
 * <p>⚠️ 这不是理论问题,它决定一个入口通不通:模板页「使用此模板」→
 * {@code /api/template/topic-template/use} → {@code copyCmsTopic} → {@code copyTopicBasicInfo},
 * 那个方法把 {@code status}/{@code user_status} 重置成 0,<b>却原样复制 startDate/endDate</b>。
 * 模板多半由一个已办完的主题 save-as 而来 ⇒ 复制出的新草稿一落地就带着过去的日期。
 * 日期闸若排在档位判定之前,这条入口<b>实现完仍是死的</b>,且存量已复制的草稿全部打不开。
 *
 * <p>⚠️「营销类型」不在本类管辖内:它是 {@code CoopInvite.shareMode}(每条邀约一份),
 * 全仓 {@code CmsTopic} 上没有这一列,改它走邀约侧接口,不是主题 update。
 */
public final class TopicEditWindow {

    public enum Scope { FULL, WHITELIST, SELLING_FROZEN }

    private TopicEditWindow() {
    }

    /**
     * @param topic   现库里的主题(null / 已软删 视为不存在)
     * @param isDraft {@code cms_topic.is_draft} —— 实体上没有这一列,只能由
     *                {@code CmsTopicMapper.selectTopicIsDraft} 单查后传进来
     * @param now     判「已开始」的当前时刻
     * @throws IllegalStateException 主题不存在 / 已开始(只读)
     */
    public static Scope resolve(CmsTopic topic, Integer isDraft, Date now) {
        if (topic == null || (topic.getDelFlag() != null && topic.getDelFlag() == 2)) {
            throw new IllegalStateException("主题不存在");
        }
        Integer status = topic.getStatus();
        boolean draft = isDraft != null && isDraft == 1;
        // 合同二：商业结构从 SELLING 起冻结。创作者编辑 DTO 没有运营字段，不能复用已过审
        // 的文案白名单绕开内容冻结；运营字段只允许走后台显式运维接口。
        TopicLifecycleState lifecycle;
        try {
            lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("主题生命周期未知或缺失，拒绝编辑", ex);
        }
        if (lifecycle.isSaleStarted()) {
            return Scope.SELLING_FROZEN;
        }
        // ★ 先判档位:从未过审的题(草稿/待审/驳回)一律 FULL,不看日期 —— 理由见类注释,
        //   把这一段挪到日期闸后面就会把模板「使用此模板」那条入口重新打死。
        // status 为 null 按「未过审」从宽给 FULL:这一档本来就是没开卖的题,不会碰到已售票。
        if (draft || status == null || status == 0 || status == 2) {
            return Scope.FULL;
        }
        Date start = topic.getStartDate();
        if (start != null && !start.after(now)) {
            throw new IllegalStateException("主题已开始，不能再编辑；要改内容请先下架后重发");
        }
        return Scope.WHITELIST;
    }
}
