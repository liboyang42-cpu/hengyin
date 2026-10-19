package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantSubscription;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.mapper.MerchantSubscriptionMapper;
import com.chengyinhub.business.service.impl.MerchantCommerceService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
class MerchantSubscriptionBoundaryMySqlIT {
    @Test
    void originalMapperAndQuotaHonorStartInclusiveEndExclusiveWithoutDeletingGrants() throws Exception {
        MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.fromEnvironment(
                "subscription_boundary_" + Long.toString(System.nanoTime(), 36));
        assertTrue(mysql.adminUrl().startsWith("jdbc:mysql://127.0.0.1:"));
        boolean created = false;
        try (Connection admin = DriverManager.getConnection(mysql.adminUrl(), mysql.user(), mysql.password());
             Statement ddl = admin.createStatement()) {
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(admin.getMetaData().getDatabaseProductVersion()));
            try {
                ddl.execute("CREATE DATABASE `" + mysql.databaseName() + "` CHARACTER SET utf8mb4");
                created = true;
                Configuration configuration = new Configuration();
                configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
                configuration.getTypeAliasRegistry().registerAlias("MerchantSubscription", MerchantSubscription.class);
                configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                        new UnpooledDataSource("com.mysql.cj.jdbc.Driver", mysql.databaseUrl(), mysql.user(), mysql.password())));
                try (InputStream xml = Resources.getResourceAsStream("mapper/business/MerchantSubscriptionMapper.xml")) {
                    new XMLMapperBuilder(xml, configuration, "mapper/business/MerchantSubscriptionMapper.xml",
                            configuration.getSqlFragments()).parse();
                }
                try (SqlSession session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
                     Statement statement = session.getConnection().createStatement()) {
                    statement.execute("SET time_zone = '+00:00'");
                    statement.execute("CREATE TABLE merchant_subscription (id bigint primary key, merchant_id bigint,"
                            + " subscription_type varchar(32), start_date datetime, end_date datetime, max_usage int,"
                            + " used_count int, is_active int, remark varchar(255), create_time datetime, update_time datetime) ENGINE=InnoDB");
                    statement.execute("INSERT INTO merchant_subscription VALUES "
                            + "(1,10,'city_node_quota',from_unixtime(1700000000),from_unixtime(1700000010),10,0,1,null,null,null),"
                            + "(2,10,'city_node_quota',from_unixtime(1700000001),from_unixtime(1700000010),20,0,1,null,null,null),"
                            + "(3,10,'city_node_quota',from_unixtime(1699999990),from_unixtime(1700000000),30,0,1,null,null,null),"
                            + "(4,10,'city_node_quota',null,null,50,0,1,null,null,null),"
                            + "(5,10,'city_node_quota',null,null,40,0,0,null,null,null),"
                            + "(6,11,'city_node_quota',null,null,100,0,1,null,null,null),"
                            + "(7,10,'PREMIUM_TEMPLATE',null,null,7,0,1,null,null,null)");
                    MerchantSubscriptionMapper mapper = session.getMapper(MerchantSubscriptionMapper.class);
                    MerchantCommerceService service = new MerchantCommerceService(false);
                    IUserPermissionService permissions = mock(IUserPermissionService.class);
                    UserPermission permission = new UserPermission();
                    permission.setMaxCityNodes(3);
                    when(permissions.getByRole("merchant")).thenReturn(permission);
                    ReflectionTestUtils.setField(service, "permissionService", permissions);
                    ReflectionTestUtils.setField(service, "subscriptionMapper", mapper);

                    statement.execute("SET timestamp = 1699999999");
                    assertEquals(83, quota(service).getLimit());
                    statement.execute("SET timestamp = 1700000000");
                    assertEquals(63, quota(service).getLimit(), "start is effective; end at the same instant is expired");
                    assertNodeConsumer(service, false);
                    statement.execute("SET timestamp = 1700000001");
                    assertEquals(83, quota(service).getLimit(), "future grant becomes effective at its own start");
                    assertNodeConsumer(service, true);
                    statement.execute("SET timestamp = 1700000010");
                    MerchantCommerceService.Entitlement expired = quota(service);
                    assertEquals(53, expired.getLimit());
                    assertEquals(70, expired.getUsed(), "expiry does not erase existing usage");
                    assertEquals(0, expired.getRemaining());
                    assertNodeConsumer(service, false);

                    MerchantSubscription management = new MerchantSubscription();
                    management.setMerchantId(10L);
                    management.setSubscriptionType(MerchantCommerceService.ENTITLEMENT_CITY_NODE);
                    assertEquals(java.util.Arrays.asList(5L, 4L, 3L, 2L, 1L),
                            mapper.selectMerchantSubscriptionList(management).stream()
                                    .map(MerchantSubscription::getId).collect(Collectors.toList()));
                    try (ResultSet count = statement.executeQuery("SELECT count(*) FROM merchant_subscription")) {
                        assertTrue(count.next());
                        assertEquals(7, count.getInt(1), "projection does not delete future, expired, or disabled grants");
                    }
                }
            } finally {
                if (created) ddl.execute("DROP DATABASE `" + mysql.databaseName() + "`");
            }
        }
    }

    private void assertNodeConsumer(MerchantCommerceService commerce, boolean canActivate) {
        com.chengyinhub.business.mapper.RoamMapper roam = mock(com.chengyinhub.business.mapper.RoamMapper.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        IUmsMemberService members = mock(IUmsMemberService.class);
        IRoleService roles = mock(IRoleService.class);
        com.chengyinhub.business.domain.MmsMerchant merchant = new com.chengyinhub.business.domain.MmsMerchant();
        merchant.setId(88L); merchant.setMemberId(10L);
        com.chengyinhub.business.domain.UmsMember member = new com.chengyinhub.business.domain.UmsMember();
        member.setId(10L); member.setRole("merchant");
        UserPermission permission = new UserPermission(); permission.setCanOpenAsNode(true);
        when(merchants.selectMmsMerchantByMemberId(10L)).thenReturn(merchant);
        when(members.selectUmsMemberById(10L)).thenReturn(member);
        when(roles.getPermission(member)).thenReturn(permission);
        when(roles.resolveRole(member)).thenReturn("merchant");
        when(roam.lockMerchantForNodeReview(10L)).thenReturn(88L);
        when(roam.countActiveNodesByMerchant(88L)).thenReturn(70);
        com.chengyinhub.business.domain.RoamPoi node = new com.chengyinhub.business.domain.RoamPoi();
        node.setId(501L); node.setMerchantId(88L); node.setStatus(0);
        when(roam.selectMerchantPoiOwned(501L, 88L)).thenReturn(node);
        when(roam.setNodeStatusOwned(501L, 88L, 1)).thenReturn(1);
        when(roam.setNodeStatusOwned(501L, 88L, 0)).thenReturn(1);
        com.chengyinhub.business.service.impl.RoamPoiApplicationServiceImpl application =
                new com.chengyinhub.business.service.impl.RoamPoiApplicationServiceImpl(roam, merchants, members, roles,
                        commerce, mock(ICmsMemberTemplateService.class), mock(ICouponRewardAuthorizationService.class));
        if (canActivate) assertTrue(application.setOwnedNodeStatus(10L, 501L, 1));
        else {
            org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                    () -> application.setOwnedNodeStatus(10L, 501L, 1));
            org.mockito.Mockito.verify(roam, org.mockito.Mockito.never()).setNodeStatusOwned(501L, 88L, 1);
        }
        node.setStatus(1);
        assertTrue(application.setOwnedNodeStatus(10L, 501L, 1), "already active node is retained");
        assertTrue(application.setOwnedNodeStatus(10L, 501L, 0), "expiry must not prevent taking an owned node offline");
    }

    private MerchantCommerceService.Entitlement quota(MerchantCommerceService service) {
        return service.entitlement(10L, "merchant", MerchantCommerceService.ENTITLEMENT_CITY_NODE, 70);
    }
}
