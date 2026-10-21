package com.chengyinhub.business.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AUDIT-G1-2 / G1-5:活动编辑的审核回路(service 层)。
 *
 * <p>先审下改名称/简介/图片/时间/地点 ⇒ 已上架的也退回待审;已驳回 ⇒ 改完即重提;
 * 审核单一律 submitOrRefreshActivity(不撞开放任务唯一约束),快照带简介/图片/票价/俱乐部/主办会员。
 */
class ActivityEditResubmitServiceTest {

    private static final long OWNER = 9L;
    private static final long ACTIVITY = 300L;

    private CmsActivityMapper activityMapper;
    private OmsTicketMapper ticketMapper;
    private IAuditTaskService auditTaskService;
    private CmsActivityServiceImpl service;
    private CmsActivity current;

    @BeforeEach
    void setUp() {
        activityMapper = mock(CmsActivityMapper.class);
        ticketMapper = mock(OmsTicketMapper.class);
        auditTaskService = mock(IAuditTaskService.class);
        service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "auditTaskService", auditTaskService);
        ReflectionTestUtils.setField(service, "memberIdentityService", mock(MemberIdentityService.class));
        current = new CmsActivity();
        current.setId(ACTIVITY);
        current.setMemberId(OWNER);
        current.setClubId(700L);
        current.setName("旧名");
        current.setDescription("旧简介");
        current.setImgUrl("old.jpg");
        current.setStartDate(new Date(1_800_000_000_000L));
        current.setStatus(1L);
        current.setPublishStatus(1L);
        current.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(ACTIVITY)).thenReturn(current);
        when(activityMapper.updateCmsActivityByOwner(any())).thenReturn(1);
        when(activityMapper.updateCmsActivity(any())).thenReturn(1);
        OmsTicket ticket = new OmsTicket();
        ticket.setName("标准票");
        ticket.setPrice(new BigDecimal("69.00"));
        when(ticketMapper.selectOmsTicketList(any())).thenReturn(Collections.singletonList(ticket));
    }

    private static CmsActivityPublishRequest edit(String name) {
        CmsActivityPublishRequest r = new CmsActivityPublishRequest();
        r.setId(ACTIVITY);
        r.setName(name);
        return r;
    }

    @Test
    void preReview_liveActivityRenamed_backToPendingAndRefreshesTask() {
        service.updateActivityByOwner(edit("新名"), OWNER, "me", true);

        ArgumentCaptor<CmsActivity> back = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivity(back.capture());
        assertEquals(0L, back.getValue().getStatus());
        assertEquals(0L, back.getValue().getPublishStatus());
        assertEquals(0, back.getValue().getCheckStatus());
        ArgumentCaptor<String> snap = ArgumentCaptor.forClass(String.class);
        verify(auditTaskService).submitOrRefreshActivity(eq(ACTIVITY), eq(OWNER), eq("user_republish"), snap.capture());
        verify(auditTaskService, never()).submit(anyString(), anyLong(), anyLong(), anyString(), any());

        JSONObject json = JSON.parseObject(snap.getValue());
        assertEquals("旧简介", json.getString("description"), "AUDIT-G1-5:快照要带简介");
        assertEquals("old.jpg", json.getString("imgUrl"));
        assertEquals(700L, json.getLongValue("clubId"));
        assertEquals(OWNER, json.getLongValue("memberId"));
        assertEquals(new BigDecimal("69.00"), json.getJSONArray("tickets").getJSONObject(0).getBigDecimal("price"));
    }

    @Test
    void preReview_unchangedReviewableFields_staysLive() {
        CmsActivityPublishRequest r = edit("旧名");
        r.setTemplateId(5L);
        service.updateActivityByOwner(r, OWNER, "me", true);

        verify(activityMapper, never()).updateCmsActivity(any());
        verify(auditTaskService, never()).submitOrRefreshActivity(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void postReview_liveActivityRenamed_staysLive() {
        service.updateActivityByOwner(edit("新名"), OWNER, "me", false);

        verify(activityMapper, never()).updateCmsActivity(any());
        verify(auditTaskService, never()).submitOrRefreshActivity(anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    void rejectedActivityEdited_isResubmittedEvenInPostReview() {
        current.setStatus(2L);
        current.setPublishStatus(0L);

        service.updateActivityByOwner(edit("改好了"), OWNER, "me", false);

        ArgumentCaptor<CmsActivity> back = ArgumentCaptor.forClass(CmsActivity.class);
        verify(activityMapper).updateCmsActivity(back.capture());
        assertEquals(0L, back.getValue().getStatus(), "驳回后编辑 = 重提:回待审");
        assertEquals("", back.getValue().getReason(), "旧驳回原因要清掉");
        verify(auditTaskService).submitOrRefreshActivity(eq(ACTIVITY), eq(OWNER), eq("user_republish"), anyString());
    }

    @Test
    void pendingActivityEdited_refreshesTaskWithoutStatusWrite() {
        current.setStatus(0L);
        current.setPublishStatus(0L);

        service.updateActivityByOwner(edit("待审中改名"), OWNER, "me", true);

        verify(activityMapper, never()).updateCmsActivity(any());
        verify(auditTaskService).submitOrRefreshActivity(eq(ACTIVITY), eq(OWNER), eq("user_republish"), anyString());
    }
}
