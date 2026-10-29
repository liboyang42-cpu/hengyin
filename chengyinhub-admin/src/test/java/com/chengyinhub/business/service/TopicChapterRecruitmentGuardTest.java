package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.domain.dto.ChapterSupplyTermsDTO;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.service.impl.CmsTopicChapterServiceImpl;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台通用 CRUD 也能直接写 cms_topic_chapter，不能只在小程序发布链守招商配置。
 */
class TopicChapterRecruitmentGuardTest {

    private static final Long TOPIC = 42L;
    private static final Long CHAPTER = 7L;

    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicMapper topicMapper;
    private MerchantChapterApplicationMapper applicationMapper;
    private MerchantChapterOfferMapper offerMapper;
    private ISysCategoryService categoryService;
    private ITopicOperationsScopeAuthorizer scopeAuthorizer;
    private CommercialTopicPolicy commercialTopicPolicy;
    private IProductRoleService productRoleService;
    private CmsTopicChapterServiceImpl service;

    @BeforeEach
    void setUp() {
        chapterMapper = mock(CmsTopicChapterMapper.class);
        topicMapper = mock(CmsTopicMapper.class);
        applicationMapper = mock(MerchantChapterApplicationMapper.class);
        offerMapper = mock(MerchantChapterOfferMapper.class);
        categoryService = mock(ISysCategoryService.class);
        scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        commercialTopicPolicy = mock(CommercialTopicPolicy.class);
        productRoleService = mock(IProductRoleService.class);
        service = new CmsTopicChapterServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        com.chengyinhub.business.service.support.TopicContentBudgetTestWiring.chapter(service);
        ReflectionTestUtils.setField(service, "merchantChapterApplicationMapper", applicationMapper);
        ReflectionTestUtils.setField(service, "merchantChapterOfferMapper", offerMapper);
        ReflectionTestUtils.setField(service, "sysCategoryService", categoryService);
        ReflectionTestUtils.setField(service, "topicOperationsScopeAuthorizer", scopeAuthorizer);
        ReflectionTestUtils.setField(service, "commercialTopicPolicy", commercialTopicPolicy);
        ReflectionTestUtils.setField(service, "productRoleService", productRoleService);
        when(chapterMapper.lockChaptersForCapacity(Collections.singletonList(CHAPTER)))
                .thenReturn(Collections.singletonList(CHAPTER));
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(recruitmentChapter());
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic(ProductType.FREE_EXPLORE));
        when(topicMapper.selectCmsTopicById(TOPIC)).thenReturn(topic(ProductType.FREE_EXPLORE));
        when(scopeAuthorizer.canViewTopic(TOPIC)).thenReturn(true);
    }

    private CmsTopic topic(int productType) {
        CmsTopic topic = new CmsTopic();
        topic.setId(TOPIC);
        topic.setProductType(productType);
        topic.setLifecycle(TopicLifecycleState.DRAFT.getCode());
        topic.setDelFlag(0);
        return topic;
    }

    private CmsTopicChapter recruitmentChapter() {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER);
        chapter.setTopicId(TOPIC);
        chapter.setRecruitEnabled(1);
        chapter.setCategoryId(18);
        chapter.setMaxMerchant(0);
        return chapter;
    }

    @Test
    void insert_rejectsRecruitmentForNonFreeExploreTopic() {
        CmsTopicChapter chapter = recruitmentChapter();
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic(ProductType.ORIENTEERING));

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter));

        assertTrue(ex.getMessage().contains("仅自由探索主题"));
        verify(chapterMapper, never()).insertCmsTopicChapter(chapter);
    }

    @Test
    void insert_rejectsRecruitmentForDeletedTopic() {
        CmsTopicChapter chapter = recruitmentChapter();
        CmsTopic deleted = topic(ProductType.FREE_EXPLORE);
        deleted.setDelFlag(2);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(deleted);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter));

        assertTrue(ex.getMessage().contains("已删除") || ex.getMessage().contains("生命周期未知"));
        verify(chapterMapper, never()).insertCmsTopicChapter(chapter);
    }

    @Test
    void insert_rejectsUnknownTermsModeBeforeWriting() {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setTermsMode("BOGUS");

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter));

        assertTrue(ex.getMessage().contains("条款档不合法"));
        verify(chapterMapper, never()).insertCmsTopicChapter(chapter);
    }

    @Test
    void insert_rejectsTrafficChapterWithPerkThresholdBeforeWriting() {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setTermsMode("TRAFFIC");
        chapter.setPerkMinValue(BigDecimal.ONE);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter));

        assertTrue(ex.getMessage().contains("只有权益档有权益门槛"));
        verify(chapterMapper, never()).insertCmsTopicChapter(chapter);
    }

    @Test
    void insert_rejectsNonPositivePerkThresholdBeforeWriting() {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setTermsMode("PERK");
        chapter.setPerkMinValue(BigDecimal.ZERO);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter));

        assertTrue(ex.getMessage().contains("权益最低价值"));
        verify(chapterMapper, never()).insertCmsTopicChapter(chapter);
    }

    @Test
    void insert_rejectsNonRevshareChapterWithFeeCapBeforeWriting() {
        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setTermsMode("PERK");
        chapter.setMaxPerHeadFee(BigDecimal.ONE);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter));

        assertTrue(ex.getMessage().contains("只有计酬档谈人头费上限"));
        verify(chapterMapper, never()).insertCmsTopicChapter(chapter);
    }

    @Test
    void update_rejectsInvalidCategoryForEnabledRecruitment() {
        CmsTopicChapter current = recruitmentChapter();
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(current);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic(ProductType.FREE_EXPLORE));
        when(categoryService.selectSysCategoryById(19L)).thenReturn(null);

        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(CHAPTER);
        patch.setCategoryId(19);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(patch));

        assertTrue(ex.getMessage().contains("不存在或已停用"));
        verify(chapterMapper, never()).updateCmsTopicChapter(patch);
    }

    /**
     * 招商开关写入必须与父主题软删串行：普通 select 会在“读到未删 → 主题被删 → 写章节”
     * 的窗口留下已删除主题下的开放招商章。事务 + FOR UPDATE 让这两个写操作只能二选一地先完成。
     */
    @Test
    void insert_locksParentTopicUntilRecruitmentChapterIsWritten() throws Exception {
        CmsTopicChapter chapter = recruitmentChapter();
        SysCategory category = new SysCategory();
        category.setId(18L);
        category.setType(1L);
        category.setParentId(0L);
        category.setStatus(1);
        category.setDelFlag(0);
        when(topicMapper.selectCmsTopicByIdForUpdate(TOPIC)).thenReturn(topic(ProductType.FREE_EXPLORE));
        when(categoryService.selectSysCategoryById(18L)).thenReturn(category);

        service.insertCmsTopicChapter(chapter);

        org.mockito.InOrder order = inOrder(topicMapper, chapterMapper);
        order.verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC);
        order.verify(chapterMapper).insertCmsTopicChapter(chapter);
        assertNotNull(CmsTopicChapterServiceImpl.class
                .getMethod("insertCmsTopicChapter", CmsTopicChapter.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void update_rejectsMovingChapterToAnotherTopicEvenWhenItIsFreeExplore() {
        CmsTopicChapter current = recruitmentChapter();
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(current);
        when(topicMapper.selectCmsTopicById(43L)).thenReturn(topic(ProductType.FREE_EXPLORE));
        SysCategory category = new SysCategory();
        category.setId(18L);
        category.setType(1L);
        category.setParentId(0L);
        category.setStatus(1);
        category.setDelFlag(0);
        when(categoryService.selectSysCategoryById(18L)).thenReturn(category);

        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(CHAPTER);
        patch.setTopicId(43L);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(patch));

        assertTrue(ex.getMessage().contains("不能在通用编辑中更换主题"));
        verify(chapterMapper, never()).updateCmsTopicChapter(patch);
    }

    @Test
    void update_rejectsReenablingLegacyInvalidCapacity() {
        CmsTopicChapter current = recruitmentChapter();
        current.setRecruitEnabled(0);
        current.setMaxMerchant(-1);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(current);

        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(CHAPTER);
        patch.setRecruitEnabled(1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(patch));

        assertTrue(ex.getMessage().contains("0到127"));
        verify(chapterMapper, never()).updateCmsTopicChapter(patch);
    }

    @Test
    void update_rejectsEnablingRevshareForV1Recruitment() {
        CmsTopicChapter current = recruitmentChapter();
        current.setRecruitEnabled(0);
        current.setTermsMode("REVSHARE");
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(current);

        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(CHAPTER);
        patch.setRecruitEnabled(1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(patch));

        assertTrue(ex.getMessage().contains("待P2开放"));
        verify(chapterMapper, never()).updateCmsTopicChapter(patch);
    }

    @Test
    void update_rejectsEnablingLegacyNonPositivePerkThreshold() {
        CmsTopicChapter current = recruitmentChapter();
        current.setRecruitEnabled(0);
        current.setPerkMinValue(BigDecimal.ZERO);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(current);

        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(CHAPTER);
        patch.setRecruitEnabled(1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(patch));

        assertTrue(ex.getMessage().contains("权益最低价值"));
        verify(chapterMapper, never()).updateCmsTopicChapter(patch);
    }

    @Test
    void configureSupplyTerms_rejectsRevshareForEnabledV1Recruitment() {
        CmsTopicChapter current = recruitmentChapter();
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(current);
        ChapterSupplyTermsDTO terms = new ChapterSupplyTermsDTO();
        terms.setTermsMode("REVSHARE");
        terms.setMaxPerHeadFee(BigDecimal.ONE);
        terms.setMaxMerchant(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.configureSupplyTerms(CHAPTER, terms));

        assertTrue(ex.getMessage().contains("待P2开放"));
        verify(chapterMapper, never()).updateSupplyTerms(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void delete_rejectsChapterWithRecruitmentRelations() {
        when(applicationMapper.countCurrentByChapter(CHAPTER)).thenReturn(1);
        when(offerMapper.countAllByChapter(CHAPTER)).thenReturn(0);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.deleteCmsTopicChapterById(CHAPTER));

        assertTrue(ex.getMessage().contains("不能直接删除"));
        verify(chapterMapper, never()).deleteCmsTopicChapterById(CHAPTER);
    }

    @Test
    void delete_allowsChapterWithoutRecruitmentRelations() {
        when(applicationMapper.countCurrentByChapter(CHAPTER)).thenReturn(0);
        when(offerMapper.countAllByChapter(CHAPTER)).thenReturn(0);

        service.deleteCmsTopicChapterById(CHAPTER);

        verify(chapterMapper).deleteCmsTopicChapterById(CHAPTER);
    }

    @Test
    void update_rejectsRewritingLegacyInvalidCapacityUnchanged() {
        CmsTopicChapter current = recruitmentChapter();
        current.setRecruitEnabled(0);
        current.setMaxMerchant(-1);
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(current);

        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(CHAPTER);
        patch.setName("只改名称");
        patch.setMaxMerchant(-1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(patch));

        assertTrue(ex.getMessage().contains("0到127"));
        verify(chapterMapper, never()).updateCmsTopicChapter(patch);
    }

    @Test
    void update_locksChapterBeforeReadingCurrentRecruitmentConfiguration() throws Exception {
        CmsTopicChapter current = recruitmentChapter();
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(current);
        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(CHAPTER);
        patch.setRecruitEnabled(0);

        service.updateCmsTopicChapter(patch);

        org.mockito.InOrder order = inOrder(chapterMapper, topicMapper);
        order.verify(chapterMapper).selectCmsTopicChapterById(CHAPTER);
        order.verify(topicMapper).selectCmsTopicByIdForUpdate(TOPIC);
        order.verify(chapterMapper).selectContentByIdForUpdate(CHAPTER);
        order.verify(chapterMapper).updateCmsTopicChapter(patch);
        assertNotNull(CmsTopicChapterServiceImpl.class
                .getMethod("updateCmsTopicChapter", CmsTopicChapter.class)
                .getAnnotation(Transactional.class));
    }

    // ---------- P1-LC-1:后台通用 CRUD 也不能把「已发布免费主题」写成商业 ----------

    /**
     * 9-15 拍板:已发布免费主题不能转商业。开招募 = recruitmentOpen,是商业事实;
     * 后台建章入口此前只查「仅自由探索主题」(FREE_EXPLORE 本身已算商业),不查保护口径。
     * 这里钉的是「这条入口真的执行了写后复核」——把调用删掉,本条立即红。
     */
    @Test
    void insert_openingRecruitmentOnProtectedTopicMustRunConversionGuard() {
        CmsTopicChapter chapter = recruitmentChapter();
        when(categoryService.selectSysCategoryById(18L)).thenReturn(category());
        when(commercialTopicPolicy.isConversionProtected(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED))
                .when(commercialTopicPolicy).assertNotConvertedToCommercial(TOPIC, true);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.insertCmsTopicChapter(chapter));

        assertEquals(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED, ex.getMessage());
        // 写后复核:章节已写、再由复核抛出回滚(与票务入口同一语义)。
        verify(chapterMapper).insertCmsTopicChapter(chapter);
    }

    @Test
    void update_openingRecruitmentOnProtectedTopicMustRunConversionGuard() {
        CmsTopicChapter current = recruitmentChapter();
        current.setRecruitEnabled(0);
        current.setTermsMode("PERK");
        when(chapterMapper.selectCmsTopicChapterById(CHAPTER)).thenReturn(current);
        when(categoryService.selectSysCategoryById(18L)).thenReturn(category());
        when(commercialTopicPolicy.isConversionProtected(org.mockito.ArgumentMatchers.any())).thenReturn(true);
        org.mockito.Mockito.doThrow(new IllegalStateException(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED))
                .when(commercialTopicPolicy).assertNotConvertedToCommercial(TOPIC, true);
        CmsTopicChapter patch = new CmsTopicChapter();
        patch.setId(CHAPTER);
        patch.setRecruitEnabled(1);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> service.updateCmsTopicChapter(patch));

        assertEquals(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED, ex.getMessage());
        verify(chapterMapper).updateCmsTopicChapter(patch);
    }

    /** 平台自营主题豁免:平台自己的主题允许开章节承接,不该多读一道第三方保护口径。 */
    @Test
    void insert_onPlatformOwnedTopicSkipsConversionGuard() {
        CmsTopicChapter chapter = recruitmentChapter();
        when(categoryService.selectSysCategoryById(18L)).thenReturn(category());
        when(productRoleService.isPlatformOwned(TOPIC)).thenReturn(true);

        service.insertCmsTopicChapter(chapter);

        verify(chapterMapper).insertCmsTopicChapter(chapter);
        // 豁免判据在 isPlatformOwned:被豁免时不该再去读第三方保护口径。
        verify(commercialTopicPolicy, never()).isConversionProtected(org.mockito.ArgumentMatchers.any());
    }

    private SysCategory category() {
        SysCategory category = new SysCategory();
        category.setId(18L);
        category.setType(1L);
        category.setParentId(0L);
        category.setStatus(1);
        category.setDelFlag(0);
        return category;
    }
}
