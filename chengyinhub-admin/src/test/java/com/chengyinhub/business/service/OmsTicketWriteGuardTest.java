package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * G5(P0):票务写入口必须服从其父主题/活动的编辑权限，不能只靠 ticket:add/edit。
 */
class OmsTicketWriteGuardTest {

    private static final Long TOPIC_ID = 701L;

    private OmsTicketMapper ticketMapper;
    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private OmsTicketServiceImpl service;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(OmsTicketMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        service = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        // P1-LC-1:票务写入口多了「已发布免费主题禁转商业」的写后复核(平台自营豁免)。
        // 本类测的是权限/库存闸,政策桩默认放行(受保护=false ⇒ 复核 no-op)。
        ReflectionTestUtils.setField(service, "commercialTopicPolicy",
                mock(com.chengyinhub.business.service.support.CommercialTopicPolicy.class));
        ReflectionTestUtils.setField(service, "productRoleService",
                mock(com.chengyinhub.business.service.IProductRoleService.class));

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC_ID);
        topic.setMemberId(88L);
        topic.setLifecycle(0);
        org.mockito.Mockito.when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(topic);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void directPostWithoutParentEditPermissionDoesNotWriteTicket() {
        loginWith("business:ticket:add");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.insertOmsTicket(validTopicTicket()));

        assertEquals(403, ex.getCode());
        verify(ticketMapper, never()).insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void directPutWithoutParentEditPermissionDoesNotWriteTicket() {
        OmsTicket existing = existingTopicTicket();
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        loginWith("business:ticket:edit");

        OmsTicket submitted = new OmsTicket();
        submitted.setId(existing.getId());
        submitted.setPrice(new java.math.BigDecimal("99"));
        submitted.setOwnerType(1L);
        submitted.setOwnerId(TOPIC_ID);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateOmsTicket(submitted));

        assertEquals(403, ex.getCode());
        verify(ticketMapper, never()).updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void directPutWithoutOwnerClaimIsRejectedWith400() {
        OmsTicket existing = existingTopicTicket();
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        loginWith("business:ticket:edit", "business:topic:edit");

        OmsTicket noClaim = new OmsTicket();
        noClaim.setId(existing.getId());
        noClaim.setPrice(new java.math.BigDecimal("99"));
        ServiceException missing = assertThrows(ServiceException.class, () -> service.updateOmsTicket(noClaim));

        assertEquals(400, missing.getCode(), "归属声明缺省不能再走「按票真实父对象兜底」,必须 fail-closed");
        assertTrue(missing.getMessage().contains("ownerType/ownerId"), missing.getMessage());

        OmsTicket halfClaim = new OmsTicket();
        halfClaim.setId(existing.getId());
        halfClaim.setPrice(new java.math.BigDecimal("99"));
        halfClaim.setOwnerType(1L);
        ServiceException partial = assertThrows(ServiceException.class, () -> service.updateOmsTicket(halfClaim));

        assertEquals(400, partial.getCode(), "只声明一半同样是缺失,不能被当成「部分匹配」放行");

        verify(ticketMapper, never()).updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void directPostDerivesOwnerAndInitialInventoryFromTopic() {
        loginWith("business:ticket:add", "business:topic:edit");
        OmsTicket submitted = validTopicTicket();
        submitted.setMemberId(999L);
        submitted.setRemainingInventory(999L);
        org.mockito.Mockito.when(ticketMapper.insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class)))
                .thenReturn(1);

        assertEquals(1, service.insertOmsTicket(submitted));

        verify(ticketMapper).insertOmsTicket(org.mockito.ArgumentMatchers.argThat(ticket ->
                Long.valueOf(88L).equals(ticket.getMemberId())
                        && Long.valueOf(10L).equals(ticket.getRemainingInventory())
                        && Long.valueOf(TOPIC_ID).equals(ticket.getOwnerId())
                        && Long.valueOf(1L).equals(ticket.getOwnerType())));
    }

    @Test
    void directPostRejectsInvalidTimeWindowWithoutWritingTicket() {
        loginWith("business:ticket:add", "business:topic:edit");
        OmsTicket submitted = validTopicTicket();
        submitted.setEndTime(submitted.getStartTime());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.insertOmsTicket(submitted));

        assertEquals(400, ex.getCode());
        verify(ticketMapper, never()).insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void sellingTopicRejectsTicketCatalogMutation() {
        CmsTopic selling = new CmsTopic();
        selling.setId(TOPIC_ID);
        selling.setMemberId(88L);
        selling.setLifecycle(com.chengyinhub.business.domain.enums.TopicLifecycleState.SELLING.getCode());
        org.mockito.Mockito.when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(selling);
        loginWith("business:ticket:add", "business:topic:edit");

        ServiceException ex = assertThrows(ServiceException.class, () -> service.insertOmsTicket(validTopicTicket()));

        assertEquals(409, ex.getCode(), "SELLING 后票种/价格/容量/履约窗均属冻结售卖目录");
        verify(ticketMapper, never()).insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void directPutRejectsTotalInventoryBelowCurrentOccupiedWithoutWritingTicket() {
        OmsTicket existing = existingTopicTicket();
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        loginWith("business:ticket:edit", "business:topic:edit");

        OmsTicket submitted = new OmsTicket();
        submitted.setId(existing.getId());
        submitted.setTotalInventory(1L);
        submitted.setOwnerType(1L);
        submitted.setOwnerId(TOPIC_ID);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateOmsTicket(submitted));

        assertEquals(400, ex.getCode());
        verify(ticketMapper, never()).updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void directPutRecalculatesRemainingInventoryWhenTotalChanges() {
        OmsTicket existing = existingTopicTicket(); // total=10, remaining=8, occupied=2
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        org.mockito.Mockito.when(ticketMapper.updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class)))
                .thenReturn(1);
        loginWith("business:ticket:edit", "business:topic:edit");

        OmsTicket submitted = new OmsTicket();
        submitted.setId(existing.getId());
        submitted.setTotalInventory(7L);
        submitted.setRemainingInventory(999L); // 客户端值必须不被采信
        submitted.setOwnerType(1L);
        submitted.setOwnerId(TOPIC_ID);

        assertEquals(1, service.updateOmsTicket(submitted));

        verify(ticketMapper).updateOmsTicket(org.mockito.ArgumentMatchers.argThat(ticket ->
                Long.valueOf(7L).equals(ticket.getTotalInventory())
                        && Long.valueOf(5L).equals(ticket.getRemainingInventory())));
    }

    @Test
    void directPutRejectsConcurrentInventoryChangeInsteadOfReportingSuccess() {
        OmsTicket existing = existingTopicTicket();
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        org.mockito.Mockito.when(ticketMapper.updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class)))
                .thenReturn(0);
        loginWith("business:ticket:edit", "business:topic:edit");

        OmsTicket submitted = new OmsTicket();
        submitted.setId(existing.getId());
        submitted.setTotalInventory(9L);
        submitted.setOwnerType(1L);
        submitted.setOwnerId(TOPIC_ID);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateOmsTicket(submitted));

        assertEquals(409, ex.getCode());
    }

    @Test
    void directPutRejectsClaimedOwnerThatDoesNotMatchTheTicket() {
        OmsTicket existing = existingTopicTicket();
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        loginWith("business:ticket:edit", "business:topic:edit", "business:activity:edit");

        OmsTicket submitted = new OmsTicket();
        submitted.setId(existing.getId());
        submitted.setPrice(new java.math.BigDecimal("99"));
        submitted.setOwnerType(2L);
        submitted.setOwnerId(999L);
        submitted.setMemberId(999L);
        submitted.setRemainingInventory(999L);

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateOmsTicket(submitted));

        assertEquals(403, ex.getCode(), "归属声明与票的真实父对象不一致,必须拒绝越权 id 而不是静默忽略");
        assertTrue(ex.getMessage().contains("不属于当前主题"), ex.getMessage());
        verify(ticketMapper, never()).updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void directPutBindsTicketToClaimedActivityAndRejectsAnotherActivity() {
        OmsTicket existing = existingActivityTicket(702L);
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        CmsActivity claimed = new CmsActivity();
        claimed.setId(702L);
        claimed.setMemberId(66L);
        org.mockito.Mockito.when(activityMapper.selectCmsActivityById(702L)).thenReturn(claimed);
        loginWith("business:ticket:edit", "business:activity:edit");

        OmsTicket otherActivity = new OmsTicket();
        otherActivity.setId(existing.getId());
        otherActivity.setOwnerType(2L);
        otherActivity.setOwnerId(703L);
        otherActivity.setPrice(new java.math.BigDecimal("99"));

        ServiceException ex = assertThrows(ServiceException.class, () -> service.updateOmsTicket(otherActivity));

        assertEquals(403, ex.getCode());
        assertTrue(ex.getMessage().contains("不属于当前活动"), ex.getMessage());
        verify(ticketMapper, never()).updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void directPutStripsOwnerAndInventoryFieldsWhenClaimMatchesTheTicket() {
        OmsTicket existing = existingTopicTicket();
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        org.mockito.Mockito.when(ticketMapper.updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class)))
                .thenReturn(1);
        loginWith("business:ticket:edit", "business:topic:edit");

        OmsTicket submitted = new OmsTicket();
        submitted.setId(existing.getId());
        submitted.setPrice(new java.math.BigDecimal("99"));
        submitted.setOwnerType(1L);
        submitted.setOwnerId(TOPIC_ID);
        submitted.setMemberId(999L);
        submitted.setRemainingInventory(999L);

        assertEquals(1, service.updateOmsTicket(submitted));

        verify(ticketMapper).updateOmsTicket(org.mockito.ArgumentMatchers.argThat(ticket ->
                ticket.getOwnerType() == null
                        && ticket.getOwnerId() == null
                        && ticket.getMemberId() == null
                        && ticket.getRemainingInventory() == null
                        && new java.math.BigDecimal("99").equals(ticket.getPrice())));
    }

    @Test
    void directPostForActivityRequiresActivityScopeAndDerivesOwner() {
        CmsActivity activity = new CmsActivity();
        activity.setId(702L);
        activity.setMemberId(66L);
        org.mockito.Mockito.when(activityMapper.selectCmsActivityById(702L)).thenReturn(activity);
        org.mockito.Mockito.when(ticketMapper.insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class)))
                .thenReturn(1);
        loginWith("business:ticket:add", "business:activity:edit");

        OmsTicket submitted = validTopicTicket();
        submitted.setOwnerType(2L);
        submitted.setOwnerId(702L);
        submitted.setMemberId(999L);

        assertEquals(1, service.insertOmsTicket(submitted));

        verify(ticketMapper).insertOmsTicket(org.mockito.ArgumentMatchers.argThat(ticket ->
                Long.valueOf(66L).equals(ticket.getMemberId())
                        && Long.valueOf(2L).equals(ticket.getOwnerType())
                        && Long.valueOf(702L).equals(ticket.getOwnerId())));
    }

    @Test
    void sellingTopicFreezesTicketsOfItsActivityShell() {
        CmsActivity activity = new CmsActivity();
        activity.setId(702L);
        activity.setMemberId(66L);
        activity.setTopicId(TOPIC_ID);
        org.mockito.Mockito.when(activityMapper.selectCmsActivityById(702L)).thenReturn(activity);
        CmsTopic selling = new CmsTopic();
        selling.setId(TOPIC_ID);
        selling.setLifecycle(com.chengyinhub.business.domain.enums.TopicLifecycleState.SELLING.getCode());
        org.mockito.Mockito.when(topicMapper.selectCmsTopicById(TOPIC_ID)).thenReturn(selling);
        loginWith("business:ticket:add", "business:activity:edit");
        OmsTicket submitted = validTopicTicket();
        submitted.setOwnerType(2L);
        submitted.setOwnerId(702L);

        ServiceException error = assertThrows(ServiceException.class, () -> service.insertOmsTicket(submitted));

        assertEquals(409, error.getCode());
        verify(ticketMapper, never()).insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void independentActivityTicketRemainsEditable() {
        CmsActivity activity = new CmsActivity();
        activity.setId(703L);
        activity.setMemberId(66L);
        activity.setTopicId(null);
        org.mockito.Mockito.when(activityMapper.selectCmsActivityById(703L)).thenReturn(activity);
        org.mockito.Mockito.when(ticketMapper.insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class)))
                .thenReturn(1);
        loginWith("business:ticket:add", "business:activity:edit");
        OmsTicket submitted = validTopicTicket();
        submitted.setOwnerType(2L);
        submitted.setOwnerId(703L);

        assertEquals(1, service.insertOmsTicket(submitted));
    }

    @Test
    void reversedSaleWindowOnCreateDoesNotWriteTicket() {
        loginWith("business:ticket:add", "business:topic:edit");
        OmsTicket submitted = validTopicTicket();
        submitted.setSaleStartTime(new Date(1_800_000_000_000L));
        submitted.setSaleEndTime(new Date(1_799_913_600_000L));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.insertOmsTicket(submitted));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("售票结束"), error.getMessage());
        verify(ticketMapper, never()).insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void partialSaleEndUpdateIsCheckedAgainstLockedExistingStart() {
        assertReversedPartialSaleUpdate(false);
    }

    @Test
    void partialSaleStartUpdateIsCheckedAgainstLockedExistingEnd() {
        assertReversedPartialSaleUpdate(true);
    }

    private void assertReversedPartialSaleUpdate(boolean startOnly) {
        OmsTicket existing = existingTopicTicket();
        existing.setSaleStartTime(new Date(1_799_913_600_000L));
        existing.setSaleEndTime(new Date(1_800_000_000_000L));
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        loginWith("business:ticket:edit", "business:topic:edit");
        OmsTicket submitted = new OmsTicket();
        submitted.setId(existing.getId());
        submitted.setOwnerType(1L);
        submitted.setOwnerId(TOPIC_ID);
        if (startOnly) {
            submitted.setSaleStartTime(new Date(1_800_086_400_000L));
        } else {
            submitted.setSaleEndTime(new Date(1_799_827_200_000L));
        }

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.updateOmsTicket(submitted));

        assertEquals(400, error.getCode());
        assertTrue(error.getMessage().contains("售票结束"), error.getMessage());
        verify(ticketMapper, never()).updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class));
    }

    @Test
    void sameDaySaleWindowRemainsCreatable() {
        loginWith("business:ticket:add", "business:topic:edit");
        OmsTicket submitted = validTopicTicket();
        submitted.setSaleStartTime(new Date(1_800_000_000_000L));
        submitted.setSaleEndTime(new Date(1_800_000_000_000L));
        org.mockito.Mockito.when(ticketMapper.insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class)))
                .thenReturn(1);

        assertEquals(1, service.insertOmsTicket(submitted));
        verify(ticketMapper).insertOmsTicket(submitted);
    }

    @Test
    void optionalSaleEndRemainsOptionalOnCreate() {
        loginWith("business:ticket:add", "business:topic:edit");
        OmsTicket submitted = validTopicTicket();
        submitted.setSaleStartTime(new Date(1_800_000_000_000L));
        org.mockito.Mockito.when(ticketMapper.insertOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class)))
                .thenReturn(1);

        assertEquals(1, service.insertOmsTicket(submitted));
    }

    @Test
    void legacyReversedSaleWindowDoesNotBlockUnrelatedNameUpdate() {
        OmsTicket existing = existingTopicTicket();
        existing.setSaleStartTime(new Date(1_800_000_000_000L));
        existing.setSaleEndTime(new Date(1_799_913_600_000L));
        org.mockito.Mockito.when(ticketMapper.selectOmsTicketByIdForUpdate(existing.getId())).thenReturn(existing);
        org.mockito.Mockito.when(ticketMapper.updateOmsTicket(org.mockito.ArgumentMatchers.any(OmsTicket.class)))
                .thenReturn(1);
        loginWith("business:ticket:edit", "business:topic:edit");
        OmsTicket submitted = new OmsTicket();
        submitted.setId(existing.getId());
        submitted.setOwnerType(1L);
        submitted.setOwnerId(TOPIC_ID);
        submitted.setName("修正票名");

        assertEquals(1, service.updateOmsTicket(submitted));
        verify(ticketMapper).updateOmsTicket(submitted);
    }

    private OmsTicket existingTopicTicket() {
        OmsTicket ticket = validTopicTicket();
        ticket.setId(5L);
        ticket.setMemberId(88L);
        ticket.setRemainingInventory(8L);
        return ticket;
    }

    private OmsTicket existingActivityTicket(Long activityId) {
        OmsTicket ticket = validTopicTicket();
        ticket.setId(6L);
        ticket.setOwnerType(2L);
        ticket.setOwnerId(activityId);
        ticket.setMemberId(66L);
        ticket.setRemainingInventory(8L);
        return ticket;
    }

    private OmsTicket validTopicTicket() {
        OmsTicket ticket = new OmsTicket();
        ticket.setOwnerType(1L);
        ticket.setOwnerId(TOPIC_ID);
        ticket.setName("单日票");
        ticket.setTotalInventory(10L);
        ticket.setStartTime(new Date(1_800_000_000_000L));
        ticket.setEndTime(new Date(1_800_003_600_000L));
        return ticket;
    }

    private void loginWith(String... permissions) {
        SysUser user = new SysUser();
        user.setUserId(1L);
        LoginUser loginUser = new LoginUser(user, new HashSet<>(Arrays.asList(permissions)));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
    }
}
