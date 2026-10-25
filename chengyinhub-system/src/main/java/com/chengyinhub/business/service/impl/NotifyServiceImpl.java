package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.notify.NotifyChannel;
import com.chengyinhub.business.notify.NotifyEvent;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.notify.WxSubscribeChannel;
import com.chengyinhub.business.service.INotifyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知 fanout:把一条语义消息投给所有已注册通道。
 *
 * ⚠️ <b>本类的核心职责是「不把异常放出去」。</b>见 {@link NotifyChannel} 的说明:
 * 上游多处在 @Transactional(rollbackFor=Exception.class) 里调用,任何异常穿透都可能
 * 回滚掉已生效的业务(已被微信受理的退款等)。所以逐通道 try,一条炸掉不影响其余通道。
 */
@Service
public class NotifyServiceImpl implements INotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyServiceImpl.class);

    /** 所有通道。当前为微信订阅 + 仅处理队伍事件的站内信通道。 */
    @Autowired
    private List<NotifyChannel> channels;

    @Autowired
    private WxSubscribeChannel wxChannel;

    @Autowired
    private com.chengyinhub.business.service.support.CommittedOfficialNoticeService committedNotices;


    /**
     * 投递给所有通道,返回「至少一条成功」。
     * 不短路:即使第一条成功,其余通道也要发 —— push 成功不代表站内信可以不写。
     */
    private boolean fanout(NotifyMessage m) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        boolean anyDelivered = false;
        for (NotifyChannel c : channels) {
            try {
                if (c.send(m)) anyDelivered = true;
            } catch (com.chengyinhub.common.config.RestoreDispatchGuard.Blocked paused) {
                throw paused;
            } catch (Throwable t) {
                // ⚠️ 这里必须是 Throwable,不能只接 RuntimeException/Exception。
                // 通道是**旁路**,上游是带 @Transactional(rollbackFor=Exception.class) 的资金路径。
                // 任何东西穿透出去都会回滚已生效的业务(典型:已被微信网关受理的退款)= 资损。
                // Error 也要接:P0-2 要接的 JPush SDK 缺类时抛的是 NoClassDefFoundError,
                // 只接 RuntimeException 会让它一路穿到 RefundServiceImpl。
                // 吞 Error 的代价可接受 —— OOM 这类问题会在主路径再次暴露,不会被永久藏住;
                // 而资损不可逆。两害相权,这里选择吞掉并带堆栈 error 留痕。
                log.error("[通知] 通道违约抛出异常 channel={} event={} memberId={} reason={}",
                        c.getClass().getSimpleName(), m.event(), m.memberId(),
                        t.getClass().getSimpleName(), t);
            }
        }
        if (!anyDelivered) {
            log.warn("[通知] 全部通道未送达 event={} memberId={} channelCount={}",
                    m.event(), m.memberId(), channels.size());
        }
        return anyDelivered;
    }

    @Override
    public boolean notifyRefund(Long memberId, String topicName, String amount) {
        return fanout(NotifyMessage.of(NotifyEvent.REFUND, memberId)
                .with("topicName", topicName).with("amount", amount));
    }

    @Override
    public boolean notifySignupSuccess(Long memberId, String topicName) {
        return fanout(NotifyMessage.of(NotifyEvent.SIGNUP_SUCCESS, memberId)
                .with("topicName", topicName));
    }

    @Override
    public boolean notifyBeforeStart(Long memberId, String topicName, String whenText) {
        return fanout(NotifyMessage.of(NotifyEvent.BEFORE_START, memberId)
                .with("topicName", topicName).with("whenText", whenText));
    }

    @Override
    public com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery notifyBeforeStart(
            Long ticketId, Long memberId, String topicName, java.util.Date startsAt) {
        try {
            return committedNotices.beforeStart(ticketId, memberId, topicName, startsAt);
        } catch (Throwable failure) {
            // Same best-effort boundary; a failed/unknown local ack never means the provider did not execute.
            log.error("[通知] 出发提醒原意图待核实 ticketId={} memberId={} reason={}",
                    ticketId, memberId, failure.getClass().getSimpleName());
            return new com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery(null, "UNKNOWN", false);
        }
    }

    @Override
    public boolean notifyCoopInvited(Long toMemberId, String topicName, String fromName) {
        return fanout(NotifyMessage.of(NotifyEvent.COOP_INVITED, toMemberId)
                .with("topicName", topicName).with("fromName", fromName));
    }

    @Override
    public boolean notifyClubInterested(Long toMemberId, String topicName, String clubName) {
        return fanout(NotifyMessage.of(NotifyEvent.CLUB_INTERESTED, toMemberId)
                .with("topicName", topicName).with("clubName", clubName));
    }

    @Override
    public boolean notifyCoopStatus(Long fromMemberId, String topicName, String toName,
                                    String statusText, String progress) {
        return fanout(NotifyMessage.of(NotifyEvent.COOP_STATUS, fromMemberId)
                .with("topicName", topicName).with("toName", toName)
                .with("statusText", statusText).with("progress", progress));
    }

    @Override
    public boolean notifyRecruit(Long publisherId, String topicName, String activityTime, String tip) {
        return fanout(NotifyMessage.of(NotifyEvent.RECRUIT, publisherId)
                .with("topicName", topicName).with("activityTime", activityTime).with("tip", tip));
    }

    @Override
    public boolean notifyCoopSettle(Long merchantId, String amount, String whenText,
                                    String desc, String orderNo) {
        return fanout(NotifyMessage.of(NotifyEvent.COOP_SETTLE, merchantId)
                .with("amount", amount).with("whenText", whenText)
                .with("desc", desc).with("orderNo", orderNo));
    }

    @Override
    public boolean notifyOfficialBroadcast(Long memberId, String title, String sub) {
        return fanout(NotifyMessage.of(NotifyEvent.OFFICIAL_BROADCAST, memberId)
                .with("title", title).with("sub", sub));
    }

    @Override
    public boolean notifyRoamRecall(Long memberId, int distanceM, int poisFound) {
        return fanout(NotifyMessage.of(NotifyEvent.ROAM_RECALL, memberId)
                .with("distanceM", distanceM).with("poisFound", poisFound));
    }

    @Override
    public boolean notifyTeamStatus(Long memberId, String teamTitle, String statusText) {
        return fanout(NotifyMessage.of(NotifyEvent.TEAM_STATUS, memberId)
                .with("teamTitle", teamTitle).with("statusText", statusText));
    }

    @Override
    public boolean notifyAftercarePlatformTakeover(Long memberId, String amount) {
        return fanout(NotifyMessage.of(NotifyEvent.AFTERCARE_PLATFORM_TAKEOVER, memberId)
                .with("amount", amount == null ? "" : amount));
    }

    @Override
    public boolean isRoamRecallReady() {
        // ⚠️ 名不副实的地方要写在明处:这条**只问微信通道**,不是「问所有通道」。
        // 就绪查询没做成 NotifyChannel 的方法 —— 现在只有一条通道,加个接口方法是没人要的抽象。
        // P0-2 加 App push 时必须回来改这里,否则「微信模板没配」会让已能推送的召回被判成不可发。
        return wxChannel.isRoamRecallReady();
    }
}
