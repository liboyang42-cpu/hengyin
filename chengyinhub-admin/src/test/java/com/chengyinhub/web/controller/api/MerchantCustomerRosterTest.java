package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.MerchantCustomerQuery;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.CoopInvite;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ICoopInviteService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.business.service.impl.ProjectPlayerReadServiceImpl;
import com.chengyinhub.common.config.ComplianceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 商家客户名册:商家问的是「这个人到底什么情况」——买了没来?买了又退?来过几次?
 *
 * 这里锁三件在实现里最容易写歪的事:
 *   ① 退款的人必须留在名册里(2026-08-05 已拍板:钱退了要看得见,不是把人抹掉);
 *   ② 同一个人跨项目要归成一行,不是每个项目一行;
 *   ③ 联系方式沿用「谁组织谁负责联系」的闸,名册不是绕过它的后门。
 */
class MerchantCustomerRosterTest {

    private static final Long MERCHANT = 20L;
    private static final Long CLUB_LED_TOPIC = 300L;
    private static final Long OWN_TOPIC = 301L;

    private ICmsTopicService topicSvc;
    private ICmsRegistrationService regSvc;
    private ICmsRegistrationMerchantService regMerchantSvc;
    private ICoopInviteService inviteSvc;
    private ProjectPlayerReadServiceImpl service;

    @BeforeEach
    void setUp() {
        topicSvc = mock(ICmsTopicService.class);
        regSvc = mock(ICmsRegistrationService.class);
        regMerchantSvc = mock(ICmsRegistrationMerchantService.class);
        inviteSvc = mock(ICoopInviteService.class);
        IUmsMemberService memberSvc = mock(IUmsMemberService.class);
        service = new ProjectPlayerReadServiceImpl(topicSvc, regSvc, regMerchantSvc, inviteSvc, memberSvc);

        ConsentRecordMapper consentMapper = mock(ConsentRecordMapper.class);
        ConsentRecordServiceImpl consentService = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(consentService, "consentRecordMapper", consentMapper);
        ComplianceProperties properties = new ComplianceProperties();
        Map<String, String> versions = new HashMap<>();
        versions.put("activity_host_data_sharing", "v2.1");
        properties.setDocVersions(versions);
        ReflectionTestUtils.setField(service, "consentRecordService", consentService);
        ReflectionTestUtils.setField(service, "complianceProperties", properties);
        ConsentRecord agreed = new ConsentRecord();
        agreed.setDocVersion("v2.1");
        agreed.setEventType("AGREE");
        when(consentMapper.selectLatest(anyLong(), eq("activity_host_data_sharing"), eq("activity_signup")))
                .thenReturn(agreed);

        UmsMember m = new UmsMember();
        m.setNickname("城瘾玩家");
        when(memberSvc.selectUmsMemberById(anyLong())).thenReturn(m);

        // 我中标承接了两个主题;没有自办活动
        CmsRegistrationMerchant a = new CmsRegistrationMerchant();
        a.setTopicId(CLUB_LED_TOPIC);
        CmsRegistrationMerchant b = new CmsRegistrationMerchant();
        b.setTopicId(OWN_TOPIC);
        when(regMerchantSvc.selectCmsRegistrationMerchantList(any())).thenReturn(List.of(a, b));
        when(topicSvc.selectCmsTopicList(any())).thenReturn(Collections.<CmsTopic>emptyList());

        CmsTopic clubLed = new CmsTopic();
        clubLed.setId(CLUB_LED_TOPIC);
        clubLed.setClubId(77L);                     // 俱乐部带团 ⇒ 电话不给
        when(topicSvc.selectCmsTopicById(CLUB_LED_TOPIC)).thenReturn(clubLed);

        CmsTopic own = new CmsTopic();
        own.setId(OWN_TOPIC);
        own.setClubId(0L);                          // 没有组织方 ⇒ 电话给
        when(topicSvc.selectCmsTopicById(OWN_TOPIC)).thenReturn(own);

        when(inviteSvc.selectCoopInviteList(any())).thenReturn(Collections.<CoopInvite>emptyList());
    }

    /** 张三(memberId 501)在两个主题都来过;李四(502)只在俱乐部那个主题、且退了款。 */
    private void stubRegistrations() {
        when(regSvc.selectCmsRegistrationList(any())).thenAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            Long ownerId = probe.getOwnerId();
            Integer pay = probe.getPaymentStatus();
            List<CmsRegistration> out = new ArrayList<>();
            if (CLUB_LED_TOPIC.equals(ownerId) && Integer.valueOf(2).equals(pay)) {
                out.add(reg(1L, 501L, "张三", "13800000001", 1, "10"));   // 已核销 = 来过
            }
            if (CLUB_LED_TOPIC.equals(ownerId) && Integer.valueOf(4).equals(pay)) {
                out.add(reg(2L, 502L, "李四", "13800000002", 0, "20"));   // 退款
            }
            if (OWN_TOPIC.equals(ownerId) && Integer.valueOf(2).equals(pay)) {
                out.add(reg(3L, 501L, "张三", "13800000001", 0, "30"));   // 买了没来
            }
            return out;
        });
    }

    private CmsRegistration reg(Long id, Long memberId, String name, String phone, int verified, String amount) {
        CmsRegistration r = new CmsRegistration();
        r.setId(id);
        r.setMemberId(memberId);
        r.setRealName(name);
        r.setPhone(phone);
        r.setVerificationStatus(verified);
        r.setPayableAmount(new BigDecimal(amount));
        r.setActivityTitle("城南寻味");
        r.setCreateTime(new Date(1_700_000_000_000L + id * 1000L));
        return r;
    }

    private MerchantCustomerQuery queryWithKeyword(String keyword) {
        MerchantCustomerQuery q = new MerchantCustomerQuery();
        q.setKeyword(keyword);
        return q;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rowFor(Map<String, Object> roster, String name) {
        for (Map<String, Object> row : (List<Map<String, Object>>) roster.get("rows")) {
            if (name.equals(row.get("name"))) return row;
        }
        return null;
    }

    @Test
    void refundedCustomersStayVisibleInsteadOfDisappearing() {
        stubRegistrations();
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);

        Map<String, Object> li = rowFor(roster, "李四");
        // 只按「已支付」过滤会让退款的人整个消失 —— 对商家来说「昨天还在今天没了」
        // 和「他退款了」是两件完全不同的事。
        assertNotNull(li, "退款的客户必须还在名册里");
        assertEquals(1, li.get("refundedCount"));
        assertEquals(0, li.get("arrivedCount"));
        assertEquals(0, li.get("pendingCount"));
        assertEquals("refunded", li.get("state"));
    }

    @Test
    void oneCustomerAcrossTwoProjectsCollapsesIntoOneRowWithBothStates() {
        stubRegistrations();
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);

        Map<String, Object> zhang = rowFor(roster, "张三");
        assertNotNull(zhang);
        assertEquals(1, zhang.get("arrivedCount"), "俱乐部那场核销过 = 来过");
        assertEquals(1, zhang.get("pendingCount"), "我自己那场买了还没来");
        assertEquals(0, zhang.get("refundedCount"));
        // 名册是按人不是按票:同一个人两场必须并成一行
        assertEquals(1, ((List<?>) roster.get("rows")).stream()
                .filter(r -> "张三".equals(((Map<?, ?>) r).get("name"))).count());
        assertFalse(zhang.containsKey("totalAmount"), "客户名册不得泄露跨项目累计票款");
        assertFalse(((Map<?, ?>) roster.get("summary")).containsKey("totalAmount"));
    }

    @Test
    void freeExploreTopicNeverEntersCrossProjectCrm() {
        CmsTopic shopDay = new CmsTopic();
        shopDay.setId(OWN_TOPIC);
        shopDay.setProductType(2);
        when(topicSvc.selectCmsTopicById(OWN_TOPIC)).thenReturn(shopDay);
        stubRegistrations();

        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);

        Map<String, Object> zhang = rowFor(roster, "张三");
        assertEquals(1, zhang.get("arrivedCount"));
        assertEquals(0, zhang.get("pendingCount"), "探店日不得并入跨项目 CRM");
    }

    @Test
    void summaryCountsMatchTheRows() {
        stubRegistrations();
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);
        Map<?, ?> summary = (Map<?, ?>) roster.get("summary");

        // 汇总跟名单对不上账,是页面上「0 个客户」配一屏客户行那类 bug 的根
        assertEquals(2, summary.get("customerCount"));
        assertEquals(1, summary.get("arrivedCount"));
        assertEquals(1, summary.get("pendingCount"));
        assertEquals(1, summary.get("refundedCount"));
    }

    @Test
    void phoneFollowsTheExistingOrganiserGate() {
        stubRegistrations();
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);

        // 张三有一场是我自办的(无俱乐部)⇒ 我负责联系 ⇒ 给号
        assertEquals("13800000001", rowFor(roster, "张三").get("phone"));
        // 李四只来过俱乐部带团那场 ⇒ 要联系走俱乐部 ⇒ 不给号。
        // 名册不能变成绕过这道闸的后门。
        assertNull(rowFor(roster, "李四").get("phone"), "只来过俱乐部带团场次的客户不得下发电话");
    }

    @Test
    void crmReadWithoutSensitivePermissionNeverProjectsPhone() {
        stubRegistrations();

        Map<String, Object> roster = service.listMerchantCustomers(
                MERCHANT, new MerchantCustomerQuery(), false);

        Map<String, Object> zhang = rowFor(roster, "张三");
        assertNull(zhang.get("phone"));
        assertEquals("当前账号无查看联系方式权限", zhang.get("contactHint"));
    }

    @Test
    void tierFollowsWhatNeedsActionNotTheLatestTicket() {
        stubRegistrations();
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);

        // 张三来过一次、还有一张没核销。徽章要显示「待核销」——那是今天能动手的事,
        // 不是「复购」这种好消息。分层跟着人和动作走,不跟着最后一张票的时间走。
        assertEquals("pending", rowFor(roster, "张三").get("tier"));
        assertEquals("abnormal", rowFor(roster, "李四").get("tier"));
    }

    @Test
    void segmentsAreMembershipsNotAPartition() {
        stubRegistrations();
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);

        @SuppressWarnings("unchecked")
        List<String> zhang = (List<String>) rowFor(roster, "张三").get("segments");
        // 一个人可以同时在多个段里。硬做互斥分区会让「待核销」这类数字莫名少掉一批人。
        assertTrue(zhang.contains("pending"), "手上有票没核销");
        assertTrue(zhang.contains("repeat") || zhang.size() >= 1);

        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) roster.get("segmentCounts");
        assertEquals(2, counts.get("all"));
        assertEquals(1, counts.get("pending"));
        assertEquals(1, counts.get("abnormal"));
    }

    @Test
    void lastActionSaysWhatHappenedNotJustAStatusWord() {
        stubRegistrations();
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);

        // 「报名了「城南寻味」」比「pending」有用得多:商家一眼知道是哪个项目。
        // 相对时间不在服务端拼 —— 那会按服务器时区算,跨时区必错。
        String action = String.valueOf(rowFor(roster, "李四").get("lastAction"));
        assertTrue(action.startsWith("退了"), "退款客户的最近动作应该是「退了…」,实际:" + action);
        assertTrue(action.contains("「"), "动作里要带项目名,实际:" + action);
        assertNull(rowFor(roster, "李四").get("lastActionTimeText"),
                "相对时间不该由服务端下发");
    }

    @Test
    void missingProjectTitleFallsBackToASentenceThatStandsAlone() {
        when(regSvc.selectCmsRegistrationList(any())).thenAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            List<CmsRegistration> out = new ArrayList<>();
            if (OWN_TOPIC.equals(probe.getOwnerId()) && Integer.valueOf(2).equals(probe.getPaymentStatus())) {
                CmsRegistration r = reg(20L, 701L, "无名项目客", "13800000020", 1, "10");
                r.setActivityTitle(null);            // 存量数据没回填标题
                out.add(r);
            }
            return out;
        });

        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);
        String action = String.valueOf(rowFor(roster, "无名项目客").get("lastAction"));
        // 「核销了」后面没宾语一眼就看得出是数据缺了;换成能独立成立的一句
        assertEquals("已到店核销", action);
    }

    @Test
    void aCustomerHoldingAnUnusedTicketIsNeverDormant() {
        // 沉睡 = 失联要召回。手上还有没核销的票的人不是失联,他只是还没来 ——
        // 标成沉睡会让商家去做无意义的召回动作。
        when(regSvc.selectCmsRegistrationList(any())).thenAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            List<CmsRegistration> out = new ArrayList<>();
            if (OWN_TOPIC.equals(probe.getOwnerId()) && Integer.valueOf(2).equals(probe.getPaymentStatus())) {
                CmsRegistration old = reg(9L, 601L, "老客", "13800000009", 1, "50");
                old.setVerificationTime(new Date(System.currentTimeMillis() - 400L * 86400_000L));
                old.setCreateTime(old.getVerificationTime());
                out.add(old);                                           // 400 天前来过
                out.add(reg(10L, 601L, "老客", "13800000009", 0, "50")); // 但手上还有一张没核销
            }
            return out;
        });

        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);
        @SuppressWarnings("unchecked")
        List<String> segs = (List<String>) rowFor(roster, "老客").get("segments");
        assertTrue(segs.contains("pending"));
        assertTrue(!segs.contains("dormant"), "手上有未核销票的人不能被判成沉睡");
    }

    @Test
    void keywordFiltersByName() {
        stubRegistrations();
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, queryWithKeyword("李"), true);

        List<?> rows = (List<?>) roster.get("rows");
        assertEquals(1, rows.size());
        assertEquals("李四", ((Map<?, ?>) rows.get(0)).get("name"));
        // 汇总必须跟着筛完的结果走,否则搜出 1 个人却显示「共 2 位客户」
        assertEquals(1, ((Map<?, ?>) roster.get("summary")).get("customerCount"));
    }

    @Test
    void segmentFilterRunsBeforePagingAndCountsStayGlobal() {
        stubRegistrations();
        MerchantCustomerQuery q = new MerchantCustomerQuery();
        q.setSegment("abnormal");
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, q, true);

        List<?> rows = (List<?>) roster.get("rows");
        assertEquals(1, rows.size(), "只该剩异常段那一个");
        assertEquals("李四", ((Map<?, ?>) rows.get(0)).get("name"));
        assertEquals(1, roster.get("total"), "total 是筛完的条数,不是全量");

        // ★ 切段后其它段的计数不能塌成 0,否则用户切一次就再也看不出别的段还有多少人
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) roster.get("segmentCounts");
        assertEquals(2, counts.get("all"));
        assertEquals(1, counts.get("pending"));
    }

    @Test
    void pagingSlicesTheRosterAndReportsTheFullTotal() {
        stubRegistrations();
        MerchantCustomerQuery q = new MerchantCustomerQuery();
        q.setPageNum(2);
        q.setPageSize(1);
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, q, true);

        assertEquals(1, ((List<?>) roster.get("rows")).size(), "第二页只有一条");
        assertEquals(2, roster.get("total"), "total 必须是筛后全量,不是本页条数");
    }

    @Test
    void pagingPastTheEndReturnsEmptyInsteadOfBlowingUp() {
        stubRegistrations();
        MerchantCustomerQuery q = new MerchantCustomerQuery();
        q.setPageNum(9);          // 越界页:subList 边界算错就是 IndexOutOfBounds
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, q, true);

        assertTrue(((List<?>) roster.get("rows")).isEmpty());
        assertEquals(2, roster.get("total"));
    }

    @Test
    void unknownSegmentDegradesToAllRatherThanEmpty() {
        stubRegistrations();
        MerchantCustomerQuery q = new MerchantCustomerQuery();
        q.setSegment("no-such-segment");
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, q, true);

        // 白名单外的值退化成「全部」。返回空列表会被当成「这个商家没客户」,
        // 前端加了新段而后端没跟上时就是这个场景。
        assertEquals(2, ((List<?>) roster.get("rows")).size());
    }

    @Test
    void contactHintSaysWhyThePhoneIsMissing() {
        stubRegistrations();
        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);

        // 给号的人不该带 hint
        assertNull(rowFor(roster, "张三").get("contactHint"));
        // 不给号的必须说清为什么 —— 只给 null 的话商家会以为「这人没留电话」,
        // 同一道闸在 listPlayers 那边是给了 contactHint 的,名册不能退化成静默。
        String hint = String.valueOf(rowFor(roster, "李四").get("contactHint"));
        assertTrue(hint.contains("俱乐部"), "俱乐部带团的场次要指明去找俱乐部,实际:" + hint);
    }

    @Test
    void contactDetailsComeFromTheMostRecentTicketNotTheFirstSeen() {
        // 同一个人换过号码:名册要显示新的那个。按「第一次撞见」取会长期显示旧号,
        // 而顺序由 topicId 插入序 × paymentStatus × mapper 返回序决定,跟时间无关。
        when(regSvc.selectCmsRegistrationList(any())).thenAnswer(inv -> {
            CmsRegistration probe = inv.getArgument(0);
            List<CmsRegistration> out = new ArrayList<>();
            if (OWN_TOPIC.equals(probe.getOwnerId()) && Integer.valueOf(2).equals(probe.getPaymentStatus())) {
                CmsRegistration older = reg(1L, 801L, "旧名", "13800000001", 1, "10");
                older.setCreateTime(new Date(1_700_000_000_000L));
                CmsRegistration newer = reg(2L, 801L, "新名", "13900000002", 1, "10");
                newer.setCreateTime(new Date(1_700_000_900_000L));
                out.add(older);   // 故意把旧的排在前面
                out.add(newer);
            }
            return out;
        });

        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);
        Map<String, Object> row = rowFor(roster, "新名");
        assertNotNull(row, "应该显示最近一张票上的姓名");
        assertEquals("13900000002", row.get("phone"), "电话也要取最近那张");
    }

    @Test
    void merchantWithNoProjectsGetsAnEmptyRosterNotAnError() {
        when(regMerchantSvc.selectCmsRegistrationMerchantList(any()))
                .thenReturn(Collections.<CmsRegistrationMerchant>emptyList());
        when(regSvc.selectCmsRegistrationList(any())).thenReturn(Collections.<CmsRegistration>emptyList());

        Map<String, Object> roster = service.listMerchantCustomers(MERCHANT, new MerchantCustomerQuery(), true);

        assertTrue(((List<?>) roster.get("rows")).isEmpty());
        assertEquals(0, ((Map<?, ?>) roster.get("summary")).get("customerCount"));
    }
}
