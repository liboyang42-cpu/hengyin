package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.CmsTopicTemplateMeta;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import com.chengyinhub.business.domain.vo.TopicTemplateChapterVO;
import com.chengyinhub.business.domain.vo.TopicTemplateDetailVO;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.service.impl.TopicQueryService;
import com.chengyinhub.business.util.EvidenceLevel;
import com.chengyinhub.business.util.TemplateStatus;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 07-23 P0 字段字典元数据装配。
 *
 * <p>钉死四件事:
 * ① 附表没行时所有元数据字段留空 —— 「未录入」不能被渲染成「已验证」;
 * ② mode 的真源是 product_type,不是元数据表,且恒非空;
 * ③ <b>运行投入 effort 与 recruitStatus 同闸</b> —— 匿名玩家看不到商家成本口径;
 * ④ <b>排他规则与 viewerCanViewRecruitment 同闸</b> —— 匿名为空,合作方/发布者可见。
 * <p>负控已实测:精准删除 service 中的 {@code viewerCanViewRecruitment &&} 后,
 * 匿名排他规则的 {@code assertNull} 变红;恢复闸后本组测试全绿。
 */
class TopicTemplateMetaBuildTest {

    private TopicQueryService service;
    private CmsTopicMapper cmsTopicMapper;
    private CmsTopicChapterMapper cmsTopicChapterMapper;
    private CmsTopicNodeMapper cmsTopicNodeMapper;
    private CmsTopicTemplateMetaMapper metaMapper;
    private IMerchantChapterApplicationService merchantChapterApplicationService;
    private ISysCategoryService categoryService;

    private static final Long TOPIC = 42L;
    private static final long CHAPTER = 7L;

    @BeforeEach
    void setUp() {
        service = new TopicQueryService();
        cmsTopicMapper = mock(CmsTopicMapper.class);
        cmsTopicChapterMapper = mock(CmsTopicChapterMapper.class);
        cmsTopicNodeMapper = mock(CmsTopicNodeMapper.class);
        metaMapper = mock(CmsTopicTemplateMetaMapper.class);
        merchantChapterApplicationService = mock(IMerchantChapterApplicationService.class);
        categoryService = mock(ISysCategoryService.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", cmsTopicChapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", cmsTopicNodeMapper);
        ReflectionTestUtils.setField(service, "cmsMemberTemplateMapper", mock(CmsMemberTemplateMapper.class));
        ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper", metaMapper);
        ReflectionTestUtils.setField(service, "merchantChapterApplicationService", merchantChapterApplicationService);
        ReflectionTestUtils.setField(service, "sysCategoryService", categoryService);

        when(cmsTopicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic());
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any())).thenReturn(Arrays.asList(chapter()));
        when(cmsTopicNodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
        when(categoryService.selectSysCategoryById(18L)).thenReturn(activeCategory());
        when(merchantChapterApplicationService.getRecruitmentStatsByChapters(org.mockito.ArgumentMatchers.anyMap()))
                .thenAnswer(invocation -> {
                    java.util.Map<Long, Integer> requested = invocation.getArgument(0);
                    java.util.Map<Long, MerchantChapterRecruitmentStats> result = new java.util.LinkedHashMap<>();
                    for (Long chapterId : requested.keySet()) result.put(chapterId, stats());
                    return result;
                });
    }

    /** 附表无行 = 该模板尚未录入元数据,是合法状态,不能被渲染成任何确定结论。 */
    @Test
    void missingMeta_leavesEveryDictionaryFieldEmpty() {
        when(metaMapper.selectByTopicId(TOPIC)).thenReturn(null);
        when(metaMapper.selectChapterMetaByTopicId(TOPIC)).thenReturn(Collections.emptyList());

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true, null);

        assertNull(vo.getTemplateStatus(), "未录入不能冒充任何状态,尤其不能是 VERIFIED");
        assertNull(vo.getEvidenceLevel(), "未录入的证据等级必须留空,不能默认成有实测");
        assertNull(vo.getIsOfficial(), "未录入的官方标识必须留空,不能默认成官方");
        assertNull(vo.getTargetAudience());
        assertNull(vo.getPlayerPromise());
        assertNull(vo.getCoreLoop());
        assertNull(vo.getRisk());
        assertNull(vo.getPlayerPreview());
        assertNull(vo.getApplicableRoles());
        assertNull(vo.getChapters().get(0).getRoleNeed());
        assertNull(vo.getChapters().get(0).getExclusivity());
        assertNull(vo.getChapters().get(0).getEffort());

        // mode 的真源是 product_type,与元数据表无关 ⇒ 即使没有元数据也必须有值。
        assertEquals("FREE_EXPLORE", vo.getMode(), "mode 不该依赖元数据表,它的真源是 product_type");
    }

    @Test
    void presentMeta_mapsDictionaryFieldsAndSplitsMultiValues() {
        when(metaMapper.selectByTopicId(TOPIC)).thenReturn(fullMeta());
        when(metaMapper.selectChapterMetaByTopicId(TOPIC)).thenReturn(Arrays.asList(fullChapterMeta()));

        TopicTemplateDetailVO vo = service.buildTopicTemplateDetail(TOPIC, true, null);

        assertEquals(TemplateStatus.VERIFIED, vo.getTemplateStatus());
        assertEquals("v1.2", vo.getVersion());
        assertEquals(Integer.valueOf(1), vo.getIsOfficial(), "官方标识必须从元数据透传到详情");
        assertEquals(EvidenceLevel.PILOTED, vo.getEvidenceLevel());
        assertEquals(Arrays.asList("MERCHANT", "CLUB"), vo.getApplicableRoles());
        assertEquals("周末下午想遛弯又不想排队的年轻情侣", vo.getTargetAudience().getWho());
        assertEquals("周六 14:00-17:00 商圈闲逛时", vo.getTargetAudience().getMoment());
        assertEquals("想有个由头把街逛出内容", vo.getTargetAudience().getNeed());

        // 六段循环:顺序即阶段顺序,不得排序、不得丢段。
        assertEquals(6, vo.getCoreLoop().size(), "核心循环必须六段齐全");
        assertEquals("接受任务", vo.getCoreLoop().get(0), "第一段必须还在第一位,顺序即阶段顺序");
        assertEquals("邀请同伴再来", vo.getCoreLoop().get(5));

        assertEquals(Arrays.asList("含酒精", "户外行走"), vo.getRisk().getTags());
        assertEquals("雨天顺延,不在雷雨天开场", vo.getRisk().getSafetyNotice());
        assertEquals("看到入口和第一条线索", vo.getPlayerPreview().getBefore());
        assertEquals(Long.valueOf(900L), vo.getMainSkeleton());
        assertEquals("阿岚", vo.getAuthor());

        TopicTemplateChapterVO ch = vo.getChapters().get(0);
        assertEquals("提供一杯特调并核销", ch.getRoleNeed());
        assertEquals("HARD", ch.getExclusivity().getMode());
        assertEquals("方圆500米", ch.getExclusivity().getScope());
    }

    /**
     * <b>越权闸</b>:运行投入的可见范围是「合作方」。recruitStatus 为 null 意味着当前访问者
     * 没通过商家/发布者校验,此时下发商家成本口径就是越权。
     *
     * <p>这条对应仓库的历史教训:模板相关接口曾整实体下发,把只该给一方看的内容发给了所有人。
     */
    @Test
    void effort_isGatedTogetherWithRecruitStatus_notVisibleToAnonymousPlayer() {
        when(metaMapper.selectByTopicId(TOPIC)).thenReturn(fullMeta());
        when(metaMapper.selectChapterMetaByTopicId(TOPIC)).thenReturn(Arrays.asList(fullChapterMeta()));

        // 匿名玩家:viewerCanViewRecruitment=false ⇒ recruitStatus 不下发
        TopicTemplateDetailVO anonymous = service.buildTopicTemplateDetail(TOPIC, false, null);
        TopicTemplateChapterVO anonChapter = anonymous.getChapters().get(0);
        assertNull(anonChapter.getRecruitStatus(), "前提:匿名视角本就拿不到招商段");
        assertNull(anonChapter.getEffort(),
                "运行投入是商家成本口径,必须与 recruitStatus 同闸,不得下发给匿名玩家");

        // 角色需求仍随故事面下发；排他规则属于合作方口径，匿名不可见。
        assertEquals("提供一杯特调并核销", anonChapter.getRoleNeed());
        assertNull(anonChapter.getExclusivity(), "匿名视角不能看到合作方排他规则");

        // 商家视角:两者都在
        TopicTemplateDetailVO merchant = service.buildTopicTemplateDetail(TOPIC, true, null);
        TopicTemplateChapterVO merchantChapter = merchant.getChapters().get(0);
        assertNotNull(merchantChapter.getRecruitStatus(), "前提:商家视角能拿到招商段");
        assertNotNull(merchantChapter.getExclusivity(), "合作方视角应当能看到排他规则");
        assertNotNull(merchantChapter.getEffort(), "商家视角应当能看到运行投入");
        assertEquals(new BigDecimal("2.50"), merchantChapter.getEffort().getPrepHours());
        assertEquals(Boolean.TRUE, merchantChapter.getEffort().getNeedsBooking());
    }

    /**
     * 服务层只接收 controller 已判定的 viewerCanViewRecruitment; true 同时覆盖商家与发布者。
     * 发布者即使遇到未开放招商的章节,也必须能看到排他规则,不能偷用 recruitStatus 作为闸。
     */
    @Test
    void exclusivity_usesViewerCanViewRecruitment_forPublisherWithoutRecruitStatus() {
        when(metaMapper.selectByTopicId(TOPIC)).thenReturn(fullMeta());
        when(metaMapper.selectChapterMetaByTopicId(TOPIC)).thenReturn(Arrays.asList(fullChapterMeta()));
        CmsTopicChapter chapterWithoutRecruitment = chapter();
        chapterWithoutRecruitment.setRecruitEnabled(0);
        when(cmsTopicChapterMapper.selectCmsTopicChapterList(any()))
                .thenReturn(Arrays.asList(chapterWithoutRecruitment));

        TopicTemplateDetailVO anonymous = service.buildTopicTemplateDetail(TOPIC, false, null);
        assertNull(anonymous.getChapters().get(0).getExclusivity(), "匿名视角必须隐藏排他规则");

        TopicTemplateDetailVO publisher = service.buildTopicTemplateDetail(TOPIC, true, null);
        TopicTemplateChapterVO publisherChapter = publisher.getChapters().get(0);
        assertNull(publisherChapter.getRecruitStatus(), "未开放招商时发布者也可能没有 recruitStatus");
        assertNotNull(publisherChapter.getExclusivity(),
                "发布者 viewerCanViewRecruitment=true,排他规则不能被 recruitStatus 连带隐藏");
    }

    /** 玩法白名单不因新增元数据而变宽 —— 谜底没有任何一条路径能流出去。 */
    @Test
    void metaAssembly_doesNotWidenGameWhitelist() {
        when(metaMapper.selectByTopicId(TOPIC)).thenReturn(fullMeta());
        when(metaMapper.selectChapterMetaByTopicId(TOPIC)).thenReturn(Collections.emptyList());

        service.buildTopicTemplateDetail(TOPIC, true, null);

        // mainSkeleton 只是一个 id;装配过程绝不因它去查模板实体另开出口。
        for (java.lang.reflect.Method m
                : com.chengyinhub.business.domain.vo.TopicTemplateGameVO.class.getMethods()) {
            String n = m.getName().toLowerCase();
            assertTrue(!n.contains("answer") && !n.contains("hint") && !n.contains("storyjson"),
                    "玩法 VO 出现了疑似泄题的访问器: " + m.getName());
        }
    }

    // ====== fixtures ======

    private CmsTopic topic() {
        CmsTopic t = new CmsTopic();
        t.setId(TOPIC);
        t.setName("赛博夜巡");
        t.setProductType(ProductType.FREE_EXPLORE);
        t.setStatus(1);
        t.setUserStatus(1);
        return t;
    }

    private CmsTopicChapter chapter() {
        CmsTopicChapter ch = new CmsTopicChapter();
        ch.setId(CHAPTER);
        ch.setName("第一章");
        ch.setTopicId(TOPIC);
        ch.setRecruitEnabled(1);
        ch.setMaxMerchant(3);
        ch.setTermsMode("PERK");
        ch.setPerkMinValue(new BigDecimal("30"));
        ch.setCategoryId(18);
        ch.setRequired(1);
        return ch;
    }

    private SysCategory activeCategory() {
        SysCategory c = new SysCategory();
        c.setId(18L);
        c.setType(1L);
        c.setParentId(0L);
        c.setStatus(1);
        c.setDelFlag(0);
        return c;
    }

    private MerchantChapterRecruitmentStats stats() {
        MerchantChapterRecruitmentStats s = new MerchantChapterRecruitmentStats();
        s.setReservedMerchantCount(1);
        s.setPendingApplicationCount(0);
        s.setApprovedApplicationCount(1);
        s.setActiveOfferCount(1);
        s.setRemainingMerchantCount(2);
        return s;
    }

    private CmsTopicTemplateMeta fullMeta() {
        CmsTopicTemplateMeta m = new CmsTopicTemplateMeta();
        m.setTopicId(TOPIC);
        m.setTemplateStatus(TemplateStatus.VERIFIED);
        m.setVersion("v1.2");
        m.setApplicableRoles("MERCHANT,CLUB");
        m.setAudienceWho("周末下午想遛弯又不想排队的年轻情侣");
        m.setAudienceMoment("周六 14:00-17:00 商圈闲逛时");
        m.setAudienceNeed("想有个由头把街逛出内容");
        m.setPlayerPromise("两小时走完六个点,每个点留下一张属于你们的照片和一句话,结束时拿到一本可以带走的小册子。");
        m.setCoreLoop("接受任务\n到达地点\n完成互动\n得到反馈\n拿到结果\n邀请同伴再来");
        m.setMainSkeletonTemplateId(900L);
        m.setEvidenceLevel(EvidenceLevel.PILOTED);
        m.setRiskTags("含酒精,户外行走");
        m.setRiskSafetyNotice("雨天顺延,不在雷雨天开场");
        m.setPreviewBefore("看到入口和第一条线索");
        m.setPreviewDuring("地图上逐个点亮");
        m.setPreviewAfter("拿到小册子和勋章");
        m.setAuthor("阿岚");
        m.setIsOfficial(1);
        return m;
    }

    private CmsTopicChapterTemplateMeta fullChapterMeta() {
        CmsTopicChapterTemplateMeta cm = new CmsTopicChapterTemplateMeta();
        cm.setChapterId(CHAPTER);
        cm.setTopicId(TOPIC);
        cm.setRoleNeed("提供一杯特调并核销");
        cm.setExclusivityMode("HARD");
        cm.setExclusivityScope("方圆500米");
        cm.setExclusivityPeriod("活动期间");
        cm.setExclusivityReason("同品类相邻会互相分流");
        cm.setEffortPrepHours(new BigDecimal("2.50"));
        cm.setEffortStaffMinutes(15);
        cm.setEffortVenue("需有 2 人以上座位区");
        cm.setEffortMaxConcurrent(6);
        cm.setEffortNeedsBooking(1);
        return cm;
    }
}
