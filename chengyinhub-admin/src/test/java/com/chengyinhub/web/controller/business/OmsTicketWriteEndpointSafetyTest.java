package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * G5(P0):真实票务 URL 负控。单测服务还不够，必须钉住 Controller 到异常响应这一整段入口。
 *
 * <p>standalone MockMvc 不开启 Spring Security AOP；这里显式给 ticket:add/edit，模拟 AOP 已放行后
 * 缺少父对象编辑权限的情形。被测的是真实 Controller + OmsTicketServiceImpl，拒绝结果必须是客户端
 * 可见的 AjaxResult code，而不是只在内部抛异常。
 */
class OmsTicketWriteEndpointSafetyTest {

    private static final Long TOPIC_ID = 701L;

    private OmsTicketMapper ticketMapper;
    private CmsActivityMapper activityMapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(OmsTicketMapper.class);
        CmsTopicMapper topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(88L);
        topic.setLifecycle(0);
        when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);

        OmsTicketServiceImpl service = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        // P1-LC-1:写入口多了「已发布免费主题禁转商业」的写后复核。本类测的是权限/库存拒绝
        // 如何变成客户端可见的 code;政策桩默认不受保护(复核 no-op),不改变这些用例的判据。
        ReflectionTestUtils.setField(service, "commercialTopicPolicy",
                mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class));
        ReflectionTestUtils.setField(service, "productRoleService",
                mock(com.chengyinhub.business.service.IProductRoleService.class));

        OmsTicketController controller = new OmsTicketController();
        ReflectionTestUtils.setField(controller, "omsTicketService", service);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void postWithTicketAddButWithoutTopicEdit_returns403AndDoesNotWrite() throws Exception {
        loginWith("business:ticket:add");

        mvc.perform(post("/business/ticket")
                        .contentType("application/json")
                        .content(validTicketJson()))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg").value("无权维护该主题的票务"));

        verify(ticketMapper, never()).insertOmsTicket(any(OmsTicket.class));
    }

    @Test
    void putBelowOccupiedInventory_returns400AndDoesNotWrite() throws Exception {
        OmsTicket existing = new OmsTicket();
        existing.setId(5L);
        existing.setOwnerType(1L);
        existing.setOwnerId(TOPIC_ID);
        existing.setMemberId(88L);
        existing.setTotalInventory(10L);
        existing.setRemainingInventory(8L);
        existing.setStartTime(new Date(1_800_000_000_000L));
        existing.setEndTime(new Date(1_800_003_600_000L));
        when(ticketMapper.selectOmsTicketByIdForUpdate(5L)).thenReturn(existing);
        loginWith("business:ticket:edit", "business:topic:edit");

        mvc.perform(put("/business/ticket")
                        .contentType("application/json")
                        .content("{\"id\":5,\"ownerType\":1,\"ownerId\":701,\"totalInventory\":1}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg").value("票务总库存不能低于当前已占用库存"));

        verify(ticketMapper, never()).updateOmsTicket(any(OmsTicket.class));
    }

    @Test
    void putWithoutOwnerClaim_returns400AndDoesNotWrite() throws Exception {
        OmsTicket existing = new OmsTicket();
        existing.setId(5L);
        existing.setOwnerType(1L);
        existing.setOwnerId(TOPIC_ID);
        existing.setMemberId(88L);
        existing.setTotalInventory(10L);
        existing.setRemainingInventory(8L);
        existing.setStartTime(new Date(1_800_000_000_000L));
        existing.setEndTime(new Date(1_800_003_600_000L));
        when(ticketMapper.selectOmsTicketByIdForUpdate(5L)).thenReturn(existing);
        loginWith("business:ticket:edit", "business:topic:edit");

        mvc.perform(put("/business/ticket")
                        .contentType("application/json")
                        .content("{\"id\":5,\"price\":99}"))
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("ownerType/ownerId")));

        verify(ticketMapper, never()).updateOmsTicket(any(OmsTicket.class));
    }

    @Test
    void putTicketOfAnotherActivity_returns403AndDoesNotWrite() throws Exception {
        OmsTicket existing = new OmsTicket();
        existing.setId(6L);
        existing.setOwnerType(2L);
        existing.setOwnerId(702L);
        existing.setMemberId(66L);
        existing.setTotalInventory(10L);
        existing.setRemainingInventory(8L);
        existing.setStartTime(new Date(1_800_000_000_000L));
        existing.setEndTime(new Date(1_800_003_600_000L));
        when(ticketMapper.selectOmsTicketByIdForUpdate(6L)).thenReturn(existing);
        CmsActivity claimed = new CmsActivity();
        claimed.setId(702L);
        claimed.setMemberId(66L);
        when(activityMapper.selectCmsActivityById(702L)).thenReturn(claimed);
        loginWith("business:ticket:edit", "business:activity:edit");

        mvc.perform(put("/business/ticket")
                        .contentType("application/json")
                        .content("{\"id\":6,\"ownerType\":2,\"ownerId\":703,\"price\":99}"))
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.msg", org.hamcrest.Matchers.containsString("不属于当前活动")));

        verify(ticketMapper, never()).updateOmsTicket(any(OmsTicket.class));
    }

    private String validTicketJson() {
        return "{\"ownerType\":1,\"ownerId\":701,\"memberId\":999,\"remainingInventory\":999,"
                + "\"name\":\"单日票\",\"totalInventory\":10,"
                + "\"startTime\":\"2027-01-15 08:00:00\",\"endTime\":\"2027-01-15 09:00:00\"}";
    }

    private void loginWith(String... permissions) {
        SysUser user = new SysUser();
        user.setUserId(1L);
        LoginUser loginUser = new LoginUser(user, new HashSet<>(Arrays.asList(permissions)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }
}
