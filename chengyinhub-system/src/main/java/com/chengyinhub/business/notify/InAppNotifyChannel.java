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
        if (message.event() == NotifyEvent.CITY_TERRITORY) {
            return sendCityTerritory(message);
        }
        if (message.event() == NotifyEvent.MERCHANT_REVIEW) {
            return sendMerchantReview(message);
        }
        if (message.event() == NotifyEvent.GAME_DUEL) {
            try {
                JSONObject extra = new JSONObject();
                extra.put("cardType", "gameDuel");
                extra.put("duelId", message.get("duelId"));
                Long id = imService.sendSystem(message.memberId(), ImMessage.MSG_CARD, message.get("text"), extra.toJSONString());
                return id != null && id > 0;
            } catch (Throwable t) {
                log.error("[通知] 对决站内信异常 memberId={} reason={}", message.memberId(), t.getClass().getSimpleName());
                return false;
            }
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

    /**
     * 城市大富翁地产事件:系统会话里的一张卡片(cardType=cityTerritory)。content 是完整一句,旧客户端按文本展示;
     * extra 给新客户端跳转到地产(territoryId)与区分事件(kind)。
     */
    private boolean sendCityTerritory(NotifyMessage message) {
        try {
            JSONObject extra = new JSONObject();
            extra.put("cardType", "cityTerritory");
            extra.put("kind", message.get("kind"));
            String territoryId = message.get("territoryId");
            extra.put("territoryId", territoryId == null || territoryId.isEmpty() ? null : territoryId);
            extra.put("title", message.get("title"));
            extra.put("points", message.getInt("points", 0));
            extra.put("currency", "POINTS");
            Long id = imService.sendSystem(message.memberId(), ImMessage.MSG_CARD, message.get("text"), extra.toJSONString());
            return id != null && id > 0;
        } catch (Throwable t) {
            log.error("[通知] 城市大富翁站内信异常 memberId={} reason={}",
                    message.memberId(), t.getClass().getSimpleName(), t);
            return false;
        }
    }

    /** MERCHANT-14:入驻审核结果写进消息列表,告诉商家结果和下一步。 */
    private boolean sendMerchantReview(NotifyMessage message) {
        try {
            Long id = imService.sendSystem(message.memberId(), ImMessage.MSG_TEXT,
                    merchantReviewText("1".equals(message.get("approved")), message.get("reason")), null);
            return id != null && id > 0;
        } catch (Throwable t) {
            log.error("[通知] 商家入驻审核站内信异常 memberId={} reason={}",
                    message.memberId(), t.getClass().getSimpleName());
            return false;
        }
    }

    static String merchantReviewText(boolean approved, String reason) {
        if (approved) {
            return "你的商家入驻申请已通过。下一步：在「我的店铺」里设置据点、小游戏和优惠券。";
        }
        String trimmed = reason == null ? "" : reason.trim();
        if (trimmed.length() > 120) trimmed = trimmed.substring(0, 120);
        return "你的商家入驻申请未通过。" + (trimmed.isEmpty() ? "" : "原因：" + trimmed + "。")
                + "请完善资料后重新申请。";
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
