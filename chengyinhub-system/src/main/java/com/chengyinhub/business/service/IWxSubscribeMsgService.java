package com.chengyinhub.business.service;

import java.util.Map;

/**
 * 微信订阅消息发送(F8)。
 *
 * 复用内容安全的 access_token;模板ID 由 application 配置注入,未配置则该类消息跳过(no-op,零运行影响)。
 * 注:真正下发还需小程序端先 wx.requestSubscribeMessage 取得用户一次性授权(前端工作);
 *     且模板字段名(thing1/amount2 等)须与微信公众平台所建模板一致,配置时对齐。
 */
public interface IWxSubscribeMsgService {
    // ⚠️ 2026-08-20:8 个原本 void 的方法改为返回 boolean。
    //    语义与 send 完全一致 —— true = 微信真的受理了,不是「调用没报错」。
    //    改动原因:上层 NotifyChannel 要 fanout 多条通道并判「至少一条成功」,void 拿不到这个信号。
    //    sendToMember 本来就返回 boolean,此前只是被丢弃。行为不变,只是不再扔掉已有的信息。

    /**
     * 底层发送(best-effort:失败/未配置/无 openid 均**不抛异常**,只记日志)。fields 自动包成 {key:{value}}。
     *
     * ⚠️ 返回值 = 微信是否真的受理(errcode==0),**不是"调用没报错"**。
     * 「不抛」是有意设计:退款路径在 @Transactional(rollbackFor=Exception.class) 里调它,
     * 抛出会把已被微信网关受理的退款连带回滚 = 资损。所以调用方**没法靠 try/catch 判断成败**,
     * 需要判成败的只能看这个返回值。不关心成败的调用方直接忽略即可。
     */
    boolean send(String openid, String templateId, String page, Map<String, String> fields);

    /** 退款到账提醒(发给某成员) */
    boolean notifyRefund(Long memberId, String topicName, String amount);

    /** 报名成功提醒(发给某成员) */
    boolean notifySignupSuccess(Long memberId, String topicName);

    /** 出发前提醒(发给某成员)。返回是否真的下发成功 —— 调用方据此决定要不要重试(见 send 的说明)。 */
    boolean notifyBeforeStart(Long memberId, String topicName, String whenText);

    // —— 合作/定价订阅(F3-2 骨架;字段参数名待与实际模板对齐) ——

    /** 合作被邀约 → 通知受邀方 */
    boolean notifyCoopInvited(Long toMemberId, String topicName, String fromName);

    /** 俱乐部承接意向 → 模板主题创建者(best-effort;模板 tplClubInterest 未配置时静默跳过) */
    boolean notifyClubInterested(Long toMemberId, String topicName, String clubName);

    /** 邀约状态变更(接受/拒绝)→ 通知发起方 */
    boolean notifyCoopStatus(Long fromMemberId, String topicName, String toName, String statusText, String progress);

    /** 新活动招募(招募到期→进定价/回草稿)→ 通知发布者。字段:活动名称/活动时间/温馨提示 */
    boolean notifyRecruit(Long publisherId, String topicName, String activityTime, String tip);

    /** 订单结算到账 → 通知商家 */
    boolean notifyCoopSettle(Long merchantId, String amount, String whenText, String desc, String orderNo);

    /** 官方活动/公告通知，仅使用 tplOfficialEvent 专用模板；缺配置不回退招募模板。 */
    boolean notifyOfficialBroadcast(Long memberId, String title, String sub);

    /** Typed official-only send; old boolean callers retain their existing contract. */
    OfficialBroadcastResult notifyOfficialBroadcastResult(Long memberId, String title, String sub);

    /** Official-only outcome. Contains no recipient, credential, payload or provider diagnostic text. */
    record OfficialBroadcastResult(State state, Reason reason, Integer errcode) {
        public enum State { NOT_SUBMITTED, REJECTED, ACCEPTED, UNKNOWN }
        public enum Reason {
            TEMPLATE_MISSING, IDENTITY_MISSING, TOKEN_MISSING, LOCAL_PREPARATION_FAILED,
            PROVIDER_ACCEPTED, PROVIDER_REJECTED, RESPONSE_UNCONFIRMED, TRANSPORT_UNCONFIRMED
        }
        public OfficialBroadcastResult {
            if (state == null || reason == null) throw new IllegalArgumentException("missing outcome");
            boolean valid = switch (state) {
                case ACCEPTED -> reason == Reason.PROVIDER_ACCEPTED && Integer.valueOf(0).equals(errcode);
                case REJECTED -> reason == Reason.PROVIDER_REJECTED && errcode != null && errcode != 0;
                case UNKNOWN -> errcode == null && (reason == Reason.RESPONSE_UNCONFIRMED || reason == Reason.TRANSPORT_UNCONFIRMED);
                case NOT_SUBMITTED -> errcode == null && (reason == Reason.TEMPLATE_MISSING || reason == Reason.IDENTITY_MISSING
                        || reason == Reason.TOKEN_MISSING || reason == Reason.LOCAL_PREPARATION_FAILED);
            };
            if (!valid) throw new IllegalArgumentException("inconsistent outcome");
        }
    }

    /** 漫游完成 24–72h 召回；返回微信是否明确受理，未配模板/未授权均为 false。 */
    boolean notifyRoamRecall(Long memberId, int distanceM, int poisFound);

    /** 专用召回模板是否已配置；未就绪时调度不得消耗重试次数。 */
    boolean isRoamRecallReady();
}
