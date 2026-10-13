package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.MerchantCrmBroadcast;
import com.chengyinhub.business.domain.MerchantCrmCampaign;
import com.chengyinhub.business.service.IImService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** CRM 专用站内卡片；不借用官方群发或群聊语义，并返回真实 im_message.id。 */
@Service
public class MerchantCrmNotificationAdapter {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private IImService imService;

    public Long send(Long memberId, MerchantCrmCampaign campaign) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("cardType", "merchantCrmCampaign");
        card.put("campaignId", campaign.getId());
        card.put("merchantName", campaign.getMerchantNameSnapshot());
        card.put("title", campaign.getTitle());
        try {
            Long receipt = imService.sendSystem(memberId, ImMessage.MSG_CARD,
                    campaign.getContent(), JSON.writeValueAsString(card));
            if (receipt == null || receipt <= 0) throw new IllegalStateException("站内消息未返回真实回执");
            return receipt;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CRM 卡片序列化失败", e);
        }
    }

    /**
     * 定向广播卡片:内容是商家自己写的通知,标题用商家名(卡片渲染只认 title/sub)。
     * 与触达卡片同构,只换 cardType/broadcastId,不借用官方群发语义。
     */
    public Long sendBroadcast(Long memberId, MerchantCrmBroadcast broadcast) {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("cardType", "merchantCrmBroadcast");
        card.put("broadcastId", broadcast.getId());
        card.put("merchantName", broadcast.getMerchantNameSnapshot());
        card.put("title", broadcast.getMerchantNameSnapshot());
        card.put("sub", broadcast.getContent());
        try {
            Long receipt = imService.sendSystem(memberId, ImMessage.MSG_CARD,
                    broadcast.getContent(), JSON.writeValueAsString(card));
            if (receipt == null || receipt <= 0) throw new IllegalStateException("站内消息未返回真实回执");
            return receipt;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("CRM 卡片序列化失败", e);
        }
    }
}
