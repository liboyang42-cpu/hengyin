package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CoopInvite;

import java.util.List;

/**
 * 商家↔俱乐部 承接状态派生(整改报告 2026-06-30 §3.4)。
 * 从 coop_invite(invite_type=1 商家→俱乐部)派生,不新增 schema:
 * <ul>
 *   <li>已承接:存在 status=1(已接受)的邀请。</li>
 *   <li>未承接:无已接受,但存在 status=0(待确认)的邀请(已邀约,等俱乐部确认)。</li>
 *   <li>自行承办:无任何进行中邀请(无邀请,或仅 status=2 拒绝 / 3 取消)。</li>
 * </ul>
 * 纯派生逻辑;调用方传入"该主题 invite_type=1 的邀请列表"。
 * 注:"已开放给俱乐部但暂无人申请"在无邀请记录时归入自行承办(尚无俱乐部介入);
 * 若后续要区分"已开放未承接",需 cms_topic 增"开放给俱乐部承接"标志(见审计 B-4)。
 */
public final class CoopAcceptanceStatus {

    public static final String ACCEPTED = "已承接";
    public static final String PENDING = "未承接";
    public static final String SELF_RUN = "自行承办";

    private CoopAcceptanceStatus() {
    }

    /**
     * @param invitesForTopic 该主题的 invite_type=1(商家→俱乐部)邀请列表;null/空=自行承办
     * @return 已承接 / 未承接 / 自行承办
     */
    public static String derive(List<CoopInvite> invitesForTopic) {
        if (invitesForTopic == null || invitesForTopic.isEmpty()) {
            return SELF_RUN;
        }
        boolean anyPending = false;
        for (CoopInvite inv : invitesForTopic) {
            Integer s = (inv == null) ? null : inv.getStatus();
            if (s != null && s == 1) {
                return ACCEPTED;          // 已接受优先
            }
            if (s != null && s == 0) {
                anyPending = true;        // 待确认
            }
        }
        return anyPending ? PENDING : SELF_RUN;
    }
}
