package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ImConversation;
import com.chengyinhub.business.domain.ImConversationMember;
import com.chengyinhub.business.domain.ImMessage;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.ImMessageMapper;
import com.chengyinhub.business.service.IUmsMemberService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台 IM 的越权与 PII。
 *
 * <p>★ 为什么纯看前端发现不了这个洞:adminListConversations 只列 TYPE_MERCHANT(客服会话),
 * 所以前端**永远只会**递交客服会话 ID,页面上怎么点都越不了权。但 adminListMessages 收的是
 * 裸主键,持 business:im:list 的账号直接打接口、把 ID 换成别的会话,就能读到 TYPE_SINGLE
 * ——用户与用户之间的私聊全文。C 端 listMessages 早有「无权查看该会话」守卫,后台这条漏着。
 */
class ImAdminAccessTest {

    private static final Long MERCHANT_CONV = 300L;   // 客服会话:后台该看
    private static final Long PRIVATE_CONV = 301L;    // 用户私聊:后台绝不该看
    private static final String REAL_PHONE = "13812341234";

    private ImConversationMapper conversationMapper;
    private ImMessageMapper messageMapper;
    private ImConversationMemberMapper memberMapper;
    private IUmsMemberService umsMemberService;
    private ImBlockMapper blockMapper;
    private ImServiceImpl svc;

    @BeforeEach
    void setUp() {
        conversationMapper = mock(ImConversationMapper.class);
        messageMapper = mock(ImMessageMapper.class);
        memberMapper = mock(ImConversationMemberMapper.class);
        umsMemberService = mock(IUmsMemberService.class);

        svc = new ImServiceImpl();
        ReflectionTestUtils.setField(svc, "conversationMapper", conversationMapper);
        ReflectionTestUtils.setField(svc, "messageMapper", messageMapper);
        ReflectionTestUtils.setField(svc, "memberMapper", memberMapper);
        blockMapper = mock(ImBlockMapper.class);
        ReflectionTestUtils.setField(svc, "blockMapper", blockMapper);
        ReflectionTestUtils.setField(svc, "umsMemberService", umsMemberService);
    }

    private ImConversation conv(Long id, int type) {
        ImConversation c = new ImConversation();
        c.setId(id);
        c.setType(type);
        return c;
    }

    // ---------- 越权 ----------

    @Test
    void 后台不得读用户之间的私聊() {
        when(conversationMapper.selectImConversationById(PRIVATE_CONV))
                .thenReturn(conv(PRIVATE_CONV, ImConversation.TYPE_SINGLE));

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> svc.adminListMessages(PRIVATE_CONV, null, 30));
        assertEquals("无权查看该会话", e.getMessage());

        // 关键:不能只是"没返回",而是压根不该去查消息表
        verify(messageMapper, never()).selectPage(eq(PRIVATE_CONV), any(), anyInt());
        verify(messageMapper, never()).selectPage(anyLong(), any(), anyInt());
    }

    @Test
    void 后台不得读群聊() {
        when(conversationMapper.selectImConversationById(PRIVATE_CONV))
                .thenReturn(conv(PRIVATE_CONV, ImConversation.TYPE_GROUP));
        assertThrows(RuntimeException.class, () -> svc.adminListMessages(PRIVATE_CONV, null, 30));
        verify(messageMapper, never()).selectPage(anyLong(), any(), anyInt());
    }

    @Test
    void 不存在的会话id也不得放行() {
        when(conversationMapper.selectImConversationById(999999L)).thenReturn(null);
        assertThrows(RuntimeException.class, () -> svc.adminListMessages(999999L, null, 30));
        verify(messageMapper, never()).selectPage(anyLong(), any(), anyInt());
    }

    @Test
    void 客服会话仍然读得到_守卫不能顺手废掉功能() {
        when(conversationMapper.selectImConversationById(MERCHANT_CONV))
                .thenReturn(conv(MERCHANT_CONV, ImConversation.TYPE_MERCHANT));
        ImMessage m = new ImMessage();
        m.setId(1L);
        m.setSenderId(5L);
        when(messageMapper.selectPage(eq(MERCHANT_CONV), any(), anyInt()))
                .thenReturn(new ArrayList<>(Collections.singletonList(m)));

        Map<String, Object> res = svc.adminListMessages(MERCHANT_CONV, null, 30);

        assertTrue(((List<?>) res.get("list")).size() == 1, "客服会话必须照常返回消息");
    }

    // ---------- PII ----------

    @Test
    void 会话列表的手机号已脱敏() {
        ImConversation c = conv(MERCHANT_CONV, ImConversation.TYPE_MERCHANT);
        when(conversationMapper.selectByType(ImConversation.TYPE_MERCHANT))
                .thenReturn(new ArrayList<>(Collections.singletonList(c)));
        ImConversationMember part = new ImConversationMember();
        part.setMemberId(7L);
        when(memberMapper.selectByConversation(MERCHANT_CONV))
                .thenReturn(new ArrayList<>(Collections.singletonList(part)));
        when(messageMapper.selectPage(eq(MERCHANT_CONV), any(), anyInt()))
                .thenReturn(new ArrayList<ImMessage>());
        UmsMember u = new UmsMember();
        u.setPhone(REAL_PHONE);
        u.setNickname("阿三");
        when(umsMemberService.selectUmsMemberById(7L)).thenReturn(u);
        List<Map<String, Object>> rows = svc.adminListConversations();

        assertEquals("138****1234", rows.get(0).get("phone"), "会话列表不得下发完整手机号");
        assertEquals("阿三", rows.get(0).get("nickname"), "昵称是自选公开展示名,不该被顺手遮掉");
    }
}
