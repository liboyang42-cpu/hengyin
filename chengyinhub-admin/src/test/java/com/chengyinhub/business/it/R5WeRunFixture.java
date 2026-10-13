package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IWeChatMiniSessionService;
import com.chengyinhub.business.service.support.VerifiedWeRunService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.test.util.ReflectionTestUtils;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 网络交换为替身；AES、序列校验、会员绑定与数据库事务运行真实代码。 */
public final class R5WeRunFixture {
    private static final ObjectMapper JSON=new ObjectMapper();
    private static final byte[] AES_BLOCK="0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AES_IV="fedcba9876543210".getBytes(StandardCharsets.UTF_8);
    private R5WeRunFixture() { }
    public static VerifiedWeRunService service() {
        VerifiedWeRunService service=new VerifiedWeRunService();UmsMemberMapper members=mock(UmsMemberMapper.class);IWeChatMiniSessionService wx=mock(IWeChatMiniSessionService.class);
        when(members.selectUmsMemberById(anyLong())).thenAnswer(call->{UmsMember member=new UmsMember();member.setWxOpenId("fixture-player-"+call.getArgument(0));return member;});
        when(wx.miniAppId()).thenReturn("fixture-app");when(wx.exchange(anyString())).thenAnswer(call->new IWeChatMiniSessionService.WeChatMiniSession(((String)call.getArgument(0)).replace("fixture-code-","fixture-player-"),Base64.getEncoder().encodeToString(AES_BLOCK)));
        ReflectionTestUtils.setField(service,"umsMemberMapper",members);ReflectionTestUtils.setField(service,"weChatMiniSessionService",wx);return service;
    }
    public static ObjectNode today(long now,long member,String episode,int steps) throws Exception {
        ObjectNode plain=JSON.createObjectNode();plain.putObject("watermark").put("appid","fixture-app").put("timestamp",now/1000);
        plain.putArray("stepInfoList").addObject().put("timestamp",Instant.ofEpochMilli(now).atZone(ZoneId.of("Asia/Shanghai")).toLocalDate().atStartOfDay(ZoneId.of("Asia/Shanghai")).toEpochSecond()).put("step",steps);
        byte[] raw=plain.toString().getBytes(StandardCharsets.UTF_8);int pad=16-raw.length%16;byte[] padded=Arrays.copyOf(raw,raw.length+pad);Arrays.fill(padded,raw.length,padded.length,(byte)pad);
        Cipher aes=Cipher.getInstance("AES/CBC/NoPadding");aes.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(AES_BLOCK,"AES"),new IvParameterSpec(AES_IV));
        return JSON.createObjectNode().put("episodeId",episode).put("code","fixture-code-"+member).put("encryptedData",Base64.getEncoder().encodeToString(aes.doFinal(padded))).put("iv",Base64.getEncoder().encodeToString(AES_IV));
    }
}
