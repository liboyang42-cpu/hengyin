package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.domain.RefundApplication;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.service.IAppSessionRegistryService;
import com.chengyinhub.business.service.IRefundService;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.redis.RedisCache;
import com.chengyinhub.framework.security.filter.JwtAuthenticationTokenFilter;
import com.chengyinhub.framework.web.service.AppTokenService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import javax.annotation.Resource;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Synthetic principals and real MyBatis SQL rendering only; no Spring context or database. */
class ApiRegistrationOwnerScopeTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void malformedAuthenticatedMemberCannotCallAnyServiceOrMapper(Long memberId) throws Exception {
        Fixture fixture = new Fixture();
        authenticate(memberId);
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
        assertEquals(memberId, fixture.controller.getAppUserId());

        AjaxResult response = fixture.controller.myRegistrationList("3", null, null);

        assertEquals(500, response.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", response.get(AjaxResult.MSG_TAG));
        assertFalse(response.containsKey(AjaxResult.DATA_TAG));
        fixture.assertNoQueries();
    }

    @Test
    void missingAuthenticationCannotCallAnyServiceOrMapper() throws Exception {
        Fixture fixture = new Fixture();
        SecurityContextHolder.clearContext();

        AjaxResult response = fixture.controller.myRegistrationList("3", null, null);

        assertEquals("请先登录", response.get(AjaxResult.MSG_TAG));
        fixture.assertNoQueries();
    }

    @Test
    void tokenFilterCanAuthenticateMalformedNestedMemberButListStillFailsClosed() throws Exception {
        Fixture fixture = new Fixture();
        authenticateThroughRealFilter(null);
        assertNull(fixture.controller.getAppUserId());

        AjaxResult response = fixture.controller.myRegistrationList("3", null, null);

        assertEquals("请先登录", response.get(AjaxResult.MSG_TAG));
        fixture.assertNoQueries();
    }

    @Test
    void validMemberUsesOwnerPredicateAndRetainsEnvelopeWithoutPagination() throws Exception {
        Fixture fixture = new Fixture();
        authenticateThroughRealFilter(7L);

        AjaxResult response = fixture.controller.myRegistrationList("3", null, null);

        assertEquals(200, response.get(AjaxResult.CODE_TAG));
        TableDataInfo data = (TableDataInfo) response.get(AjaxResult.DATA_TAG);
        assertEquals(200, data.getCode());
        assertEquals(0L, data.getTotal());
        assertTrue(data.getRows().isEmpty());
        ArgumentCaptor<CmsRegistration> registration = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(fixture.mapper).selectCmsRegistrationList(registration.capture());
        assertEquals(7L, registration.getValue().getMemberId());
        assertNull(registration.getValue().getOwnerType());
        assertNull(registration.getValue().getRegistrationStatus());
        assertNull(registration.getValue().getIsOnline());
        assertNotNull(fixture.sql);
        assertTrue(fixture.sql.getSql().matches("(?s).*member_id\\s*=\\s*\\?.*"));
        assertEquals(1, fixture.sql.getParameterMappings().size());
        assertEquals("memberId", fixture.sql.getParameterMappings().get(0).getProperty());
        assertFalse(fixture.sql.getSql().toLowerCase().matches("(?s).*\\blimit\\b.*"));
        ArgumentCaptor<RefundApplication> refunds = ArgumentCaptor.forClass(RefundApplication.class);
        verify(fixture.refundService).selectRefundList(refunds.capture());
        assertEquals(7L, refunds.getValue().getMemberId());
        assertEquals("registration", refunds.getValue().getSourceType());
        verifyNoMoreInteractions(fixture.mapper, fixture.refundService);
        System.out.println("OWNED_REGISTRATION valid owner=7: " + fixture.sql.getSql().replaceAll("\\s+", " "));
    }

    @ParameterizedTest
    @ValueSource(longs = {8L, 0L, -1L})
    @NullSource
    void detailRetainsExplicitOwnershipDenial(Long orderMemberId) throws Exception {
        Fixture fixture = new Fixture();
        authenticate(7L);
        CmsRegistration order = new CmsRegistration();
        order.setId(91L);
        order.setMemberId(orderMemberId);
        when(fixture.mapper.selectCmsRegistrationById(91L)).thenReturn(order);

        AjaxResult response = fixture.controller.getRegistrationDetail("91");

        assertEquals(500, response.get(AjaxResult.CODE_TAG));
        assertEquals("无权查看该报名信息", response.get(AjaxResult.MSG_TAG));
        verify(fixture.mapper).selectCmsRegistrationById(91L);
        verifyNoMoreInteractions(fixture.mapper);
        verifyNoInteractions(fixture.controllerDependencies.toArray());
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void myJoinedMalformedAuthenticatedMemberCannotCallAnyServiceOrMapper(Long memberId) throws Exception {
        Fixture fixture = new Fixture();
        authenticate(memberId);
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
        assertEquals(memberId, fixture.controller.getAppUserId());

        AjaxResult response = fixture.controller.myJoined();

        assertEquals(500, response.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", response.get(AjaxResult.MSG_TAG));
        assertFalse(response.containsKey(AjaxResult.DATA_TAG));
        fixture.assertNoQueries();
    }

    @Test
    void myJoinedMissingAuthenticationCannotCallAnyServiceOrMapper() throws Exception {
        Fixture fixture = new Fixture();
        SecurityContextHolder.clearContext();

        AjaxResult response = fixture.controller.myJoined();

        assertEquals(500, response.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", response.get(AjaxResult.MSG_TAG));
        assertFalse(response.containsKey(AjaxResult.DATA_TAG));
        fixture.assertNoQueries();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L, Long.MIN_VALUE})
    void myJoinedRealFilterMalformedNestedMemberStillFailsClosed(Long memberId) throws Exception {
        Fixture fixture = new Fixture();
        authenticateThroughRealFilter(memberId, "/api/registration/my-joined");
        assertEquals(memberId, fixture.controller.getAppUserId());

        AjaxResult response = fixture.controller.myJoined();

        assertEquals(500, response.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", response.get(AjaxResult.MSG_TAG));
        assertFalse(response.containsKey(AjaxResult.DATA_TAG));
        fixture.assertNoQueries();
    }

    @Test
    void myJoinedValidMemberRetainsBothOwnedStatusQueriesAndEmptyArrayEnvelope() throws Exception {
        Fixture fixture = new Fixture();
        authenticateThroughRealFilter(7L, "/api/registration/my-joined");

        AjaxResult response = fixture.controller.myJoined();

        assertEquals(200, response.get(AjaxResult.CODE_TAG));
        assertTrue(response.get(AjaxResult.DATA_TAG) instanceof List);
        assertTrue(((List<?>) response.get(AjaxResult.DATA_TAG)).isEmpty());
        ArgumentCaptor<CmsRegistration> queries = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(fixture.mapper, times(2)).selectCmsRegistrationList(queries.capture());
        assertEquals(Arrays.asList(1L, 2L), Arrays.asList(
                queries.getAllValues().get(0).getOwnerType(), queries.getAllValues().get(1).getOwnerType()));
        for (CmsRegistration query : queries.getAllValues()) {
            assertEquals(7L, query.getMemberId());
            assertEquals(2, query.getRegistrationStatus());
            assertNull(query.getOwnerId());
            assertNull(query.getPaymentStatus());
            assertNull(query.getIsOnline());
        }
        assertEquals(2, fixture.renderedQueries.size());
        for (BoundSql sql : fixture.renderedQueries) {
            assertTrue(sql.getSql().matches("(?s).*member_id\\s*=\\s*\\?.*"));
            assertTrue(sql.getSql().matches("(?s).*owner_type\\s*=\\s*\\?.*"));
            assertTrue(sql.getSql().matches("(?s).*registration_status\\s*=\\s*\\?.*"));
            assertEquals(Arrays.asList("ownerType", "memberId", "registrationStatus"), Arrays.asList(
                    sql.getParameterMappings().get(0).getProperty(), sql.getParameterMappings().get(1).getProperty(),
                    sql.getParameterMappings().get(2).getProperty()));
            assertEquals(3, sql.getParameterMappings().size());
            assertFalse(sql.getSql().toLowerCase().matches("(?s).*\\blimit\\b.*"));
        }
        ArgumentCaptor<RefundApplication> refunds = ArgumentCaptor.forClass(RefundApplication.class);
        verify(fixture.refundService).selectRefundList(refunds.capture());
        assertEquals(7L, refunds.getValue().getMemberId());
        assertEquals("registration", refunds.getValue().getSourceType());
        verifyNoMoreInteractions(fixture.mapper, fixture.refundService);
        for (Object dependency : fixture.controllerDependencies) {
            if (dependency != fixture.refundService) verifyNoInteractions(dependency);
        }
    }

    @Test
    void myJoinedValidRowsKeepOwnerAttachmentsDescendingOrderAndSevenDayFilter() throws Exception {
        Fixture fixture = new Fixture();
        authenticate(7L);
        CmsRegistration topic = joinedRegistration(101L, 1L, 11L, 1000L);
        CmsRegistration activity = joinedRegistration(102L, 2L, 22L, 2000L);
        CmsRegistration old = joinedRegistration(103L, 1L, 33L, 3000L);
        fixture.listRows.put(1L, Arrays.asList(old, topic));
        fixture.listRows.put(2L, Collections.singletonList(activity));
        ICmsTopicService topicService = (ICmsTopicService) ReflectionTestUtils.getField(fixture.controller, "topicService");
        ICmsActivityService activityService = (ICmsActivityService) ReflectionTestUtils.getField(fixture.controller, "activityService");
        CmsTopic currentTopic = new CmsTopic(); currentTopic.setId(11L);
        CmsTopic oldTopic = new CmsTopic(); oldTopic.setId(33L);
        oldTopic.setEndDate(new Date(System.currentTimeMillis() - 8L * 24 * 60 * 60 * 1000));
        CmsActivity currentActivity = new CmsActivity(); currentActivity.setId(22L);
        when(topicService.selectCmsTopicById(11L)).thenReturn(currentTopic);
        when(topicService.selectCmsTopicById(33L)).thenReturn(oldTopic);
        when(activityService.selectCmsActivityById(22L)).thenReturn(currentActivity);

        AjaxResult response = fixture.controller.myJoined();

        assertEquals(200, response.get(AjaxResult.CODE_TAG));
        assertEquals(Arrays.asList(activity, topic), response.get(AjaxResult.DATA_TAG));
        assertSame(currentTopic, topic.getCmsTopic());
        assertSame(currentActivity, activity.getCmsActivity());
        assertSame(oldTopic, old.getCmsTopic());
        assertNull(currentTopic.getEndDate(), "An unknown owner end date remains visible");
        verify(fixture.mapper, times(2)).selectCmsRegistrationList(any(CmsRegistration.class));
        verifyNoMoreInteractions(fixture.mapper);
    }

    static CmsRegistration joinedRegistration(long id, long ownerType, long ownerId, long createdAt) {
        CmsRegistration registration = new CmsRegistration();
        registration.setId(id); registration.setMemberId(7L);
        registration.setOwnerType(ownerType); registration.setOwnerId(ownerId);
        registration.setRegistrationStatus(2); registration.setPaymentStatus(2);
        registration.setCreateTime(new Date(createdAt));
        return registration;
    }

    static LoginAppUser principal(Long memberId) {
        AppUser appUser = new AppUser();
        appUser.setUserId(memberId);
        LoginAppUser principal = new LoginAppUser(7L, appUser);
        principal.setSessionEpoch(1L);
        principal.setAbsoluteExpireTime(System.currentTimeMillis() + 7200000L);
        principal.setExpireTime(System.currentTimeMillis() + 3600000L);
        return principal;
    }

    static void authenticate(Long memberId) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal(memberId), null, Collections.emptyList()));
    }

    static void authenticateThroughRealFilter(Long memberId) throws Exception {
        authenticateThroughRealFilter(memberId, "/api/registration/list");
    }

    static void authenticateThroughRealFilter(Long memberId, String path) throws Exception {
        SecurityContextHolder.clearContext();
        LoginAppUser principal = principal(memberId);
        IAppSessionRegistryService registry = mock(IAppSessionRegistryService.class);
        when(registry.isCurrentEpoch(7L, 1L)).thenReturn(true);
        RedisCache redis = mock(RedisCache.class);
        AppTokenService tokenService = spy(new AppTokenService());
        ReflectionTestUtils.setField(tokenService, "appSessionRegistryService", registry);
        ReflectionTestUtils.setField(tokenService, "redisCache", redis);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        // Substitute only cached principal retrieval, not real expiry/epoch validation or authentication.
        doReturn(principal).when(tokenService).getLoginAppUser(request);
        JwtAuthenticationTokenFilter filter = new JwtAuthenticationTokenFilter();
        ReflectionTestUtils.setField(filter, "appTokenService", tokenService);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        assertTrue(SecurityContextHolder.getContext().getAuthentication().isAuthenticated());
        assertSame(principal, SecurityContextHolder.getContext().getAuthentication().getPrincipal());
        verify(tokenService).verifyToken(principal);
        verify(registry).isCurrentEpoch(7L, 1L);
        verifyNoInteractions(redis);
    }

    static class Fixture {
        final ApiRegistrationController controller = new ApiRegistrationController();
        final CmsRegistrationServiceImpl service = spy(new CmsRegistrationServiceImpl());
        final CmsRegistrationMapper mapper = mock(CmsRegistrationMapper.class);
        final List<Object> controllerDependencies = new ArrayList<>();
        final IRefundService refundService;
        BoundSql sql;
        final List<BoundSql> renderedQueries = new ArrayList<>();
        final Map<Long, List<CmsRegistration>> listRows = new HashMap<>();

        Fixture() throws Exception {
            for (Field field : ApiRegistrationController.class.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class) || field.isAnnotationPresent(Resource.class)) {
                    if (!"registrationService".equals(field.getName())) {
                        Object dependency = mock(field.getType());
                        controllerDependencies.add(dependency);
                        ReflectionTestUtils.setField(controller, field.getName(), dependency);
                    }
                }
            }
            refundService = (IRefundService) ReflectionTestUtils.getField(controller, "refundService");
            ReflectionTestUtils.setField(service, "cmsRegistrationMapper", mapper);
            ReflectionTestUtils.setField(controller, "registrationService", service);
            Configuration configuration = new Configuration();
            configuration.getTypeAliasRegistry().registerAlias("CmsRegistration", CmsRegistration.class);
            String resource = "mapper/business/CmsRegistrationMapper.xml";
            try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
            when(mapper.selectCmsRegistrationList(any(CmsRegistration.class))).thenAnswer(invocation -> {
                sql = configuration.getMappedStatement(
                        "com.chengyinhub.business.mapper.CmsRegistrationMapper.selectCmsRegistrationList")
                        .getBoundSql(invocation.getArgument(0));
                renderedQueries.add(sql);
                CmsRegistration query = invocation.getArgument(0);
                return listRows.getOrDefault(query.getOwnerType(), Collections.emptyList());
            });
        }

        void assertNoQueries() {
            verifyNoInteractions(service, mapper);
            verifyNoInteractions(controllerDependencies.toArray());
            assertNull(sql, "No SQL may be rendered for an invalid principal");
            assertTrue(renderedQueries.isEmpty());
        }
    }
}
