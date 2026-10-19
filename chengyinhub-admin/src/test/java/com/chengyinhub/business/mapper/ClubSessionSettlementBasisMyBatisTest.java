package com.chengyinhub.business.mapper;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubPermission;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CoopGroupMemberVerify;
import com.chengyinhub.business.domain.CoopGroupVerify;
import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.dto.CoopGroupSettlementBasis;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.business.service.ISettlementEntryService;
import com.chengyinhub.business.service.impl.CmsRegistrationServiceImpl;
import com.chengyinhub.business.service.impl.GroupCodeServiceImpl;
import com.chengyinhub.business.service.impl.QrcodeServiceImpl;
import com.chengyinhub.business.service.impl.MerchantReferralBountyService;
import com.chengyinhub.business.service.impl.VerificationRiskService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实调用玩家票核销与商家团码核销后，结算 Mapper 必须读出非零团基数。 */
class ClubSessionSettlementBasisMyBatisTest {

    private SqlSessionFactory factory;

    @BeforeEach
    void setUp() throws Exception {
        Configuration configuration = new Configuration();
        configuration.getTypeAliasRegistry().registerAlias("CoopGroupVerify", CoopGroupVerify.class);
        configuration.getTypeAliasRegistry().registerAlias("CoopGroupMemberVerify", CoopGroupMemberVerify.class);
        PooledDataSource dataSource = new PooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:club_session_basis_" + System.nanoTime()
                        + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        for (String resource : new String[]{
                "mapper/business/CoopGroupVerifyMapper.xml",
                "mapper/business/CoopGroupMemberVerifyMapper.xml"}) {
            try (InputStream in = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = factory.openSession(true); Statement statement = session.getConnection().createStatement()) {
            statement.execute("create table coop_group_verify (id bigint auto_increment primary key, topic_id bigint, activity_id bigint, coop_order_id bigint, merchant_member_id bigint, leader_member_id bigint, verify_time timestamp, unique(activity_id, merchant_member_id))");
            statement.execute("create table coop_group_member_verify (id bigint auto_increment primary key, topic_id bigint, activity_id bigint, registration_id bigint, verifier_member_id bigint, verify_time timestamp)");
            statement.execute("create table cms_registration (id bigint primary key, member_id bigint, owner_id bigint, owner_type bigint, payment_status int, verification_status int, payable_amount decimal(10,2))");
            statement.execute("create table cms_activity (id bigint primary key, topic_id bigint, del_flag int)");
            statement.execute("insert into cms_registration values (701, 801, 31, 1, 2, 1, 88.50)");
        }
    }

    @Test
    void playerTicketThenMerchantGroupCodeProducesNonZeroSettlementBasis() {
        try (SqlSession session = factory.openSession(true)) {
            CmsRegistrationMapper registrationMapper = mock(CmsRegistrationMapper.class);
            when(registrationMapper.verifyIfNotVerified(701L, 41L)).thenReturn(1);
            CmsRegistrationScanMapper scanMapper = mock(CmsRegistrationScanMapper.class);
            when(scanMapper.selectCmsRegistrationScanList(any())).thenReturn(Collections.emptyList());
            CmsRegistrationServiceImpl registrationService = new CmsRegistrationServiceImpl();
            org.springframework.test.util.ReflectionTestUtils.setField(registrationService, "growthService",
                    org.mockito.Mockito.mock(com.chengyinhub.business.service.IGrowthService.class));
            ReflectionTestUtils.setField(registrationService, "cmsRegistrationMapper", registrationMapper);
            ReflectionTestUtils.setField(registrationService, "settlementEntryService", mock(ISettlementEntryService.class));
            ReflectionTestUtils.setField(registrationService, "cmsRegistrationScanMapper", scanMapper);
            ReflectionTestUtils.setField(registrationService, "coopGroupMemberVerifyMapper",
                    session.getMapper(CoopGroupMemberVerifyMapper.class));
            ReflectionTestUtils.setField(registrationService, "merchantReferralBountyService",
                    mock(MerchantReferralBountyService.class));
            ReflectionTestUtils.setField(registrationService, "verificationRiskService",
                    mock(VerificationRiskService.class));
            BusinessAuditEventMapper registrationAudit = mock(BusinessAuditEventMapper.class);
            when(registrationAudit.insertBusinessAuditEvent(any())).thenReturn(1);
            ReflectionTestUtils.setField(registrationService, "businessAuditEventMapper", registrationAudit);

            CmsRegistration registration = new CmsRegistration();
            registration.setId(701L);
            registration.setOwnerId(31L);
            // A包核销在原事务内按原ID锁读当前订单；可核销玩家票即已付且报名成功的同一原行。
            registration.setPaymentStatus(2);
            registration.setRegistrationStatus(2);
            when(registrationMapper.selectCmsRegistrationByIdForUpdate(701L)).thenReturn(registration);
            assertTrue(registrationService.verifyAndSettleForGroupActivity(registration, 41L, 51L),
                    "领队扫玩家票必须写入场次成员核销事实");

            CmsActivity activity = new CmsActivity();
            activity.setId(51L);
            activity.setTopicId(31L);
            activity.setClubId(21L);
            activity.setDelFlag(0);
            activity.setStatus(1L);
            activity.setPublishStatus(1L);
            ICmsActivityService activityService = mock(ICmsActivityService.class);
            when(activityService.selectCmsActivityById(51L)).thenReturn(activity);
            IClubAccessService clubAccessService = mock(IClubAccessService.class);
            when(clubAccessService.require(41L, 21L, 51L, ClubPermission.EVENT_CHECKIN))
                    .thenReturn(new ClubAccessContext(41L, 21L, 41L, 51L,
                            Collections.singleton(ClubRoleCode.CLUB_OWNER)));
            CoopOrder merchantOrder = new CoopOrder();
            merchantOrder.setId(901L);
            merchantOrder.setTopicId(31L);
            merchantOrder.setToType("merchant");
            merchantOrder.setToId(61L);
            CoopOrder clubOrder = new CoopOrder();
            clubOrder.setTopicId(31L);
            clubOrder.setToType("club");
            clubOrder.setToId(21L);
            CoopOrderMapper orderMapper = mock(CoopOrderMapper.class);
            when(orderMapper.selectEffectiveOrdersByTopicId(31L))
                    .thenReturn(Arrays.asList(clubOrder, merchantOrder));
            GroupCodeServiceImpl groupCodeService = new GroupCodeServiceImpl(activityService, clubAccessService,
                    orderMapper, session.getMapper(CoopGroupVerifyMapper.class), inlineQrcode(),
                    "club-session-test-secret");
            BusinessAuditEventMapper groupAudit = mock(BusinessAuditEventMapper.class);
            when(groupAudit.insertBusinessAuditEvent(any())).thenReturn(1);
            ReflectionTestUtils.setField(groupCodeService, "businessAuditEventMapper", groupAudit);

            AjaxResult issued = groupCodeService.issue(51L, 41L);
            @SuppressWarnings("unchecked")
            String code = (String) ((Map<String, Object>) issued.get("data")).get("code");
            AjaxResult redeemed = groupCodeService.redeem(code, 61L);
            assertEquals(200, redeemed.get("code"), "商家扫团码必须写入接待事实");

            CoopGroupSettlementBasis basis = session.getMapper(CoopGroupVerifyMapper.class)
                    .selectSettlementBasisByTopicAndMerchant(31L, 61L);
            assertEquals(Integer.valueOf(1), basis.getVerifiedHeads());
            assertTrue(basis.getVerifiedSales().compareTo(BigDecimal.ZERO) > 0,
                    "同场次玩家核销 + 商家团码核销后，分账销售基数必须大于零");
        }
    }

    /** 团码使用真实内联PNG渲染，不依赖对象存储。 */
    private static IQrcodeService inlineQrcode() {
        return new QrcodeServiceImpl();
    }
}
