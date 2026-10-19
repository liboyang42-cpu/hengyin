package com.chengyinhub.business.notify;

import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.service.IImService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 队伍通知的站内信通道；复用现有系统会话，不创建微信订阅模板。 */
@Component
public class InAppNotifyChannel implements NotifyChannel {

    private static final Logger log = LoggerFactory.getLogger(InAppNotifyChannel.class);

    @Autowired
    private IImService imService;
    @Autowired
    private com.chengyinhub.business.service.impl.AlarmService alarmService;

    @Override
    public String channelId() { return "IN_APP"; }

    @Override
    public NotifyResult sendResult(NotifyMessage message) {
        return send(message)
                ? NotifyResult.of(channelId(), NotifyResult.Status.IN_APP_STORED, null)
                : NotifyResult.failed(channelId(), null);
    }

    @Override
    public boolean send(NotifyMessage message) {
        if (message.event() == NotifyEvent.OFFICIAL_BROADCAST) {
            try {
                Long id = imService.sendSystem(message.memberId(), ImMessage.MSG_TEXT,
                        message.get("title") + "\n" + message.get("sub"), null);
                if (id != null && id > 0) return true;
                officialFailure(message.memberId(), "NO_MESSAGE_ID");
                return false;
            } catch (Throwable failure) {
                log.error("[通知] 业务结果站内信异常 memberId={} type={}",
                        message.memberId(), failure.getClass().getSimpleName());
                officialFailure(message.memberId(), failure.getClass().getSimpleName());
                return false;
            }
        }
        if (message.event() == NotifyEvent.AFTERCARE_PLATFORM_TAKEOVER) {
            return sendAftercareTakeover(message);
        }
        if (message.event() == NotifyEvent.REFUND) {
            return sendRefund(message);
        }
        if (message.event() != NotifyEvent.TEAM_STATUS) return false;
        try {
            JSONObject extra = new JSONObject();
            extra.put("cardType", "teamStatus");
            extra.put("teamTitle", message.get("teamTitle"));
            extra.put("statusText", message.get("statusText"));
            Long id = imService.sendSystem(message.memberId(), ImMessage.MSG_CARD,
                    message.get("statusText"), extra.toJSONString());
            return id != null && id > 0;
        } catch (Throwable t) {
            log.error("[通知] 队伍站内信异常 memberId={} reason={}",
                    message.memberId(), t.getClass().getSimpleName(), t);
            return false;
        }
    }

    private void officialFailure(Long memberId, String failureType) {
        try {
            alarmService.alarm("业务结果站内信未送达", "event=OFFICIAL_BROADCAST memberId="
                    + memberId + " type=" + failureType);
        } catch (Throwable failure) {
            log.error("Business IM alarm failed memberId={} type={}", memberId, failure.getClass().getSimpleName());
        }
    }

    /**
     * [6-20] 退款到账:补一条站内信。
     *
     * <p>原先退款事件只走微信订阅消息 —— 没订阅(或订阅次数用完/拒收)的玩家等于零通知,
     * 钱到了也不知道。站内信不依赖订阅授权,是退款这条资金事实的兜底告知。
     */
    private boolean sendRefund(NotifyMessage message) {
        try {
            String amount = message.get("amount");
            String content = amount != null && !amount.isEmpty()
                    ? "退款 ¥" + amount + " 已退回，到账时间以支付渠道通知为准。"
                    : "你的订单退款已处理，到账时间以支付渠道通知为准。";
            Long id = imService.sendSystem(message.memberId(), ImMessage.MSG_TEXT, content, null);
            return id != null && id > 0;
        } catch (Throwable t) {
            log.error("[通知] 退款站内信异常 memberId={} reason={}",
                    message.memberId(), t.getClass().getSimpleName(), t);
            return false;
        }
    }

    /** 售后超时转平台:发一条系统文本消息,玩家在消息列表里能看到完整一句。 */
    private boolean sendAftercareTakeover(NotifyMessage message) {
        try {
            String amount = message.get("amount");
            String content = "商家未在 24 小时内回应你的退款申请，平台客服已接手处理。"
                    + (amount != null && !amount.isEmpty() ? "退款金额 ¥" + amount + "。" : "");
            Long id = imService.sendSystem(message.memberId(), ImMessage.MSG_TEXT, content, null);
            return id != null && id > 0;
        } catch (Throwable t) {
            log.error("[通知] 售后转平台站内信异常 memberId={} reason={}",
                    message.memberId(), t.getClass().getSimpleName(), t);
            return false;
        }
    }
}
