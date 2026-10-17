package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** 认证材料只在本次调用流转，不写入玩法状态或回执。 */
@Service
public class VerifiedWeRunService {
    @Autowired private IWeChatMiniSessionService weChatMiniSessionService;
    @Autowired private UmsMemberMapper umsMemberMapper;

    public WeRunDecryptor.VerifiedWeRunReading verify(Long memberId,JsonNode cipher,long now) {
        ThemeRuntimeConfig.fields(cipher,ThemeRuntimeConfig.set("episodeId","code","encryptedData","iv"));
        for(String field:new String[]{"code","encryptedData","iv"})
            if(!cipher.path(field).isTextual() || cipher.path(field).asText().isEmpty() || cipher.path(field).asText().length()>65536)
                throw new IllegalArgumentException("微信运动认证数据不完整");
        UmsMember member=umsMemberMapper.selectUmsMemberById(memberId);
        if(member==null || member.getWxOpenId()==null || member.getWxOpenId().trim().isEmpty())throw new IllegalStateException("当前会员未绑定小程序身份");
        IWeChatMiniSessionService.WeChatMiniSession wx=weChatMiniSessionService.exchange(cipher.path("code").asText());
        if(wx==null || !member.getWxOpenId().equals(wx.getOpenId()))throw new IllegalArgumentException("微信运动不属于当前登录会员");
        return WeRunDecryptor.decryptVerified(wx.getSessionKey(),cipher.path("encryptedData").asText(),cipher.path("iv").asText(),weChatMiniSessionService.miniAppId(),now);
    }
}
