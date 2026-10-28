package com.chengyinhub.business.notify;

import com.chengyinhub.business.service.IWxSubscribeMsgService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 微信订阅消息通道。
 *
 * 只做一件事:把 {@link NotifyMessage} 的**语义字段**翻译成
 * {@link IWxSubscribeMsgService} 的方法调用。微信模板字段(thing1/amount1 等)
 * 与 20 字/5 字长度限制仍留在 WxSubscribeMsgServiceImpl 里 —— 那是微信的规矩,
 * 不该爬到通用层来。
 *
 * ⚠️ 本类不得抛异常(见 {@link NotifyChannel})。IWxSubscribeMsgService 全链 best-effort,
 * 正常路径不会抛;这里的 catch 是防御下游将来被改坏,不是掩盖错误 —— 所以要 error 留痕。
 */
@Component
public class WxSubscribeChannel implements NotifyChannel {

    private static final Logger log = LoggerFactory.getLogger(WxSubscribeChannel.class);

    @Autowired
    private IWxSubscribeMsgService wx;

    @Override
    public boolean send(NotifyMessage m) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        try {
            switch (m.event()) {
                case REFUND:
                    return wx.notifyRefund(m.memberId(), m.get("topicName"), m.get("amount"));
                case SIGNUP_SUCCESS:
                    return wx.notifySignupSuccess(m.memberId(), m.get("topicName"));
                case BEFORE_START:
                    return wx.notifyBeforeStart(m.memberId(), m.get("topicName"), m.get("whenText"));
                case COOP_INVITED:
                    return wx.notifyCoopInvited(m.memberId(), m.get("topicName"), m.get("fromName"));
                case COOP_STATUS:
                    return wx.notifyCoopStatus(m.memberId(), m.get("topicName"), m.get("toName"),
                            m.get("statusText"), m.get("progress"));
                case CLUB_INTERESTED:
                    return wx.notifyClubInterested(m.memberId(), m.get("topicName"), m.get("clubName"));
                case RECRUIT:
                    return wx.notifyRecruit(m.memberId(), m.get("topicName"), m.get("activityTime"), m.get("tip"));
                case COOP_SETTLE:
                    return wx.notifyCoopSettle(m.memberId(), m.get("amount"), m.get("whenText"),
                            m.get("desc"), m.get("orderNo"));
                case OFFICIAL_BROADCAST:
                    return wx.notifyOfficialBroadcast(m.memberId(), m.get("title"), m.get("sub"));
                case ROAM_RECALL:
                    return wx.notifyRoamRecall(m.memberId(), m.getInt("distanceM", 0), m.getInt("poisFound", 0));
                case TEAM_STATUS:
                    // 队伍 P1 明确只走站内信，不占用微信订阅模板。
                    return false;
                case AFTERCARE_PLATFORM_TAKEOVER:
                    // 售后转平台同样只走站内信,不占用微信订阅模板。
                    return false;
                default:
                    // 新增事件但本通道没接 —— 不是错误,但要能被发现,否则「加了事件却没人发」会静默。
                    log.warn("[通知] 微信通道未处理该事件 event={},本条不发", m.event());
                    return false;
            }
        } catch (Throwable t) {
            // 绝不外抛:上游可能在 @Transactional 里,抛出会连带回滚已生效的业务(如已被微信受理的退款)。
            // 接 Throwable 而非 Exception,理由同 NotifyServiceImpl#fanout —— Error 一样会回滚事务。
            log.error("[通知] 微信通道异常 event={} memberId={} reason={}",
                    m.event(), m.memberId(), t.getClass().getSimpleName(), t);
            return false;
        }
    }

    /** Official broadcast only; no boolean-to-result inference or fanout. */
    public IWxSubscribeMsgService.OfficialBroadcastResult sendOfficialBroadcast(Long memberId, String title, String sub) {
        try {
            IWxSubscribeMsgService.OfficialBroadcastResult result = wx.notifyOfficialBroadcastResult(memberId, title, sub);
            return result == null ? officialUnknown() : result;
        } catch (Throwable failure) {
            return officialUnknown();
        }
    }

    private static IWxSubscribeMsgService.OfficialBroadcastResult officialUnknown() {
        return new IWxSubscribeMsgService.OfficialBroadcastResult(IWxSubscribeMsgService.OfficialBroadcastResult.State.UNKNOWN,
                IWxSubscribeMsgService.OfficialBroadcastResult.Reason.TRANSPORT_UNCONFIRMED, null);
    }

    /** 漫游召回模板是否已配置。RoamRecallService 据此决定要不要消耗重试次数。 */
    public boolean isRoamRecallReady() {
        return wx.isRoamRecallReady();
    }
}
