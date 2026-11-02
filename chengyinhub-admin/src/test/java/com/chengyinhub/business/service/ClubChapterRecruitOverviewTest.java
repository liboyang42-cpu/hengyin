package com.chengyinhub.business.service;

import com.chengyinhub.business.access.club.ClubAccessContext;
import com.chengyinhub.business.access.club.ClubRoleCode;
import com.chengyinhub.business.access.club.IClubAccessService;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.service.impl.ClubChapterRecruitServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 2026-11-02 承接商家半屏(ROUND3B):总览带章节招募条件、申请数与玩法名,电话仍只给已确认商家与 ACTIVITY_MANAGE。 */
class ClubChapterRecruitOverviewTest {
    private static final Long CLUB = 7L, TOPIC = 70L, OWNER = 1L, MEMBER = 2L;
    private ClubChapterRecruitServiceImpl service;
    private IClubAccessService access;
    private ICmsTopicService topics;

    @BeforeEach
    void setUp() {
        service = new ClubChapterRecruitServiceImpl();
        access = mock(IClubAccessService.class);
        topics = mock(ICmsTopicService.class);
        ICmsTopicNodeService nodes = mock(ICmsTopicNodeService.class);
        IMmsMerchantService merchants = mock(IMmsMerchantService.class);
        ICmsTopicChapterService chapters = mock(ICmsTopicChapterService.class);
        ICmsMemberTemplateService templates = mock(ICmsMemberTemplateService.class);
        MerchantChapterApplicationMapper apps = mock(MerchantChapterApplicationMapper.class);
        ReflectionTestUtils.setField(service, "clubAccessService", access);
        ReflectionTestUtils.setField(service, "cmsTopicService", topics);
        ReflectionTestUtils.setField(service, "cmsTopicNodeService", nodes);
        ReflectionTestUtils.setField(service, "mmsMerchantService", merchants);
        ReflectionTestUtils.setField(service, "cmsTopicChapterService", chapters);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateService", templates);
        ReflectionTestUtils.setField(service, "chapterApplicationMapper", apps);

        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC); topic.setClubId(CLUB); topic.setName("武康路"); topic.setProductType(2);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        when(access.resolve(eq(OWNER), eq(CLUB), any())).thenReturn(new ClubAccessContext(OWNER, CLUB, OWNER, null,
                Collections.singleton(ClubRoleCode.CLUB_OWNER)));
        when(access.resolve(eq(MEMBER), eq(CLUB), any())).thenReturn(new ClubAccessContext(MEMBER, CLUB, OWNER, null,
                Collections.singleton(ClubRoleCode.CLUB_MEMBER)));

        CmsTopicNode won = node(11L, 101L, "旧钟楼", 1, 500L, 1L);
        CmsTopicNode open = node(12L, 101L, "未命名站点", 2, null, null);
        when(nodes.selectCmsTopicNodeList(any())).thenReturn(Arrays.asList(won, open));
        MmsMerchant m = new MmsMerchant(); m.setName("慢咖啡"); m.setPhone("13800000001");
        when(merchants.selectMmsMerchantByMemberId(500L)).thenReturn(m);
        CmsMemberTemplate t = new CmsMemberTemplate(); t.setId(1L); t.setTitle("门楣上的年份");
        when(templates.selectCmsMemberTemplateByIds(anyList())).thenReturn(Collections.singletonList(t));

        CmsTopicChapter c1 = chapter(101L, "第一章", 1L, "咖啡 / 轻食", 1);
        c1.setPerkMinValue(new BigDecimal("20")); c1.setMaxMerchant(3); c1.setTermsMode("PERK");
        CmsTopicChapter c2 = chapter(102L, "第二章", 2L, "书店", 0);
        CmsTopicChapter foreign = chapter(999L, "别的主题", 3L, "x", 1); foreign.setTopicId(71L);
        when(chapters.selectCmsTopicChapterList(any())).thenReturn(Arrays.asList(c2, c1, foreign));
        when(apps.selectByChapterIdsAndStatus(anyList(), eq(0))).thenReturn(Arrays.asList(app(101L), app(101L)));
        when(apps.selectByChapterIdsAndStatus(anyList(), eq(1))).thenReturn(Arrays.asList(app(101L), app(101L)));
    }

    private static CmsTopicNode node(Long id, Long chapterId, String name, long sort, Long merchant, Long template) {
        CmsTopicNode n = new CmsTopicNode();
        n.setId(id); n.setChapterId(chapterId); n.setName(name); n.setSortId(sort);
        n.setMerchantMemberId(merchant); n.setTemplateId(template); n.setNodeAuditStatus(merchant == null ? null : 1);
        return n;
    }
    private static CmsTopicChapter chapter(Long id, String name, Long sort, String category, int recruit) {
        CmsTopicChapter c = new CmsTopicChapter();
        c.setId(id); c.setTopicId(TOPIC); c.setName(name); c.setSortId(sort); c.setCategory(category); c.setRecruitEnabled(recruit);
        return c;
    }
    private static MerchantChapterApplication app(Long chapterId) {
        MerchantChapterApplication a = new MerchantChapterApplication(); a.setChapterId(chapterId); return a;
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewCarriesChaptersCountsAndGameplayNames() {
        Map<String, Object> out = service.overview(OWNER, CLUB, TOPIC);
        assertEquals(2, out.get("productType"));
        List<Map<String, Object>> chapters = (List<Map<String, Object>>) out.get("chapters");
        assertEquals(2, chapters.size(), "别的主题的章节不进来");
        assertEquals(101L, chapters.get(0).get("chapterId"), "按 sortId 排");
        assertEquals(true, chapters.get(0).get("recruiting"));
        assertEquals(2, chapters.get(0).get("pendingCount"));
        assertEquals(2, chapters.get(0).get("approvedCount"));
        assertEquals(3, chapters.get(0).get("maxMerchant"));
        assertEquals(false, chapters.get(1).get("recruiting"));
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) out.get("nodes");
        assertEquals("门楣上的年份", nodes.get(0).get("gameplayName"));
        assertEquals("13800000001", nodes.get(0).get("phone"));
        assertFalse(nodes.get(1).containsKey("gameplayName"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void memberWithoutManageGetsNoMerchantPhone() {
        Map<String, Object> out = service.overview(MEMBER, CLUB, TOPIC);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) out.get("nodes");
        assertFalse(nodes.get(0).containsKey("phone"));
    }

    @Test
    void otherClubsThemeIsRefused() {
        assertThrows(ServiceException.class, () -> service.overview(OWNER, 8L, TOPIC));
    }
}
