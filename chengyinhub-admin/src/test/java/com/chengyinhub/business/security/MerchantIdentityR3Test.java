package com.chengyinhub.business.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.UmsMemberIdentity;
import com.chengyinhub.business.domain.dto.MemberIdentityRequest;
import com.chengyinhub.business.domain.dto.MerchantRegistrationDTO;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.UmsMemberIdentityMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.impl.MemberIdentityService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.IdCardUtils;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import com.chengyinhub.web.controller.api.ApiMemberIdentityController;
import com.chengyinhub.web.controller.api.ApiMerchantController;
import com.chengyinhub.web.controller.business.MmsMerchantController;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R3 商家入驻实名(realName + idCardNumber):校验、加密落库、各读路径只出脱敏值、日志无明文、存量可读。
 *
 * <p>库是 H2(MySQL 模式)+ 真 mapper XML + 真 PiiCryptoTypeHandler,密钥每次运行随机生成,不进仓库。
 */
class MerchantIdentityR3Test {

    /** 合法号码(GB 11643 校验位 7)。 */
    private static final String VALID_ID = "110105199003070017";
    /** 末位为 X 的合法号码。 */
    private static final String VALID_ID_X = "11010519491231002X";
    private static final String MASKED = "110***********0017";
    private static final long MEMBER = 9301L;

    private PiiCryptoService previousCrypto;
    private SqlSession session;
    private UmsMemberIdentityMapper identityMapper;
    private MemberIdentityService identityService;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final List<Logger> touchedLoggers = new ArrayList<>();
    private final List<Level> previousLevels = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        previousCrypto = PiiCryptoService.getInstance();
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        new PiiCryptoService(Base64.getEncoder().encodeToString(key), "").register();

        Configuration configuration = new Configuration();
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(),
                new PooledDataSource("org.h2.Driver",
                        "jdbc:h2:mem:merchant_identity_r3_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                        "sa", "")));
        try (InputStream in = Resources.getResourceAsStream("mapper/business/UmsMemberIdentityMapper.xml")) {
            new XMLMapperBuilder(in, configuration, "mapper/business/UmsMemberIdentityMapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        session = new SqlSessionFactoryBuilder().build(configuration).openSession(true);
        try (Statement st = session.getConnection().createStatement()) {
            // 与 chengyinhub-admin/sql/migration_ums_member_identity_20260919.sql 同列同宽
            st.execute("create table ums_member_identity (id bigint auto_increment primary key,"
                    + " member_id bigint not null, real_name varchar(768) not null, id_card varchar(768) not null,"
                    + " id_card_hash char(64) not null, source varchar(24) not null, consent_time datetime not null,"
                    + " create_time datetime not null, update_time datetime null, unique key uk_member (member_id))");
        }
        identityMapper = session.getMapper(UmsMemberIdentityMapper.class);
        identityService = new MemberIdentityService();
        ReflectionTestUtils.setField(identityService, "identityMapper", identityMapper);
        ReflectionTestUtils.setField(identityService, "consentRecordService", mock(IConsentRecordService.class));

        logs.start();
        for (String name : new String[]{org.slf4j.Logger.ROOT_LOGGER_NAME, "com.chengyinhub",
                "com.chengyinhub.business.mapper"}) {
            Logger logger = (Logger) LoggerFactory.getLogger(name);
            touchedLoggers.add(logger);
            previousLevels.add(logger.getLevel());
            logger.setLevel(Level.ALL);
        }
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        for (int i = 0; i < touchedLoggers.size(); i++) {
            touchedLoggers.get(i).setLevel(previousLevels.get(i));
        }
        if (session != null) session.close();
        ReflectionTestUtils.setField(PiiCryptoService.class, "instance", previousCrypto);
    }

    // ---------------------------------------------------------------- 校验

    @Test
    void 校验_合法号码与末位小写x都放行且x归一为大写() {
        assertTrue(IdCardUtils.isValid(VALID_ID));
        assertTrue(IdCardUtils.isValid(VALID_ID_X));
        assertTrue(IdCardUtils.isValid(VALID_ID_X.toLowerCase()));
        assertEquals(VALID_ID_X, IdCardUtils.normalize(VALID_ID_X.toLowerCase()));
        identityService.validateSubmission("张三", VALID_ID, true);
        identityService.validateSubmission("李四", VALID_ID_X.toLowerCase(), true);
    }

    @Test
    void 校验_校验位错误与长度不对都拒_文案统一且不回显号码() {
        String badChecksum = "110105199003070018";
        for (String bad : new String[]{badChecksum, "11010519900307001", "1101051990030700177", "", null,
                "11010519900307001Y", "1101051990030700X7"}) {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> identityService.validateSubmission("张三", bad, true), String.valueOf(bad));
            assertEquals(MemberIdentityService.MSG_ID_CARD_INVALID, e.getMessage());
            assertTrue(e.getMessage().startsWith("身份证号格式不正确"));
            if (bad != null && !bad.isEmpty()) assertFalse(e.getMessage().contains(bad));
        }
    }

    @Test
    void 校验_姓名2到30字() {
        identityService.validateSubmission("张三", VALID_ID, true);
        identityService.validateSubmission("阿".repeat(30), VALID_ID, true);
        identityService.validateSubmission("买买提·艾力", VALID_ID, true);
        assertThrows(ServiceException.class, () -> identityService.validateSubmission("张", VALID_ID, true));
        assertThrows(ServiceException.class, () -> identityService.validateSubmission("阿".repeat(31), VALID_ID, true));
        assertThrows(ServiceException.class, () -> identityService.validateSubmission("  ", VALID_ID, true));
        assertThrows(ServiceException.class, () -> identityService.validateSubmission(null, VALID_ID, true));
        assertThrows(ServiceException.class, () -> identityService.validateSubmission("张三3", VALID_ID, true));
        assertThrows(ServiceException.class, () -> identityService.validateSubmission("张三", VALID_ID, false));
    }

    // ---------------------------------------------------------------- 加密与脱敏

    @Test
    void 加密往返_库里只有密文_读回原文_同号两次密文不同() throws Exception {
        identityService.register(MEMBER, "张三", VALID_ID_X.toLowerCase(), true,
                MemberIdentityService.SOURCE_MERCHANT_APPLY, null);
        identityService.register(MEMBER + 1, "李四", VALID_ID_X, true,
                MemberIdentityService.SOURCE_MERCHANT_APPLY, null);

        List<String> rawIds = new ArrayList<>();
        try (Statement st = session.getConnection().createStatement();
             ResultSet rs = st.executeQuery("select real_name, id_card, id_card_hash from ums_member_identity order by member_id")) {
            while (rs.next()) {
                String rawId = rs.getString("id_card");
                assertTrue(rawId.startsWith("v1:"), "id_card 必须是 AES-GCM 密文");
                assertFalse(rawId.toUpperCase().contains("1949123"), "密文里不许露出号码片段");
                assertTrue(rs.getString("real_name").startsWith("v1:"));
                assertEquals(64, rs.getString("id_card_hash").length());
                rawIds.add(rawId);
            }
        }
        assertEquals(2, rawIds.size());
        assertNotEquals(rawIds.get(0), rawIds.get(1), "随机 nonce:同一号码两次加密结果必须不同");

        UmsMemberIdentity back = identityMapper.selectByMemberId(MEMBER);
        assertEquals(VALID_ID_X, back.getIdCard(), "解密还原且小写 x 已归一为大写");
        assertEquals("张三", back.getRealName());
    }

    @Test
    void 脱敏格式_前3后4() {
        assertEquals(MASKED, IdCardUtils.mask(VALID_ID));
        assertEquals("110***********002X", IdCardUtils.mask(VALID_ID_X.toLowerCase()));
        assertEquals(18, IdCardUtils.mask(VALID_ID).length());
        assertNull(IdCardUtils.mask(null));
        assertEquals("*****", IdCardUtils.mask("12345"));
    }

    // ---------------------------------------------------------------- 入驻提交 → 各读路径

    @Test
    void 入驻带实名_落密文_玩家商家状态接口与后台详情只回脱敏值() throws Exception {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        when(merchants.insertMmsMerchant(any())).thenReturn(1);
        ApiMerchantController controller = merchantController(merchants);

        MerchantRegistrationDTO request = newApplication();
        request.setRealName("张三");
        request.setIdCardNumber(VALID_ID);
        request.setIdentityConsent(true);
        AjaxResult submit = controller.merchantRegistration(request);
        assertEquals(200, submit.get(AjaxResult.CODE_TAG));
        verify(merchants).insertMmsMerchant(any());
        assertNoPlaintext(json(submit));

        // 玩家 / 商家共用的本人状态接口
        ApiMemberIdentityController statusApi = new ApiMemberIdentityController() {
            @Override
            public Long getAppUserId() { return MEMBER; }
        };
        ReflectionTestUtils.setField(statusApi, "memberIdentityService", identityService);
        AjaxResult status = statusApi.status();
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) status.get(AjaxResult.DATA_TAG);
        assertEquals(true, data.get("registered"));
        assertEquals(MASKED, data.get("idCardNumberMasked"));
        assertEquals("张*", data.get("realNameMasked"));
        assertNoPlaintext(json(status));

        // 后台商家详情(入驻审核)
        MmsMerchant stored = new MmsMerchant();
        stored.setId(77L);
        stored.setMemberId(MEMBER);
        IMmsMerchantService adminMerchants = mock(IMmsMerchantService.class);
        when(adminMerchants.selectMmsMerchantById(77L)).thenReturn(stored);
        MmsMerchantController admin = new MmsMerchantController();
        ReflectionTestUtils.setField(admin, "mmsMerchantService", adminMerchants);
        ReflectionTestUtils.setField(admin, "memberIdentityService", identityService);
        AjaxResult detail = admin.getInfo(77L);
        @SuppressWarnings("unchecked")
        Map<String, Object> owner = (Map<String, Object>) detail.get("ownerIdentity");
        assertEquals(MASKED, owner.get("idCardNumberMasked"));
        assertEquals("张*", owner.get("realNameMasked"));
        assertNoPlaintext(json(detail));

        assertNoPlaintextInLogs();
    }

    @Test
    void 入驻带坏号码_拒绝且一行不写_日志无明文() throws Exception {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        ApiMerchantController controller = merchantController(merchants);
        MerchantRegistrationDTO request = newApplication();
        request.setRealName("张三");
        request.setIdCardNumber("110105199003070018");
        request.setIdentityConsent(true);

        AjaxResult result = controller.merchantRegistration(request);

        assertNotEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals(MemberIdentityService.MSG_ID_CARD_INVALID, result.get(AjaxResult.MSG_TAG));
        verify(merchants, never()).insertMmsMerchant(any());
        assertFalse(identityMapper.existsByMemberId(MEMBER));

        // 换证冲突走全局异常处理器(会 log.error),日志里也不能有号码
        // (裸 MyBatis 不做 Spring 异常翻译,这里用 mock mapper 模拟 uk_member 撞车)
        UmsMemberIdentityMapper conflicting = mock(UmsMemberIdentityMapper.class);
        UmsMemberIdentity existing = new UmsMemberIdentity();
        existing.setIdCard(VALID_ID);
        when(conflicting.insertIdentity(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("uk_member"));
        when(conflicting.selectByMemberId(MEMBER)).thenReturn(existing);
        MemberIdentityService conflictService = new MemberIdentityService();
        ReflectionTestUtils.setField(conflictService, "identityMapper", conflicting);
        ReflectionTestUtils.setField(conflictService, "consentRecordService", mock(IConsentRecordService.class));
        ServiceException conflict = assertThrows(ServiceException.class, () -> conflictService.register(
                MEMBER, "张三", VALID_ID_X, true, MemberIdentityService.SOURCE_MERCHANT_APPLY, null));
        new GlobalExceptionHandler().handleServiceException(conflict, new MockHttpServletRequest());
        assertNoPlaintext(conflict.getMessage());
        assertNoPlaintextInLogs();
    }

    @Test
    void 入驻没带实名且从未登记_拒绝() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        AjaxResult result = merchantController(merchants).merchantRegistration(newApplication());
        assertEquals(MemberIdentityService.MSG_IDENTITY_REQUIRED, result.get(AjaxResult.MSG_TAG));
        verify(merchants, never()).insertMmsMerchant(any());
    }

    @Test
    void 入驻只带一半或没勾同意_拒绝() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MerchantRegistrationDTO onlyName = newApplication();
        onlyName.setRealName("张三");
        onlyName.setIdentityConsent(true);
        assertEquals(MemberIdentityService.MSG_ID_CARD_INVALID,
                merchantController(merchants).merchantRegistration(onlyName).get(AjaxResult.MSG_TAG));

        MerchantRegistrationDTO noConsent = newApplication();
        noConsent.setRealName("张三");
        noConsent.setIdCardNumber(VALID_ID);
        assertTrue(String.valueOf(merchantController(merchants).merchantRegistration(noConsent)
                .get(AjaxResult.MSG_TAG)).contains("同意"));
        verify(merchants, never()).insertMmsMerchant(any());
    }

    @Test
    void 已在发布者实名登记过的会员_入驻不带字段仍放行() {
        identityService.register(MEMBER, "张三", VALID_ID, true, MemberIdentityService.SOURCE_TOPIC_PUBLISH, null);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        when(merchants.insertMmsMerchant(any())).thenReturn(1);
        assertEquals(200, merchantController(merchants).merchantRegistration(newApplication()).get(AjaxResult.CODE_TAG));
    }

    // ---------------------------------------------------------------- 存量

    @Test
    void 存量商家没有实名_改资料与后台详情照常() {
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        MmsMerchant legacy = new MmsMerchant();
        legacy.setId(11L);
        legacy.setMemberId(MEMBER);
        legacy.setStatus(0L);
        legacy.setAccountStatus(0L);
        legacy.setDelFlag(0);
        when(merchants.selectMmsMerchantById(11L)).thenReturn(legacy);
        when(merchants.updateOwnerRegistrationProfile(any(), any())).thenReturn(1);
        MerchantRegistrationDTO edit = newApplication();
        edit.setId(11L);
        assertEquals(200, merchantController(merchants).merchantRegistration(edit).get(AjaxResult.CODE_TAG));

        MmsMerchantController admin = new MmsMerchantController();
        ReflectionTestUtils.setField(admin, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(admin, "memberIdentityService", identityService);
        AjaxResult detail = admin.getInfo(11L);
        assertEquals(200, detail.get(AjaxResult.CODE_TAG));
        assertNotNull(detail.get(AjaxResult.DATA_TAG));
        assertNull(detail.get("ownerIdentity"));
    }

    @Test
    void 存量明文行兼容读_也只出脱敏值() throws Exception {
        try (Statement st = session.getConnection().createStatement()) {
            st.execute("insert into ums_member_identity(member_id, real_name, id_card, id_card_hash, source,"
                    + " consent_time, create_time) values (" + MEMBER + ", '王五', '" + VALID_ID + "', '"
                    + "0".repeat(64) + "', 'club_apply', now(), now())");
        }
        Map<String, Object> view = identityService.maskedView(MEMBER);
        assertEquals(MASKED, view.get("idCardNumberMasked"));
        assertEquals("王*", view.get("realNameMasked"));
        assertNull(identityService.maskedView(MEMBER + 99));
    }

    // ---------------------------------------------------------------- 接线

    @Test
    void 入驻接口与登记接口都没有操作日志_DTO不打印自己() throws Exception {
        Method submit = ApiMerchantController.class.getMethod("merchantRegistration", MerchantRegistrationDTO.class);
        for (Annotation a : submit.getAnnotations()) {
            assertFalse(a.annotationType().getName().endsWith(".Log"), "入驻接口挂 @Log 会把身份证号写进 sys_oper_log");
        }
        // 也不许挂 Bean Validation:校验失败时 rejected value 会被异常处理器打进日志
        for (String field : new String[]{"realName", "idCardNumber"}) {
            for (Annotation a : MerchantRegistrationDTO.class.getDeclaredField(field).getAnnotations()) {
                assertFalse(a.annotationType().getName().startsWith("jakarta.validation"), field);
            }
        }
        MerchantRegistrationDTO dto = new MerchantRegistrationDTO();
        dto.setIdCardNumber(VALID_ID);
        assertNoPlaintext(dto.toString());

        // iOS 同名字段在独立登记接口上也认
        MemberIdentityRequest req = new ObjectMapper().readValue(
                "{\"realName\":\"张三\",\"idCardNumber\":\"" + VALID_ID + "\",\"consent\":true}", MemberIdentityRequest.class);
        assertEquals(VALID_ID, req.getIdCard());
    }

    // ---------------------------------------------------------------- helpers

    private ApiMerchantController merchantController(IMmsMerchantService merchants) {
        ApiMerchantController controller = new ApiMerchantController() {
            @Override
            public Long getAppUserId() { return MEMBER; }
        };
        UmsMemberMapper members = mock(UmsMemberMapper.class);
        when(members.lockMemberById(MEMBER)).thenReturn(MEMBER);
        ReflectionTestUtils.setField(controller, "submissionSafety",
                mock(com.chengyinhub.business.service.support.UgcSubmissionSafety.class));
        ReflectionTestUtils.setField(controller, "umsMemberMapper", members);
        ReflectionTestUtils.setField(controller, "merchantOperatorMapper", mock(MerchantOperatorMapper.class));
        ReflectionTestUtils.setField(controller, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(controller, "memberIdentityService", identityService);
        return controller;
    }

    private static MerchantRegistrationDTO newApplication() {
        MerchantRegistrationDTO request = new MerchantRegistrationDTO();
        request.setName("城瘾咖啡");
        request.setAddress("黄浦路 1 号");
        request.setLocationLat(31.2D);
        request.setLocationLng(121.4D);
        return request;
    }

    private static String json(Object value) throws Exception {
        return new ObjectMapper().writeValueAsString(value);
    }

    private static void assertNoPlaintext(String text) {
        if (text == null) return;
        String upper = text.toUpperCase();
        for (String id : new String[]{VALID_ID, VALID_ID_X, "110105199003070018"}) {
            assertFalse(upper.contains(id), "出现了明文身份证号: " + id);
            assertFalse(upper.contains(id.substring(3, 14)), "出现了身份证号中段");
        }
        assertFalse(text.contains("张三"), "出现了明文姓名");
    }

    private void assertNoPlaintextInLogs() {
        assertFalse(logs.list.isEmpty(), "日志捕获没接上,本断言等于没跑");
        for (ILoggingEvent event : logs.list) {
            assertNoPlaintext(event.getFormattedMessage());
            for (IThrowableProxy t = event.getThrowableProxy(); t != null; t = t.getCause()) {
                assertNoPlaintext(t.getMessage());
            }
        }
    }
}
