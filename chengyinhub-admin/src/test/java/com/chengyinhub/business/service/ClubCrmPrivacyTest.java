package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.vo.ClubCustomerVO;
import com.chengyinhub.business.mapper.ClubCrmMapper;
import com.chengyinhub.business.service.impl.ClubCrmAppServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 俱乐部客户接口的 PII 与权限分档。
 *
 * 守的是「脱敏必须在服务端做」这条:不管哪个岗位来看,回包里都不许出现完整手机号。
 * 三档各自断言一次,并且用结构断言把「VO 上不许有 phone 字段」钉死 —— 光断言当前这一版
 * 的字符串是不够的,加一个 getPhone() 就能把明文重新接回下发路径。
 */
class ClubCrmPrivacyTest {

    private static final Long CLUB = 42L;
    private static final Long TOPIC = 77L;
    private static final Long OWNER = 7L;
    private static final Long CHECKIN = 9L;
    private static final Long CUSTOMER = 41L;
    private static final String PLAIN_PHONE = "13812345621";
    private static final String MASKED_PHONE = "138****5621";

    private ClubCrmAppServiceImpl service;
    private ClubCrmMapper mapper;
    private IClubService clubService;
    private IClubMemberService memberService;
    private IClubAccessService accessService;

    @BeforeEach
    void setUp() {
        service = new ClubCrmAppServiceImpl();
        mapper = mock(ClubCrmMapper.class);
        clubService = mock(IClubService.class);
        memberService = mock(IClubMemberService.class);
        accessService = mock(IClubAccessService.class);
        ReflectionTestUtils.setField(service, "clubCrmMapper", mapper);
        ReflectionTestUtils.setField(service, "clubService", clubService);
        ReflectionTestUtils.setField(service, "clubMemberService", memberService);
        ReflectionTestUtils.setField(service, "clubAccessService", accessService);

        Club club = new Club();
        club.setId(CLUB);
        club.setMemberId(OWNER);
        when(clubService.selectClubById(CLUB)).thenReturn(club);
        when(memberService.canGovernClub(any(Club.class), eq(OWNER))).thenReturn(true);
        when(memberService.canGovernClub(any(Club.class), eq(CHECKIN))).thenReturn(false);
        when(accessService.resolve(eq(OWNER), eq(CLUB), any())).thenReturn(context(OWNER, ClubRoleCode.CLUB_OWNER));
        when(accessService.resolve(eq(CHECKIN), eq(CLUB), any())).thenReturn(context(CHECKIN, ClubRoleCode.EVENT_CHECKIN));
    }

    private ClubAccessContext context(Long principal, String roleCode) {
        Set<String> roles = Collections.singleton(roleCode);
        return new ClubAccessContext(principal, CLUB, OWNER, null, roles);
    }

    private ClubCustomerVO.RawCustomer rawCustomer() {
        ClubCustomerVO.RawCustomer raw = new ClubCustomerVO.RawCustomer();
        raw.setMemberId(CUSTOMER);
        raw.setDisplayName("林小满");
        raw.setAvatar("");
        raw.setPhone(PLAIN_PHONE);
        raw.setVerifiedCount(6);
        raw.setPendingCount(0);
        raw.setRefundedCount(1);
        raw.setPaidAmount(new BigDecimal("486.00"));
        raw.setLastInteractionTime(new Date());
        return raw;
    }

    private ClubCustomerVO.RawTopicRow rawTopicRow() {
        ClubCustomerVO.RawTopicRow raw = new ClubCustomerVO.RawTopicRow();
        raw.setRegistrationId(1001L);
        raw.setMemberId(CUSTOMER);
        raw.setDisplayName("林夏");
        raw.setPhone(PLAIN_PHONE);
        raw.setOrderNo("CY083101");
        raw.setPaymentStatus(2);
        raw.setVerificationStatus(0);
        raw.setSessionTime(new Date(System.currentTimeMillis() + 86_400_000L));
        raw.setSessionId(501L);
        return raw;
    }

    private String json(Object value) throws Exception {
        return new ObjectMapper().writeValueAsString(value);
    }

    @Test
    void 主理人看到的手机号是脱敏串明文不得出现在回包里() throws Exception {
        when(mapper.selectClubCustomers(eq(CLUB), anyString(), anyString(), anyInt()))
                .thenReturn(Collections.singletonList(rawCustomer()));
        when(mapper.selectProfiles(CLUB)).thenReturn(Collections.<ClubCustomerVO.Profile>emptyList());
        when(mapper.countClubCustomers(CLUB)).thenReturn(328);
        when(mapper.countClubCustomersNewThisMonth(CLUB)).thenReturn(24);

        ClubCustomerVO.ListPage page = service.listCustomers(OWNER, CLUB, "all", "");
        assertEquals(MASKED_PHONE, page.getItems().get(0).getPhoneText());
        String body = json(page);
        assertFalse(body.contains(PLAIN_PHONE), "列表回包里出现了完整手机号:" + body);
    }

    @Test
    void 核销员拿不到号码只拿到替代说明() throws Exception {
        when(mapper.selectTopicCustomers(CLUB, TOPIC)).thenReturn(Collections.singletonList(rawTopicRow()));

        ClubCustomerVO.TopicPage page = service.topicCustomers(CHECKIN, CLUB, TOPIC, "all");
        String phoneText = page.getSessions().get(0).getRows().get(0).getPhoneText();
        assertEquals(ClubCrmAppServiceImpl.PHONE_UNAVAILABLE_TEXT, phoneText);
        String body = json(page);
        assertFalse(body.contains(PLAIN_PHONE), "主题内客户回包里出现了完整手机号:" + body);
        assertFalse(body.contains(MASKED_PHONE), "核销员这一档连脱敏号码都不该拿到");
    }

    @Test
    void 核销员进不了客户列表与详情() {
        assertThrows(ServiceException.class, () -> service.listCustomers(CHECKIN, CLUB, "all", ""));
        assertThrows(ServiceException.class, () -> service.customerDetail(CHECKIN, CLUB, CUSTOMER));
        assertThrows(ServiceException.class,
                () -> service.saveTagRemark(CHECKIN, CLUB, CUSTOMER, Arrays.asList("常客"), "备注", "req-1"));
    }

    @Test
    void 没有财务读权限时金额下发为空而不是零() throws Exception {
        // legacy 管理员:canGovernClub 为真(进得来),但策略表里没有 FINANCE_READ
        Long admin = 11L;
        when(memberService.canGovernClub(any(Club.class), eq(admin))).thenReturn(true);
        when(accessService.resolve(eq(admin), eq(CLUB), any()))
                .thenReturn(context(admin, ClubRoleCode.CLUB_LEGACY_ADMIN));
        when(mapper.selectClubCustomerSummary(CLUB, CUSTOMER)).thenReturn(rawCustomer());
        when(mapper.selectClubCustomerRecords(eq(CLUB), eq(CUSTOMER), anyInt()))
                .thenReturn(Collections.<ClubCustomerVO.Record>emptyList());
        when(mapper.selectProfile(CLUB, CUSTOMER)).thenReturn(null);

        ClubCustomerVO.Detail denied = service.customerDetail(admin, CLUB, CUSTOMER);
        assertNull(denied.getSummary().getPaidAmount(), "没有 FINANCE_READ 时金额必须缺席,给 0 是假事实");
        assertEquals(MASKED_PHONE, denied.getSummary().getPhoneText(), "legacy 管理员有 MEMBER_LIST_READ,看得到脱敏号");

        ClubCustomerVO.Detail owner = service.customerDetail(OWNER, CLUB, CUSTOMER);
        assertEquals(0, new BigDecimal("486.00").compareTo(owner.getSummary().getPaidAmount()));
        assertFalse(json(owner).contains(PLAIN_PHONE));
    }

    @Test
    void 标签与备注只对本俱乐部成交过的人建档且有长度上限() {
        when(mapper.selectClubCustomerSummary(CLUB, CUSTOMER)).thenReturn(rawCustomer());
        when(mapper.selectClubCustomerSummary(CLUB, 999L)).thenReturn(null);

        assertThrows(ServiceException.class,
                () -> service.saveTagRemark(OWNER, CLUB, 999L, Arrays.asList("常客"), "", "req-1"));
        assertThrows(ServiceException.class,
                () -> service.saveTagRemark(OWNER, CLUB, CUSTOMER, Arrays.asList("常客"), "", ""));
        assertThrows(ServiceException.class, () -> service.saveTagRemark(OWNER, CLUB, CUSTOMER,
                Arrays.asList("一二三四五六七八九十十一十二十三"), "", "req-1"));
        assertThrows(ServiceException.class, () -> service.saveTagRemark(OWNER, CLUB, CUSTOMER,
                Arrays.asList("1", "2", "3", "4", "5", "6", "7", "8", "9"), "", "req-1"));

        service.saveTagRemark(OWNER, CLUB, CUSTOMER, Arrays.asList(" 常客 ", "常客", ""), " 喜欢靠窗 ", "req-1");
        org.mockito.Mockito.verify(mapper).upsertProfile(CLUB, CUSTOMER, "[\"常客\"]", "喜欢靠窗", OWNER, "req-1");
    }

    @Test
    void 标签存量脏数据只降级不炸页() {
        assertEquals(Collections.emptyList(), ClubCrmAppServiceImpl.parseTags(null));
        assertEquals(Collections.emptyList(), ClubCrmAppServiceImpl.parseTags("{不是数组"));
        assertEquals(Arrays.asList("常客", "带朋友"), ClubCrmAppServiceImpl.parseTags("[\"常客\",\" 带朋友 \",\"\"]"));
    }

    /**
     * 结构负控:下发用的三个 VO 上都不许出现 phone 字段。
     * 只断言"当前这一版的字符串里没有明文"是不够的 —— 谁给 Row 补一个 getPhone(),
     * 明文立刻又能从聚合结果流到回包,而上面那些字符串断言可能仍然是绿的。
     */
    @Test
    void 下发VO上不许有原始手机号字段() {
        for (Class<?> type : new Class<?>[]{
                ClubCustomerVO.Row.class, ClubCustomerVO.Summary.class, ClubCustomerVO.TopicRow.class }) {
            for (Method method : type.getMethods()) {
                String name = method.getName();
                assertFalse("getPhone".equals(name) || "getMobile".equals(name),
                        type.getSimpleName() + " 上出现了 " + name + " —— 下发 VO 不得携带原始号码,只留 phoneText");
            }
        }
        // 正控:phoneText 这条出口确实还在,否则上面的断言会因为字段整体消失而空转
        assertTrue(hasGetter(ClubCustomerVO.Row.class, "getPhoneText"));
        assertTrue(hasGetter(ClubCustomerVO.Summary.class, "getPhoneText"));
        assertTrue(hasGetter(ClubCustomerVO.TopicRow.class, "getPhoneText"));
    }

    private static boolean hasGetter(Class<?> type, String name) {
        for (Method method : type.getMethods()) if (method.getName().equals(name)) return true;
        return false;
    }

    /** 唯一的手机号出口:两条路都不返回原始号码。 */
    @Test
    void 手机号出口两条路都不返回原始号码() {
        assertEquals(MASKED_PHONE, ClubCrmAppServiceImpl.phoneText(PLAIN_PHONE, true));
        assertEquals(ClubCrmAppServiceImpl.PHONE_UNAVAILABLE_TEXT, ClubCrmAppServiceImpl.phoneText(PLAIN_PHONE, false));
        assertEquals("", ClubCrmAppServiceImpl.phoneText(null, true));
        // 幂等:已经脱敏过的值再过一次不会被二次打码
        assertEquals(MASKED_PHONE, ClubCrmAppServiceImpl.phoneText(MASKED_PHONE, true));
    }

    @Test
    void 主题内客户按场次分组并给出状态计数() {
        ClubCustomerVO.RawTopicRow verified = rawTopicRow();
        verified.setRegistrationId(1002L);
        verified.setVerificationStatus(1);
        verified.setVerificationTime(new Date());
        ClubCustomerVO.RawTopicRow refunded = rawTopicRow();
        refunded.setRegistrationId(1003L);
        refunded.setPaymentStatus(4);
        List<ClubCustomerVO.RawTopicRow> rows = Arrays.asList(rawTopicRow(), verified, refunded);
        when(mapper.selectTopicCustomers(CLUB, TOPIC)).thenReturn(rows);

        ClubCustomerVO.TopicPage all = service.topicCustomers(OWNER, CLUB, TOPIC, "all");
        assertEquals(2, all.getSoldCount().intValue(), "已退款不计入已售");
        assertEquals(1, all.getPendingCount().intValue());
        assertEquals(1, all.getVerifiedCount().intValue());
        assertEquals(1, all.getSessions().size(), "同一场次的行必须并成一组");
        assertEquals(3, all.getSessions().get(0).getRows().size());

        ClubCustomerVO.TopicPage onlyVerified = service.topicCustomers(OWNER, CLUB, TOPIC, "verified");
        assertEquals(1, onlyVerified.getSessions().get(0).getRows().size());
        assertEquals("已核销", onlyVerified.getSessions().get(0).getRows().get(0).getStatusText());
        // 计数是全量口径,不随筛选变化,否则 chip 上的数字会自相矛盾
        assertEquals(2, onlyVerified.getSoldCount().intValue());
    }

    /**
     * ClubCrmMapper.xml 必须能被 MyBatis 真正解析。
     * 本仓只有连真库的 *IT 会碰 mapper,而 IT 在 CI 不跑 —— 也就是说 XML 写坏了
     * 单测全绿、启动时才炸。这条用不带数据源的 Configuration 把 XML 解析一遍,
     * resultMap 的类名写错、<include> 指到不存在的片段、标签没闭合,都会在这里红。
     * (它证明不了 SQL 语义对不对 —— 那只有真库能回答。)
     */
    @Test
    void mapper定义必须能被MyBatis解析() throws Exception {
        Configuration cfg = new Configuration();
        try (InputStream in = Resources.getResourceAsStream("mapper/business/ClubCrmMapper.xml")) {
            new XMLMapperBuilder(in, cfg, "mapper/business/ClubCrmMapper.xml", cfg.getSqlFragments()).parse();
        }
        String ns = "com.chengyinhub.business.mapper.ClubCrmMapper.";
        for (String id : new String[]{"selectClubCustomers", "countClubCustomers", "countClubCustomersNewThisMonth",
                "selectClubCustomerSummary", "selectClubCustomerRecords", "selectTopicCustomers",
                "selectProfile", "selectProfiles", "upsertProfile"}) {
            assertTrue(cfg.hasStatement(ns + id), "mapper 里缺少语句 " + id);
        }
    }

    @Test
    void 未登录一律拒绝() {
        assertThrows(ServiceException.class, () -> service.listCustomers(null, CLUB, "all", ""));
        assertThrows(ServiceException.class, () -> service.topicCustomers(null, CLUB, TOPIC, "all"));
        assertThrows(ServiceException.class, () -> service.customerDetail(OWNER, null, CUSTOMER));
    }
}
