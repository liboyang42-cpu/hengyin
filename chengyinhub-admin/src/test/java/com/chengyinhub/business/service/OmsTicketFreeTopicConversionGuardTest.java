package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.OmsTicketServiceImpl;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P1-LC-1(后台口):9-15 拍板「已发布免费主题不能转商业」此前只落在用户侧三个写口,
 * 后台 {@code POST/PUT /business/ticket} 能直接补一张收费票 —— 写前不查、写后不复核,
 * isFreeTopic 就此失效,后续招商/开售/押金义务全跟着解锁。
 *
 * <p>本类钉三条行为:
 * <ul>
 *   <li>已发布免费(非平台自营)主题:后台加收费票被拒(写后复核抛,整个事务回滚);</li>
 *   <li>改价 0 → 收费 同样被拒(只测新增是假绿,改价是同一道口子的另一个入口);</li>
 *   <li>平台自营主题豁免:平台自己的主题允许补收费票(判据 = IProductRoleService.isPlatformOwned)。</li>
 * </ul>
 */
class OmsTicketFreeTopicConversionGuardTest {

    private static final Long TOPIC = 701L;
    private static final Long OWNER = 88L;

    private OmsTicketMapper ticketMapper;
    private CmsTopicMapper topicMapper;
    private CmsActivityMapper activityMapper;
    private OmsTicketServiceImpl service;
    private IProductRoleService productRoleService;

    @BeforeEach
    void setUp() {
        ticketMapper = mock(OmsTicketMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        activityMapper = mock(CmsActivityMapper.class);
        productRoleService = mock(IProductRoleService.class);

        CommercialTopicPolicy policy = new CommercialTopicPolicy(ticketMapper,
                mock(CmsTopicChapterMapper.class), mock(MerchantChapterOfferMapper.class), topicMapper);

        service = new OmsTicketServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "commercialTopicPolicy", policy);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);

        CmsTopic publishedFree = new CmsTopic();
        publishedFree.setId(TOPIC);
        publishedFree.setMemberId(OWNER);
        publishedFree.setLifecycle(TopicLifecycleState.DRAFT.getCode());
        publishedFree.setStatus(1);
        publishedFree.setUserStatus(1);
        publishedFree.setDelFlag(0);
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(publishedFree);
        when(topicMapper.selectTopicIsDraft(TOPIC)).thenReturn(0);
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(false);
        loginWith("business:ticket:add", "business:ticket:edit",
                "business:topic:edit", "business:activity:edit");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void insertPaidTicketOnPublishedFreeTopicIsRejected() {
        // 写前免费(只有一张 0 元票),写后出现收费票 —— 写后复核必须看见并抛出。
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(freeTicket(1L)),
                        Collections.singletonList(paidTicket(9L)));
        when(ticketMapper.insertOmsTicket(any(OmsTicket.class))).thenReturn(1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.insertOmsTicket(submittedPaidTicket(TOPIC)));

        assertEquals(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED, error.getMessage());
        // 写后复核语义:先落库、再由同一事务的复核抛出回滚(不是写前就拦)。
        verify(ticketMapper).insertOmsTicket(any(OmsTicket.class));
    }

    @Test
    void updateTicketFromFreeToPaidOnPublishedFreeTopicIsRejected() {
        OmsTicket existing = freeTicket(5L);
        existing.setTotalInventory(10L);
        existing.setRemainingInventory(8L);
        existing.setStartTime(new Date(1_800_000_000_000L));
        existing.setEndTime(new Date(1_800_003_600_000L));
        when(ticketMapper.selectOmsTicketByIdForUpdate(5L)).thenReturn(existing);
        when(ticketMapper.updateOmsTicket(any(OmsTicket.class))).thenReturn(1);
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(freeTicket(5L)),
                        Collections.singletonList(paidTicket(5L)));

        OmsTicket patch = new OmsTicket();
        patch.setId(5L);
        // 2026-09-16 补合 pay-leftover3 B4:票 update 必须声明归属,否则先被 400 挡掉、走不到免费转商业闸。
        patch.setOwnerType(1L);
        patch.setOwnerId(TOPIC);
        patch.setPrice(new BigDecimal("10"));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.updateOmsTicket(patch));

        assertEquals(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED, error.getMessage());
        verify(ticketMapper).updateOmsTicket(any(OmsTicket.class));
    }

    @Test
    void insertPaidTicketOnPlatformOwnedTopicIsExempt() {
        // 平台自营主题:判据在 isPlatformOwned,平台自己决定定价 —— 豁免这道闸。
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);
        when(ticketMapper.insertOmsTicket(any(OmsTicket.class))).thenReturn(1);
        // 若豁免缺失,写后复核会看到这张收费票并抛;这两条桩保证那种回归必然红。
        when(ticketMapper.selectOmsTicketList(any(OmsTicket.class)))
                .thenReturn(Collections.singletonList(freeTicket(1L)),
                        Collections.singletonList(paidTicket(9L)));

        assertEquals(1, service.insertOmsTicket(submittedPaidTicket(TOPIC)));

        verify(ticketMapper).insertOmsTicket(any(OmsTicket.class));
        verify(productRoleService).isPlatformOwned(TOPIC);
    }

    @Test
    void independentActivityTicketDoesNotConsultConversionGuard() {
        // 独立活动(无父主题)没有可判定的主题口径,不该被这道闸误伤。
        CmsActivity activity = new CmsActivity();
        activity.setId(801L);
        activity.setMemberId(OWNER);
        activity.setDelFlag(0);
        when(activityMapper.selectCmsActivityById(801L)).thenReturn(activity);
        when(ticketMapper.insertOmsTicket(any(OmsTicket.class))).thenReturn(1);

        assertEquals(1, service.insertOmsTicket(submittedPaidTicket(801L, 2L)));
        verify(ticketMapper).insertOmsTicket(any(OmsTicket.class));
        verify(productRoleService, never()).isPlatformOwned(anyLong());
    }

    private OmsTicket submittedPaidTicket(Long ownerId) {
        return submittedPaidTicket(ownerId, 1L);
    }

    private OmsTicket submittedPaidTicket(Long ownerId, Long ownerType) {
        OmsTicket ticket = new OmsTicket();
        ticket.setOwnerType(ownerType);
        ticket.setOwnerId(ownerId);
        ticket.setName("加收门票");
        ticket.setPrice(new BigDecimal("10"));
        ticket.setTotalInventory(10L);
        ticket.setStartTime(new Date(1_800_000_000_000L));
        ticket.setEndTime(new Date(1_800_003_600_000L));
        return ticket;
    }

    private OmsTicket freeTicket(Long id) {
        OmsTicket ticket = new OmsTicket();
        ticket.setId(id);
        ticket.setOwnerType(1L);
        ticket.setOwnerId(TOPIC);
        ticket.setDelFlag(0);
        ticket.setPrice(BigDecimal.ZERO);
        return ticket;
    }

    private OmsTicket paidTicket(Long id) {
        OmsTicket ticket = freeTicket(id);
        ticket.setPrice(new BigDecimal("10"));
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
