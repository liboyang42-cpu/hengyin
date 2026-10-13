package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.vo.ClubCustomerVO;
import com.chengyinhub.business.service.impl.ProjectPlayerReadServiceImpl;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CU-C-67:俱乐部主理人看自己主题的客户名单。
 *
 * <p>探店日(FREE_EXPLORE)主题原来一律转商家门店名册,俱乐部身份必然被
 * 「本店 live offer + 商家主体」那道闸挡下 —— 走查实测就是业务 500
 * 「无权查看该探店日门店名单」。修法是同源改走俱乐部 CRM 的主题客户读数,
 * 并整形回本接口对外的 roster 契约。
 *
 * <p>这个测试守两件事:①俱乐部主题 + 主题创建者必须走俱乐部分支、状态码按俱乐部那五档映射;
 * ②别的主语(不挂俱乐部 / 不是创建者)一步都不许借用俱乐部分支 —— 放行范围扩一寸,
 * 俱乐部口径的名单就会漏给不该看的人。
 */
class ProjectPlayerClubRosterTest {

    private static final Long TOPIC = 900030L;
    private static final Long CLUB = 7002L;
    private static final Long CLUB_OWNER = 9008L;
    private static final Long OTHER_MEMBER = 9002L;

    private ICmsTopicService topicService;
    private IClubCrmAppService clubCrmAppService;
    private IShopDayMerchantRosterService shopDayRosterService;
    private ProjectPlayerReadServiceImpl service;

    @BeforeEach
    void setUp() {
        topicService = mock(ICmsTopicService.class);
        clubCrmAppService = mock(IClubCrmAppService.class);
        shopDayRosterService = mock(IShopDayMerchantRosterService.class);
        service = new ProjectPlayerReadServiceImpl(topicService,
                mock(ICmsRegistrationService.class),
                mock(ICmsRegistrationMerchantService.class),
                mock(ICoopInviteService.class),
                mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(service, "clubCrmAppService", clubCrmAppService);
        ReflectionTestUtils.setField(service, "shopDayMerchantRosterService", shopDayRosterService);
    }

    private CmsTopic exploreTopic(Long ownerMemberId, Long clubId) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setMemberId(ownerMemberId);
        topic.setClubId(clubId);
        return topic;
    }

    private ClubCustomerVO.TopicRow clubRow(String key, String name, String statusCode) {
        ClubCustomerVO.TopicRow row = new ClubCustomerVO.TopicRow();
        row.setKey(key);
        row.setDisplayName(name);
        row.setStatusCode(statusCode);
        row.setPhoneText("138****5621");     // 服务端已脱敏:只许当文字看,不许当号码用
        return row;
    }

    private ClubCustomerVO.TopicPage clubPage() {
        ClubCustomerVO.Session session = new ClubCustomerVO.Session();
        session.setKey("s-1");
        session.setTimeText("2026-09-24 14:00");
        session.setRows(Arrays.asList(
                clubRow("reg-11", "张三", "VERIFIED"),
                clubRow("reg-12", "李四", "CONTACTED"),
                clubRow("reg-13", "王五", "REFUNDED")));

        ClubCustomerVO.TopicPage page = new ClubCustomerVO.TopicPage();
        page.setSoldCount(2);
        page.setPendingCount(0);
        page.setContactedCount(1);
        page.setVerifiedCount(1);
        page.setSessions(Collections.singletonList(session));
        return page;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rowsOf(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("rows");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> summaryOf(Map<String, Object> out) {
        return (Map<String, Object>) out.get("summary");
    }

    @Test
    void 俱乐部主理人看自己主题的名单走俱乐部读数() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(exploreTopic(CLUB_OWNER, CLUB));
        when(clubCrmAppService.topicCustomers(CLUB_OWNER, CLUB, TOPIC, null)).thenReturn(clubPage());

        Map<String, Object> out = service.listPlayers(CLUB_OWNER, TOPIC, true);

        assertEquals("CLUB_TOPIC_ROSTER", out.get("viewType"));
        List<Map<String, Object>> rows = rowsOf(out);
        assertEquals(3, rows.size());
        assertEquals("arrived", rows.get(0).get("state"), "已核销映射成 arrived(抽屉的已核销那一档)");
        assertEquals(Boolean.TRUE, rows.get(0).get("arrived"));
        assertEquals("contacted", rows.get(1).get("state"), "已接洽是独立一档,不能并进待核销");
        assertEquals("refunded", rows.get(2).get("state"));
        assertEquals("2026-09-24 14:00", rows.get(0).get("sessionLabel"), "场次用来分组");
        assertEquals(11L, rows.get(0).get("registrationId"));
        assertNull(rows.get(0).get("phone"), "脱敏串不许进会带「复制」钮的字段");
        assertNotNull(out.get("contactHint"));

        Map<String, Object> summary = summaryOf(out);
        assertEquals(2, summary.get("paidCount"));
        assertEquals(1, summary.get("contactedCount"));
        assertEquals(1, summary.get("arrivedCount"));
        verify(shopDayRosterService, never()).list(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void 不挂俱乐部的探店日主题仍走商家门店名册() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(exploreTopic(OTHER_MEMBER, null));
        when(shopDayRosterService.list(OTHER_MEMBER, TOPIC, true))
                .thenReturn(Collections.<String, Object>singletonMap("rows", Collections.emptyList()));

        Map<String, Object> out = service.listPlayers(OTHER_MEMBER, TOPIC, true);

        assertNotNull(out.get("rows"), "没有 club_id 就还是商家口径,一步都不许变");
        verify(clubCrmAppService, never()).topicCustomers(anyLong(), anyLong(), anyLong(), anyString());
    }

    @Test
    void 不是主题创建者的调用人不能借用俱乐部分支() {
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(exploreTopic(CLUB_OWNER, CLUB));
        when(shopDayRosterService.list(OTHER_MEMBER, TOPIC, true))
                .thenReturn(Collections.<String, Object>singletonMap("rows", Collections.emptyList()));

        service.listPlayers(OTHER_MEMBER, TOPIC, true);

        verify(clubCrmAppService, never())
                .topicCustomers(anyLong(), anyLong(), anyLong(), anyString());
        assertTrue(true);
    }

    @Test
    void 缺俱乐部读数服务时不能把主理人挡在门外报错说名单不存在() {
        // 选填注入(required=false)的兜底:服务没就绪就退回原来的商家口径,由它自己报错
        ReflectionTestUtils.setField(service, "clubCrmAppService", null);
        when(topicService.selectCmsTopicById(TOPIC)).thenReturn(exploreTopic(CLUB_OWNER, CLUB));
        when(shopDayRosterService.list(CLUB_OWNER, TOPIC, true))
                .thenReturn(Collections.<String, Object>singletonMap("rows", Collections.emptyList()));

        service.listPlayers(CLUB_OWNER, TOPIC, true);

        verify(clubCrmAppService, never()).topicCustomers(anyLong(), anyLong(), anyLong(), isNull());
    }
}
