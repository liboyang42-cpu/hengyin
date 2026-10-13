package com.chengyinhub.business.service;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.vo.MerchantEditProfileVO;
import com.chengyinhub.business.domain.vo.PublicMerchantVO;
import com.chengyinhub.business.mapper.MerchantOperatorInviteMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.MmsMerchantServiceImpl;
import com.chengyinhub.business.service.impl.NpcProfileService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysCategoryService;
import com.chengyinhub.web.controller.api.ApiMerchantController;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * R10-02：营业时间 / 衍生权益在商家资料更新链上「200 但不落库」的真实链路回归。
 *
 * <p>本地 MySQL8 + 真实 MyBatis XML + 真实 {@link MmsMerchantServiceImpl}(经 Spring 事务代理)
 * + 真实 {@link ApiMerchantController#updateUserInfo}；只 mock 登录主体、商家访问上下文、
 * 内容安全与分类等本用例不验证的外围依赖。不使用任何生产/审查库连接。</p>
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MerchantProfilePersistenceMybatisIntegrationTest {
    private static final long OWNER = 901L;
    private static final long EMPLOYEE = 903L;
    private static final long OUTSIDER = 902L;
    private static final long MERCHANT_ID = 77L;
    private static final String SEEDED_HOURS = "周一至周日 08:00-17:00";

    private String database;
    private JdbcTemplate admin;
    private JdbcTemplate db;
    private IMmsMerchantService merchants;
    private LoggedInController controller;

    @BeforeAll
    void database() throws Exception {
        // 2026-09-16: 原来硬编码 127.0.0.1:3317 —— 门禁注入的 CHENGYIN_TEST_MYSQL_* 被无视,
        // 接不上 CI 的库;统一走 MysqlIntegrationTestConfig(唯一临时 schema,参数与其它真库 IT 相同)。
        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_BASE", "cy_merchant_profile");
        env.put("CHENGYIN_TEST_MYSQL_DATABASE_SUFFIX", "_" + UUID.randomUUID().toString().replace("-", ""));
        MysqlIntegrationTestConfig mysql = MysqlIntegrationTestConfig.from(env, "it");
        database = mysql.databaseName();
        admin = new JdbcTemplate(new DriverManagerDataSource(mysql.adminUrl(), mysql.user(), mysql.password()));
        String version = admin.queryForObject("SELECT VERSION()", String.class);
        assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(version), "需要 MySQL >= 8,实际 " + version);
        admin.execute("CREATE DATABASE " + database);
        DriverManagerDataSource source =
                new DriverManagerDataSource(mysql.databaseUrl(), mysql.user(), mysql.password());
        db = new JdbcTemplate(source);
        db.execute(MERCHANT_DDL);
        db.execute("CREATE TABLE ums_member(id BIGINT PRIMARY KEY,role VARCHAR(30),user_type BIGINT,status BIGINT,del_flag INT DEFAULT 0)");
        db.execute(OPERATOR_DDL);

        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.chengyinhub.business.domain");
        factory.setMapperLocations(new ClassPathResource("mapper/business/MmsMerchantMapper.xml"),
                new ClassPathResource("mapper/business/MerchantOperatorMapper.xml"),
                new ClassPathResource("mapper/business/UmsMemberMapper.xml"));
        SqlSessionTemplate sql = new SqlSessionTemplate(factory.getObject());
        MmsMerchantMapper merchantMapper = sql.getMapper(MmsMerchantMapper.class);
        MerchantOperatorMapper operatorMapper = sql.getMapper(MerchantOperatorMapper.class);
        UmsMemberMapper memberMapper = sql.getMapper(UmsMemberMapper.class);

        MmsMerchantServiceImpl target = new MmsMerchantServiceImpl();
        ReflectionTestUtils.setField(target, "mmsMerchantMapper", merchantMapper);
        ReflectionTestUtils.setField(target, "merchantOperatorMapper", operatorMapper);
        ReflectionTestUtils.setField(target, "merchantOperatorInviteMapper", mock(MerchantOperatorInviteMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", memberMapper);
        agentBackedService(target, source);

        IMerchantAccessService access = mock(IMerchantAccessService.class);
        MerchantAccessContext context = new MerchantAccessContext(OWNER, MERCHANT_ID, OWNER, MerchantRoleCode.MERCHANT_OWNER);
        when(access.require(anyLong(), any())).thenReturn(context);

        controller = new LoggedInController();
        ReflectionTestUtils.setField(controller, "merchantAccessService", access);
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService",
                mock(IWxContentSecurityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(controller, "sysCategoryService", mock(ISysCategoryService.class));
        ReflectionTestUtils.setField(controller, "npcProfileService", mock(NpcProfileService.class));
        ReflectionTestUtils.setField(controller, "coopInviteService", mock(ICoopInviteService.class));
        ReflectionTestUtils.setField(controller, "merchantNodeDiscoveryService",
                mock(IMerchantNodeDiscoveryService.class));
    }

    private void agentBackedService(MmsMerchantServiceImpl target, DriverManagerDataSource source) {
        ReflectionTestUtils.setField(target, "clubMapper", mock(com.chengyinhub.business.mapper.ClubMapper.class));
        ReflectionTestUtils.setField(target, "capabilityService", mock(ICapabilityService.class));
        ReflectionTestUtils.setField(target, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(target, "umsMemberPointsDetailService", mock(IUmsMemberPointsDetailService.class));
        ReflectionTestUtils.setField(target, "cmsPointsSettingService", mock(ICmsPointsSettingService.class));
        DataSourceTransactionManager manager = new DataSourceTransactionManager(source);
        ProxyFactory proxy = new ProxyFactory(target);
        proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        merchants = (IMmsMerchantService) proxy.getProxy();
    }

    @AfterAll
    void cleanup() {
        if (database == null || admin == null) return;
        admin.execute("DROP DATABASE " + database);
        assertEquals(0, admin.queryForObject(
                "SELECT count(*) FROM information_schema.schemata WHERE schema_name=?", Integer.class, database));
        System.out.println("scratch cleanup verified " + database);
    }

    @BeforeEach
    void seed() {
        db.update("DELETE FROM merchant_operator");
        db.update("DELETE FROM mms_merchant");
        db.update("DELETE FROM ums_member");
        db.update("INSERT INTO ums_member(id,role,user_type,status,del_flag) VALUES(901,'merchant',2,1,0)");
        db.update("INSERT INTO ums_member(id,role,user_type,status,del_flag) VALUES(902,'player',1,1,0)");
        db.update("INSERT INTO ums_member(id,role,user_type,status,del_flag) VALUES(903,'merchant',1,1,0)");
        db.update("INSERT INTO mms_merchant(id,member_id,name,wechat,category_id,logo,description,derivatives,"
                + "derivative_benefits,website,preference,phone,address,business_time,business_license,status,reson,"
                + "account_status,business_status,del_flag,capacity,available_time,suit_activity_types,charge_type,"
                + "demand,coop_open,cover_image,slogan,city_role,story_title,gallery,tags,featured_type,featured_id,"
                + "service_tag,service_text,location_lat,location_lng,location_verified,update_by) VALUES("
                + "77,901,'样板书店','wx-secret',4001,'logo.png','原品牌故事','derivatives.png','原权益',"
                + "'https://old.example','咖啡','13800000000','旧地址','" + SEEDED_HOURS + "','license.png',1,'private',"
                + "1,1,0,10,'工作日','策展',1,'需提前预约',0,'cover.png','原口号','夜间补给','原故事标题',"
                + "'a.png,b.png','标签1,标签2',0,NULL,'服务标签','服务文案',31.2,121.4,1,'seed')");
    }

    @Test
    void authorizedSavePersistsBusinessTimeAndBenefitsAcrossSeparateMixedAndRepeatedSaves() {
        Map<String, Object> unrelated = snapshotUnrelated();

        // 单独保存白天营业时间
        assertEquals(200, updateOnlyHours("周一至周日 09:00-18:00").get(AjaxResult.CODE_TAG));
        assertEquals("周一至周日 09:00-18:00", text("business_time"), "白天营业时间必须真实落库");
        assertEquals("原权益", text("derivative_benefits"), "只改营业时间不得动权益说明");
        assertUnrelatedUnchanged(unrelated);

        // 单独保存衍生权益说明
        assertEquals(200, updateOnlyBenefits("参与即可领取限定书签").get(AjaxResult.CODE_TAG));
        assertEquals("参与即可领取限定书签", text("derivative_benefits"), "权益说明必须真实落库");
        assertEquals("周一至周日 09:00-18:00", text("business_time"), "只改权益不得动营业时间");
        assertUnrelatedUnchanged(unrelated);

        // 混合保存：跨夜营业时间 + 权益说明 + 简介同请求
        MmsMerchant mixed = new MmsMerchant();
        mixed.setBusinessTime("周一、三、五 21:00-次日02:00");
        mixed.setDerivativeBenefits("跨夜场限定饮品");
        mixed.setDescription("新的品牌故事");
        assertEquals(200, controller.updateUserInfo(mixed).get(AjaxResult.CODE_TAG));
        assertEquals("周一、三、五 21:00-次日02:00", text("business_time"), "跨夜营业时间必须真实落库");
        assertEquals("跨夜场限定饮品", text("derivative_benefits"), "混合请求中的权益必须真实落库");
        assertEquals("新的品牌故事", text("description"));

        // 重复保存相同值：仍返回成功、值不变、不产生第二条
        assertEquals(200, updateOnlyBenefits("跨夜场限定饮品").get(AjaxResult.CODE_TAG));
        assertEquals(200, updateOnlyHours("周一、三、五 21:00-次日02:00").get(AjaxResult.CODE_TAG));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mms_merchant", Integer.class));
        assertEquals("跨夜场限定饮品", text("derivative_benefits"));
        assertEquals("周一、三、五 21:00-次日02:00", text("business_time"));

        // 商家重读：/api/merchant/info 与 /api/merchant/coop-profile 必须是落库值
        MmsMerchant info = (MmsMerchant) controller.getUserInfo().get(AjaxResult.DATA_TAG);
        assertEquals("周一、三、五 21:00-次日02:00", info.getBusinessTime());
        assertEquals("跨夜场限定饮品", info.getDerivativeBenefits());
        MerchantEditProfileVO edit = (MerchantEditProfileVO) controller.coopProfile().get(AjaxResult.DATA_TAG);
        assertEquals("周一、三、五 21:00-次日02:00", edit.getBusinessTime());
        assertEquals("跨夜场限定饮品", edit.getDerivativeBenefits());

        // 玩家适用投影：/api/merchant/public-home（商家侧写入 → 匿名玩家读取一致）
        Map<String, Object> body = new HashMap<>();
        body.put("id", MERCHANT_ID);
        PublicMerchantVO home = (PublicMerchantVO) controller.publicHome(body).get(AjaxResult.DATA_TAG);
        assertEquals("周一、三、五 21:00-次日02:00", home.getBusinessTime(),
                "玩家公开主页必须读到刚保存的营业时间");
        MmsMerchant detailBody = new MmsMerchant();
        detailBody.setId(MERCHANT_ID);
        AjaxResult detail = controller.publicDetail(detailBody);
        assertEquals(200, detail.get(AjaxResult.CODE_TAG));
        assertEquals("周一、三、五 21:00-次日02:00",
                ((com.chengyinhub.business.domain.vo.PublicMerchantDiscoveryVO) detail.get(AjaxResult.DATA_TAG))
                        .getBusinessTime(),
                "玩家公开详情必须读到刚保存的营业时间");
    }

    @Test
    void illegalHoursAreRejectedWithoutPersistenceAndRecoveryStillSaves() {
        Map<String, Object> before = snapshotUnrelated();
        String[] illegal = {
                "每天 09:00-18:00",          // 非法经营日
                "周一至周日 09:00-09:00",     // 同起止
                "周一至周日 21:00-02:00",     // 跨夜漏标次日
                "周一至周日 09:00-次日18:00", // 错误标次日
                "周一 25:00-26:00",          // 非法时分
                ""                            // 空值不得被当成清空
        };
        for (String hours : illegal) {
            assertNotEquals(200, updateOnlyHours(hours).get(AjaxResult.CODE_TAG), "必须拒绝：" + hours);
            assertEquals(SEEDED_HOURS, text("business_time"), "拒绝后不得落库：" + hours);
        }
        assertUnrelatedUnchanged(before);

        // 失败恢复：拒绝之后合法保存仍然生效
        assertEquals(200, updateOnlyHours("周一至周日 22:30-次日01:15").get(AjaxResult.CODE_TAG));
        assertEquals("周一至周日 22:30-次日01:15", text("business_time"));
    }

    @Test
    void unauthorizedDisabledIdentityRejectedAndAuthorizedEmployeeCanStillSave() {
        // 非 Owner 非员工：服务层越权必须 403 且不落库
        MmsMerchant patch = new MmsMerchant();
        patch.setId(MERCHANT_ID);
        patch.setBusinessTime("周二 09:00-18:00");
        ServiceException outsider = assertThrows(ServiceException.class, () ->
                merchants.updateAuthorizedProfile(patch, OUTSIDER, MerchantPermission.PROFILE_WRITE));
        assertEquals(403, outsider.getCode());
        assertEquals(SEEDED_HOURS, text("business_time"));

        // 停用会员：锁读不到活跃会员，403 且不落库
        db.update("UPDATE ums_member SET status=0 WHERE id=901");
        ServiceException memberDisabled = assertThrows(ServiceException.class, () ->
                merchants.updateAuthorizedProfile(patch, OWNER, MerchantPermission.PROFILE_WRITE));
        assertEquals(403, memberDisabled.getCode());
        assertEquals(SEEDED_HOURS, text("business_time"));

        // 停用商家：403 且不落库
        db.update("UPDATE ums_member SET status=1 WHERE id=901");
        db.update("UPDATE mms_merchant SET account_status=2 WHERE id=77");
        ServiceException merchantDisabled = assertThrows(ServiceException.class, () ->
                merchants.updateAuthorizedProfile(patch, OWNER, MerchantPermission.PROFILE_WRITE));
        assertEquals(403, merchantDisabled.getCode());
        assertEquals(SEEDED_HOURS, text("business_time"));

        // 恢复商家状态：Owner 保存恢复可用
        db.update("UPDATE mms_merchant SET account_status=1 WHERE id=77");
        assertEquals(1, merchants.updateAuthorizedProfile(patch, OWNER, MerchantPermission.PROFILE_WRITE));
        assertEquals("周二 09:00-18:00", text("business_time"));

        // 有效员工（店长）在授权范围内保存营业时间，不因 Owner 修丁而失效
        db.update("INSERT INTO merchant_operator(id,merchant_id,operator_member_id,role_code,status) "
                + "VALUES(1,77,903,'MERCHANT_MANAGER','ACTIVE')");
        MmsMerchant employeePatch = new MmsMerchant();
        employeePatch.setId(MERCHANT_ID);
        employeePatch.setDerivativeBenefits("员工保存的权益");
        assertEquals(1, merchants.updateAuthorizedProfile(employeePatch, EMPLOYEE, MerchantPermission.PROFILE_WRITE));
        assertEquals("员工保存的权益", text("derivative_benefits"));
    }

    @Test
    void decorLocationPersistsAddressAlongWithCoordinatesAcrossStandaloneMixedAndRepeatedSaves() {
        // 单独地址：只有 address 变化（坐标与 seed 相同）也必须真实落库
        MmsMerchant standalone = new MmsMerchant();
        standalone.setAddress("新门店地址");
        standalone.setLocationLat(31.2);
        standalone.setLocationLng(121.4);
        assertEquals(200, controller.saveDecor(standalone).get(AjaxResult.CODE_TAG));
        assertEquals("新门店地址", text("address"), "仅地址变化（坐标不变）也必须落库");
        assertEquals("31.2", location("location_lat"));
        assertEquals("121.4", location("location_lng"));
        assertEquals("1", location("location_verified"));
        assertEquals(SEEDED_HOURS, text("business_time"), "定位入口不得改动营业时间");
        assertEquals("原权益", text("derivative_benefits"), "定位入口不得改动衍生权益");

        // 混合：地址 + 坐标 + 展示层字段同请求
        Map<String, Object> beforeMixed = snapshotUnrelated(
                "address", "location_lat", "location_lng", "slogan", "tags", "service_tag", "service_text");
        MmsMerchant mixed = new MmsMerchant();
        mixed.setAddress("混合新址");
        mixed.setLocationLat(31.5);
        mixed.setLocationLng(121.5);
        mixed.setSlogan("新口号");
        mixed.setTags("夜间友好,亲子");
        mixed.setServiceTag("¥5");
        mixed.setServiceText("健身体验卡");
        assertEquals(200, controller.saveDecor(mixed).get(AjaxResult.CODE_TAG));
        assertEquals("混合新址", text("address"));
        assertEquals("31.5", location("location_lat"));
        assertEquals("121.5", location("location_lng"));
        assertEquals("新口号", text("slogan"));
        assertEquals("夜间友好,亲子", text("tags"));
        assertEquals("¥5", text("service_tag"));
        assertEquals("健身体验卡", text("service_text"));
        assertUnrelatedUnchanged(beforeMixed,
                "address", "location_lat", "location_lng", "slogan", "tags", "service_tag", "service_text");

        // 同值重复：仍 200、单行、值不变，且不再触发 UPDATE（update_time 保持手工钉住值）
        db.update("UPDATE mms_merchant SET update_time='2020-01-01 00:00:00' WHERE id=77");
        MmsMerchant repeat = new MmsMerchant();
        repeat.setAddress("混合新址");
        repeat.setLocationLat(31.5);
        repeat.setLocationLng(121.5);
        repeat.setSlogan("新口号");
        repeat.setTags("夜间友好,亲子");
        repeat.setServiceTag("¥5");
        repeat.setServiceText("健身体验卡");
        assertEquals(200, controller.saveDecor(repeat).get(AjaxResult.CODE_TAG));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM mms_merchant", Integer.class));
        assertEquals("混合新址", text("address"));
        assertEquals("2020-01-01 00:00:00",
                db.queryForObject("SELECT update_time FROM mms_merchant WHERE id=77", String.class),
                "同值重复保存不得产生 UPDATE");
    }

    @Test
    void decorNullAddressLeavesItAndExplicitEmptyAddressClearsIt() {
        // null=不改：只改坐标与展示字段，地址保持旧值
        Map<String, Object> before = snapshotUnrelated("location_lat", "location_lng", "slogan");
        MmsMerchant coordsOnly = new MmsMerchant();
        coordsOnly.setSlogan("坐标更新");
        coordsOnly.setLocationLat(32.0);
        coordsOnly.setLocationLng(122.0);
        assertEquals(200, controller.saveDecor(coordsOnly).get(AjaxResult.CODE_TAG));
        assertEquals("旧地址", text("address"), "未携带 address 时不得改动既有地址");
        assertEquals("坐标更新", text("slogan"));
        assertEquals("32.0", location("location_lat"));
        assertEquals("122.0", location("location_lng"));
        assertUnrelatedUnchanged(before, "location_lat", "location_lng", "slogan");

        // 显式清空：空串是显式写入（控制器只在 address != null 时透传）
        MmsMerchant clear = new MmsMerchant();
        clear.setAddress("");
        clear.setLocationLat(32.0);
        clear.setLocationLng(122.0);
        assertEquals(200, controller.saveDecor(clear).get(AjaxResult.CODE_TAG));
        assertEquals("", text("address"), "空串是显式清空，必须落库为 ''");

        // 清空后合法地址仍可恢复
        MmsMerchant restore = new MmsMerchant();
        restore.setAddress("恢复地址");
        restore.setLocationLat(32.0);
        restore.setLocationLng(122.0);
        assertEquals(200, controller.saveDecor(restore).get(AjaxResult.CODE_TAG));
        assertEquals("恢复地址", text("address"));
    }

    @Test
    void coopNullCapacityLeavesItAndExplicitClearFlagClearsIt() {
        // M-13:接待人数是 Integer,没有「空串」可表达清空;null=不改(兼容只传部分字段的旧客户端)
        Map<String, Object> before = snapshotUnrelated("capacity");
        MmsMerchant keep = new MmsMerchant();
        keep.setChargeType(1);
        assertEquals(200, controller.saveCoopProfile(keep).get(AjaxResult.CODE_TAG));
        assertEquals("10", location("capacity"), "未声明清空时 null 不得改动既有接待人数");

        // 显式清空:params.clearCapacity=true 才落库为 NULL,且不得顺带改其它列
        MmsMerchant clear = new MmsMerchant();
        clear.setChargeType(1);
        clear.getParams().put(MmsMerchant.CLEAR_CAPACITY, true);
        assertEquals(200, controller.saveCoopProfile(clear).get(AjaxResult.CODE_TAG));
        assertNull(location("capacity"), "显式清空必须落库为 NULL");
        assertUnrelatedUnchanged(before, "capacity");

        // 负控:清空只认承接设置(COOP_MANAGE)链路,装修保存带同一标记不得清空
        MmsMerchant refillForDecor = new MmsMerchant();
        refillForDecor.setCapacity(12);
        refillForDecor.setChargeType(1);
        assertEquals(200, controller.saveCoopProfile(refillForDecor).get(AjaxResult.CODE_TAG));
        MmsMerchant decor = new MmsMerchant();
        decor.setSlogan("原口号");
        decor.getParams().put(MmsMerchant.CLEAR_CAPACITY, true);
        assertEquals(200, controller.saveDecor(decor).get(AjaxResult.CODE_TAG));
        assertEquals("12", location("capacity"), "非承接链路带清空标记不得改动接待人数");

        // 带了数值时清空标记无效,以数值为准;清空后仍可重新填写
        MmsMerchant refill = new MmsMerchant();
        refill.setCapacity(30);
        refill.setChargeType(1);
        refill.getParams().put(MmsMerchant.CLEAR_CAPACITY, true);
        assertEquals(200, controller.saveCoopProfile(refill).get(AjaxResult.CODE_TAG));
        assertEquals("30", location("capacity"));
    }

    /**
     * CU-M-78:解除招牌主推必须把 featured_id 真的清成 NULL。
     *
     * <p>病:接口层把 type=0/id=null 定义为「解除」(validateFeaturedSelection),但共用 update 的
     * featured_id 一律 &lt;if test="featuredId != null"&gt; ⇒ 没有任何 SQL 能把 featured_id 写回 NULL。
     * 公开页确实不再展示(读取方看 featuredType=0),库里却留着上一场活动的 id —— 持久数据与「未设置」
     * 状态矛盾,只读 featured_id 的其它读取方会误用旧关联。</p>
     */
    @Test
    void decorClearingFeaturedWritesNullIdAndKeepsSelectionWritable() {
        db.update("UPDATE mms_merchant SET featured_type=1, featured_id=33 WHERE id=77");
        Map<String, Object> before = snapshotUnrelated("featured_type", "featured_id", "slogan");

        MmsMerchant clear = new MmsMerchant();
        clear.setSlogan("街角补给站");
        clear.setFeaturedType(0);
        clear.setFeaturedId(null);
        assertEquals(200, controller.saveDecor(clear).get(AjaxResult.CODE_TAG));

        assertEquals("0", location("featured_type"), "解除主推后 type 必须落库为 0");
        assertNull(location("featured_id"), "解除主推必须把旧活动 id 清成 NULL");
        assertEquals("街角补给站", text("slogan"), "同一发里的装修字段照常落库");
        assertUnrelatedUnchanged(before, "featured_type", "featured_id", "slogan");

        // 清空只动这两列:重新选一场主推走动态 if 照旧落库
        MmsMerchant pick = new MmsMerchant();
        pick.setId(MERCHANT_ID);
        pick.setFeaturedType(1);
        pick.setFeaturedId(33L);
        assertEquals(1, merchants.updateAuthorizedProfile(pick, OWNER, MerchantPermission.PROFILE_WRITE));
        assertEquals("1", location("featured_type"));
        assertEquals("33", location("featured_id"));
    }

    @Test
    void decorAddressWithoutCoordinatesOrOverlongIsRejectedWithoutPersistence() {
        Map<String, Object> before = snapshotUnrelated();

        MmsMerchant addressOnly = new MmsMerchant();
        addressOnly.setAddress("无坐标地址");
        AjaxResult noCoords = controller.saveDecor(addressOnly);
        assertNotEquals(200, noCoords.get(AjaxResult.CODE_TAG));
        assertEquals("请在地图上选择完整门店地址", noCoords.get(AjaxResult.MSG_TAG));

        MmsMerchant partial = new MmsMerchant();
        partial.setAddress("仅纬度");
        partial.setLocationLat(31.9);
        assertNotEquals(200, controller.saveDecor(partial).get(AjaxResult.CODE_TAG));

        MmsMerchant overlong = new MmsMerchant();
        overlong.setAddress(repeat("地", 256));
        overlong.setLocationLat(31.9);
        overlong.setLocationLng(121.9);
        assertNotEquals(200, controller.saveDecor(overlong).get(AjaxResult.CODE_TAG));

        assertUnrelatedUnchanged(before);

        // 拒绝后合法保存仍可用
        MmsMerchant legal = new MmsMerchant();
        legal.setAddress("合法地址");
        legal.setLocationLat(31.9);
        legal.setLocationLng(121.9);
        assertEquals(200, controller.saveDecor(legal).get(AjaxResult.CODE_TAG));
        assertEquals("合法地址", text("address"));
    }

    @Test
    void allFourAuthorizedCallersPersistOwnFieldsAndOnlyDecorWritesAddress() {
        Map<String, Object> before = snapshotUnrelated(
                "business_status", "capacity", "available_time", "suit_activity_types", "charge_type",
                "demand", "coop_open", "name", "address");

        // 1) /business-status/update：只写营业状态
        assertEquals(200, controller.businessStatusUpdate("0").get(AjaxResult.CODE_TAG));
        assertEquals("0", location("business_status"));
        assertEquals("旧地址", text("address"), "营业状态入口不得改动门店地址");

        // 2) /coop-profile/save：写承接档案
        MmsMerchant coop = new MmsMerchant();
        coop.setCapacity(25);
        coop.setAvailableTime("周末");
        coop.setSuitActivityTypes("策展");
        coop.setChargeType(2);
        coop.setDemand("新合作需求");
        coop.setCoopOpen(1);
        assertEquals(200, controller.saveCoopProfile(coop).get(AjaxResult.CODE_TAG));
        assertEquals("25", location("capacity"));
        assertEquals("旧地址", text("address"), "承接档案保存不得改动门店地址");

        // 3) /update：合同只含 8 个资料字段，address/坐标不在其中，不得被带写
        MmsMerchant update = new MmsMerchant();
        update.setName("改名书店");
        update.setAddress("更新接口试图改地址");
        update.setLocationLat(30.0);
        update.setLocationLng(120.0);
        assertEquals(200, controller.updateUserInfo(update).get(AjaxResult.CODE_TAG));
        assertEquals("改名书店", text("name"));
        assertEquals("旧地址", text("address"), "/update 合同不含 address，不得被带写");
        assertEquals("31.2", location("location_lat"), "坐标只能由定位入口写");

        // 4) /decor/save：唯一能改 address 的授权资料入口；客户端自证的 verified 不生效
        MmsMerchant decor = new MmsMerchant();
        decor.setName("装修不能改名");
        decor.setAddress("装修入口新址");
        decor.setLocationLat(31.2);
        decor.setLocationLng(121.4);
        decor.setLocationVerified(0);
        assertEquals(200, controller.saveDecor(decor).get(AjaxResult.CODE_TAG));
        assertEquals("装修入口新址", text("address"));
        assertEquals("改名书店", text("name"), "装修入口不得改商家名（白名单不宽泛）");
        assertEquals("1", location("location_verified"), "verified 由服务端判定，不信任客户端自证");

        assertUnrelatedUnchanged(before,
                "business_status", "capacity", "available_time", "suit_activity_types", "charge_type",
                "demand", "coop_open", "name", "address");
        assertEquals(SEEDED_HOURS, text("business_time"), "四个入口都不得倒退营业时间");
        assertEquals("原权益", text("derivative_benefits"), "四个入口都不得倒退衍生权益");
    }

    private String location(String column) {
        Object value = db.queryForObject("SELECT " + column + " FROM mms_merchant WHERE id=77", Object.class);
        return value == null ? null : String.valueOf(value);
    }

    private static String repeat(String text, int times) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < times; i++) builder.append(text);
        return builder.toString();
    }

    private AjaxResult updateOnlyHours(String hours) {
        MmsMerchant request = new MmsMerchant();
        request.setBusinessTime(hours);
        return controller.updateUserInfo(request);
    }

    private AjaxResult updateOnlyBenefits(String benefits) {
        MmsMerchant request = new MmsMerchant();
        request.setDerivativeBenefits(benefits);
        return controller.updateUserInfo(request);
    }

    private String text(String column) {
        return db.queryForObject("SELECT " + column + " FROM mms_merchant WHERE id=77", String.class);
    }

    private static final String UNRELATED_COLUMNS = "name,wechat,category_id,logo,description,derivatives,"
            + "website,preference,phone,address,business_license,status,reson,account_status,business_status,"
            + "del_flag,capacity,available_time,suit_activity_types,charge_type,demand,coop_open,cover_image,"
            + "slogan,city_role,story_title,gallery,tags,featured_type,featured_id,service_tag,service_text,"
            + "location_lat,location_lng,location_verified";

    /** 与本次请求无关的资料列：保存字段之外必须一个都不动。excluded 是本次请求的预期变更列。 */
    private Map<String, Object> snapshotUnrelated(String... excluded) {
        java.util.Set<String> skip = new java.util.HashSet<>(java.util.Arrays.asList(excluded));
        StringBuilder columns = new StringBuilder();
        for (String column : UNRELATED_COLUMNS.split(",")) {
            if (skip.contains(column)) continue;
            if (columns.length() > 0) columns.append(',');
            columns.append(column);
        }
        return db.queryForMap("SELECT " + columns + " FROM mms_merchant WHERE id=77");
    }

    private void assertUnrelatedUnchanged(Map<String, Object> before, String... excluded) {
        assertEquals(before, snapshotUnrelated(excluded), "无关字段不得被资料更新连带修改");
    }

    private static class LoggedInController extends ApiMerchantController {
        @Override
        public Long getAppUserId() {
            return OWNER;
        }
    }

    private static final String OPERATOR_DDL = "CREATE TABLE merchant_operator(id BIGINT PRIMARY KEY,"
            + "merchant_id BIGINT,operator_member_id BIGINT,"
            + "active_operator_member_id BIGINT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN operator_member_id ELSE NULL END) STORED,"
            + "role_code VARCHAR(40),status VARCHAR(20),accepted_at DATETIME,revoked_by_member_id BIGINT,"
            + "revoked_at DATETIME,revoke_reason VARCHAR(80),version INT DEFAULT 0,create_by VARCHAR(80),"
            + "create_time DATETIME,update_by VARCHAR(80),update_time DATETIME,"
            + "UNIQUE KEY active_member(active_operator_member_id))";

    private static final String MERCHANT_DDL = "CREATE TABLE mms_merchant ("
            + "id BIGINT AUTO_INCREMENT PRIMARY KEY, member_id BIGINT UNIQUE, name VARCHAR(255), wechat VARCHAR(255),"
            + "category_id INT, logo VARCHAR(500), description TEXT, derivatives TEXT, derivative_benefits VARCHAR(100), website VARCHAR(255),"
            + "preference VARCHAR(255), phone VARCHAR(50), address VARCHAR(255), business_time VARCHAR(255),"
            + "business_license VARCHAR(255), status BIGINT DEFAULT 0, reson VARCHAR(255), disable_reason VARCHAR(500), account_status BIGINT DEFAULT 0,"
            + "business_status INT, del_flag INT DEFAULT 0, capacity INT, available_time VARCHAR(255),"
            + "suit_activity_types VARCHAR(255), charge_type INT, demand TEXT, coop_open INT,"
            + "cover_image VARCHAR(500), slogan VARCHAR(255), city_role VARCHAR(255), story_title VARCHAR(255),"
            + "gallery TEXT, tags VARCHAR(500), featured_type INT, featured_id BIGINT,"
            + "service_tag VARCHAR(8), service_text VARCHAR(64),"
            + "location_lat DOUBLE, location_lng DOUBLE, location_verified INT,"
            + "create_by VARCHAR(64), create_time DATETIME, update_by VARCHAR(64), update_time DATETIME)";
}
