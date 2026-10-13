package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * CU-M-175(2026-09-25 走查):活动卡片的「报名数」与详情页的「已报名 N 人」必须同一判据。
 *
 * <p>详情页一直按 registration_status=2 数人({@code ApiActivityController} 走 view_registration),
 * 「我的项目」卡片此前按 payment_status ∈ {0,1,2} 数 —— 拉起支付没付完的弃单也进卡片数字,
 * 于是同一场活动出现「1 报名 / 已报名 0 人」。这里把新口径钉在 SQL 条件上:已报名只认
 * registration_status,不带任何支付状态。
 *
 * <p>⚠️ 负控:删掉 setRegistrationStatus(2) ⇒ 第 1 条红;把 paymentStatus 也带上、
 * 或反过来把 {@code countOpenSaleRegistrations} 一起改成 registration_status
 * (那是「已售不可改」的锁判据,含待支付才拦得住) ⇒ 第 2 条红。
 *
 * <p>覆盖服务查询口径、批量归属与实际 MyBatis 动态 SQL；不替代正常页面验收。
 */
class CmsActivitySignupCountTest {

    private static final long ACTIVITY = 33L;

    private CmsRegistrationMapper registrations;
    private CmsActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        registrations = mock(CmsRegistrationMapper.class);
        service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", registrations);
    }

    @Test
    void signedUpCountAsksForConfirmedRegistrationsOnly() {
        when(registrations.countCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(2);

        assertEquals(2, service.countSignedUpRegistrations(ACTIVITY));

        ArgumentCaptor<CmsRegistration> probe = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrations).countCmsRegistrationList(probe.capture());
        CmsRegistration query = probe.getValue();
        assertEquals(2L, query.getOwnerType(), "活动报名的 owner_type=2");
        assertEquals(ACTIVITY, query.getOwnerId(), "按活动 id 数,不是按主理人");
        assertEquals(2, query.getRegistrationStatus(), "「已报名」= registration_status=2,与详情页同一判据");
        assertNull(query.getPaymentStatus(), "已报名不能再叠加支付状态,否则待支付弃单会虚高");
    }

    /** 反面对照:开卖锁判据仍是「钱随时可能进来」的支付状态,不能被这次统一口径顺手改掉。 */
    @Test
    void openSaleLockKeepsItsPaymentStatusJudgement() {
        when(registrations.countCmsRegistrationList(any(CmsRegistration.class)))
                .thenReturn(0);

        service.countOpenSaleRegistrations(ACTIVITY);

        ArgumentCaptor<CmsRegistration> probe = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrations, times(3)).countCmsRegistrationList(probe.capture());
        List<Integer> statuses = new ArrayList<>();
        for (CmsRegistration query : probe.getAllValues()) {
            statuses.add(query.getPaymentStatus());
            assertNull(query.getRegistrationStatus(),
                    "锁判据按 registration_status 数会漏掉待支付的单,回调到达时活动可能已被改删");
        }
        assertEquals(java.util.Arrays.asList(0, 1, 2), statuses);
    }

    @Test
    void missingActivityIdCountsNobodyWithoutQuerying() {
        assertEquals(0, service.countSignedUpRegistrations(null));
        verify(registrations, times(0)).countCmsRegistrationList(any(CmsRegistration.class));
    }

    @Test
    void countsDoNotReadPrivateFields() {
        when(registrations.selectCmsRegistrationList(any()))
                .thenThrow(new IllegalStateException("unreadable legacy registration PII"));
        assertDoesNotThrow(() -> service.countOpenSaleRegistrations(ACTIVITY));
        assertDoesNotThrow(() -> service.countPaidRegistrations(ACTIVITY));
        assertDoesNotThrow(() -> service.countPendingRegistrations(ACTIVITY));
        assertDoesNotThrow(() -> service.countSignedUpRegistrations(ACTIVITY));
    }

    @Test
    void batchCountsKeepActivityIdsAndAvoidPrivateRegistrationReads() {
        com.chengyinhub.business.mapper.CmsActivityMapper activities =
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("activityId", 33L);
        row.put("signupCount", 2L);
        List<Long> ids = java.util.Arrays.asList(33L, 34L);
        when(activities.selectSignedUpRegistrationCounts(ids)).thenReturn(java.util.Collections.singletonList(row));
        assertEquals(java.util.Collections.singletonMap(33L, 2), service.countSignedUpRegistrationsByActivityIds(ids));
        verify(activities).selectSignedUpRegistrationCounts(ids);
        row.put("pendingVerifyCount", 4L);
        when(activities.selectPendingVerificationCounts(ids)).thenReturn(java.util.Collections.singletonList(row));
        assertEquals(java.util.Collections.singletonMap(33L, 4), service.countPendingVerificationByActivityIds(ids));
        verify(activities).selectPendingVerificationCounts(ids);
        verifyNoInteractions(registrations);
    }

    @Test
    void emptyBatchCannotReadAllRegistrations() {
        com.chengyinhub.business.mapper.CmsActivityMapper activities =
                mock(com.chengyinhub.business.mapper.CmsActivityMapper.class);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
        assertTrue(service.countSignedUpRegistrationsByActivityIds(null).isEmpty());
        assertTrue(service.countSignedUpRegistrationsByActivityIds(java.util.Collections.emptyList()).isEmpty());
        assertTrue(service.countPendingVerificationByActivityIds(null).isEmpty());
        assertTrue(service.countPendingVerificationByActivityIds(java.util.Collections.emptyList()).isEmpty());
        verifyNoInteractions(activities, registrations);
    }

    @Test
    void batchSqlKeepsConfirmedStatusAndFailsClosedForEmptyIds() throws Exception {
        org.apache.ibatis.session.Configuration config = new org.apache.ibatis.session.Configuration();
        config.getTypeAliasRegistry().registerAlias("CmsActivity", com.chengyinhub.business.domain.CmsActivity.class);
        String resource = "mapper/business/CmsActivityMapper.xml";
        try (java.io.InputStream in = org.apache.ibatis.io.Resources.getResourceAsStream(resource)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        org.apache.ibatis.mapping.MappedStatement statement = config.getMappedStatement(
                "com.chengyinhub.business.mapper.CmsActivityMapper.selectSignedUpRegistrationCounts");
        java.util.Map<String, Object> params = new java.util.HashMap<>();
        params.put("activityIds", java.util.Arrays.asList(33L, 34L));
        org.apache.ibatis.mapping.BoundSql bound = statement.getBoundSql(params);
        String sql = bound.getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("owner_type = 2 and registration_status = 2"));
        assertTrue(sql.contains("owner_id in"));
        assertTrue(sql.contains("group by owner_id"));
        assertFalse(sql.contains("payment_status"));
        assertFalse(sql.contains("phone"));
        assertEquals(2, bound.getParameterMappings().size());
        params.put("activityIds", java.util.Collections.emptyList());
        assertTrue(statement.getBoundSql(params).getSql().contains("and 1 = 0"));
        params.put("activityIds", null);
        assertTrue(statement.getBoundSql(params).getSql().contains("and 1 = 0"));
        statement = config.getMappedStatement(
                "com.chengyinhub.business.mapper.CmsActivityMapper.selectPendingVerificationCounts");
        assertTrue(statement.getBoundSql(params).getSql().contains("and 1 = 0"));
        params.put("activityIds", java.util.Arrays.asList(33L, 34L));
        sql = statement.getBoundSql(params).getSql().replaceAll("\\s+", " ");
        assertTrue(sql.contains("owner_type = 2 and payment_status = 2 and verification_status = 0"));
        assertFalse(sql.contains("registration_status"));
        assertFalse(sql.contains("phone"));
        params.put("activityIds", java.util.Collections.emptyList());
        assertTrue(statement.getBoundSql(params).getSql().contains("and 1 = 0"));
    }

}
