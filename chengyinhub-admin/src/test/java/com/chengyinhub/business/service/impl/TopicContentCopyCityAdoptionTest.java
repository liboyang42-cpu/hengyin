package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.service.support.TopicLocationContract;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.system.service.ISysCategoryService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模板跨城采用：故事结构/引用保留，地点要求与城市事实分离，跨城明确待重绑，来源不改。
 *
 * <p>负例锚点：
 * <ul>
 *   <li>删掉 {@code copyTopicBasicInfo} 的 cityCode/addressName 分流 → 本案红；</li>
 *   <li>删掉 {@code copyNodes} 的 newCity 清空 → 旧城坐标进副本，本案红；</li>
 *   <li>把 LEGACY 的合同缺口判成 error → 旧版采用集体被拦，本案红。</li>
 * </ul>
 */
class TopicContentCopyCityAdoptionTest {

    private static final long SOURCE_ID = 41L;
    private static final long COPY_ID = 42L;
    private static final long CHAPTER_ID = 6L;
    private static final long NODE_ID = 11L;
    private static final long NODE2_ID = 12L;

    private CmsTopicMapper topicMapper;
    private CmsTopicChapterMapper chapterMapper;
    private CmsTopicNodeMapper nodeMapper;
    private CmsTopicServiceImpl service;
    /** insert 的副本实例；updateCmsTopic(patch) 会把这几个字段合并进来，模拟真实读写。 */
    private final AtomicReference<CmsTopic> persisted = new AtomicReference<CmsTopic>();
    private final Map<Long, CmsTopicChapter> chapterRows = new LinkedHashMap<Long, CmsTopicChapter>();
    private final Map<Long, CmsTopicNode> nodeRows = new LinkedHashMap<Long, CmsTopicNode>();
    private final AtomicLong chapterIds = new AtomicLong(600L);
    private final AtomicLong nodeIds = new AtomicLong(1100L);
    private CmsTopic original;

    @BeforeEach
    void setUp() {
        topicMapper = mock(CmsTopicMapper.class);
        chapterMapper = mock(CmsTopicChapterMapper.class);
        nodeMapper = mock(CmsTopicNodeMapper.class);
        service = new CmsTopicServiceImpl();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", topicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        com.chengyinhub.business.service.support.TopicPublicationTestWiring.set(service, "sysCategoryService",
                mock(ISysCategoryService.class));

        // 内容主体真源:平台默认账号,复制出的副本 member_id 必须与合同写口的 owner 一致。
        ReflectionTestUtils.setField(service, "platformMemberId", 100000L);
        original = new CmsTopic();
        original.setId(SOURCE_ID);
        original.setName("外滩旧信");
        original.setProductType(ProductType.ORIENTEERING);
        original.setDelFlag(0);
        original.setStatus(1);
        original.setCityCode("310100");
        original.setAddressName("上海市黄浦区中山东一路");
        original.setConfigVersion(7L);
        when(topicMapper.selectCmsTopicById(SOURCE_ID)).thenReturn(original);
        when(topicMapper.selectCmsTopicById(COPY_ID)).thenAnswer(inv -> persisted.get());
        doAnswer(inv -> {
            CmsTopic copy = inv.getArgument(0);
            copy.setId(COPY_ID);
            persisted.set(copy);
            return 1;
        }).when(topicMapper).insertCmsTopic(any());
        doAnswer(inv -> {
            CmsTopic patch = inv.getArgument(0);
            CmsTopic row = persisted.get();
            if (row == null) return 0;
            if (patch.getCityCode() != null) row.setCityCode(patch.getCityCode());
            if (patch.getThemeRuntimeConfigJson() != null) row.setThemeRuntimeConfigJson(patch.getThemeRuntimeConfigJson());
            if (patch.getJourneyStoryJson() != null) row.setJourneyStoryJson(patch.getJourneyStoryJson());
            if (patch.getCompleteRuleJson() != null) row.setCompleteRuleJson(patch.getCompleteRuleJson());
            return 1;
        }).when(topicMapper).updateCmsTopic(any());
        // 地点合同只有一个写口:owner 由 SQL 判,通用 updateCmsTopic 不碰这一列(见 mapper 契约测试)。
        doAnswer(inv -> {
            CmsTopic row = persisted.get();
            if (row == null || !row.getId().equals(inv.getArgument(0))) return 0;
            if (!java.util.Objects.equals(row.getMemberId(), inv.getArgument(1))) return 0;
            row.setContentContractJson(inv.getArgument(2));
            return 1;
        }).when(topicMapper).updateTopicLocationContract(anyLong(), anyLong(), any(), any(), any());

        CmsTopicChapter chapter = new CmsTopicChapter();
        chapter.setId(CHAPTER_ID);
        chapter.setTopicId(SOURCE_ID);
        chapter.setName("第一章");
        chapter.setRequired(1);
        chapter.setRecruitEnabled(0);
        chapter.setMaxMerchant(0);
        chapter.setBlocksJson("[{\"type\":\"text\",\"content\":\"开场在家里\"}]");
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.singletonList(chapter));
        doAnswer(inv -> {
            CmsTopicChapter row = inv.getArgument(0);
            row.setId(chapterIds.incrementAndGet());
            chapterRows.put(row.getId(), row);
            return 1;
        }).when(chapterMapper).insertCmsTopicChapter(any());
        when(chapterMapper.updateCmsTopicChapter(any())).thenReturn(1);

        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE_ID);
        node.setTopicId(SOURCE_ID);
        node.setChapterId(CHAPTER_ID);
        node.setName("外滩观景台");
        node.setAddress("上海市黄浦区中山东一路 1 号");
        node.setLongitude("121.4903");
        node.setLatitude("31.2400");
        node.setBusinessTime("09:00-18:00");
        node.setImgUrl("https://cdn.example/old-city.jpg");
        node.setSortId(1L);
        node.setMerchantMemberId(777L);
        node.setNodeAuditStatus(1);
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(node));
        doAnswer(inv -> {
            CmsTopicNode row = inv.getArgument(0);
            row.setId(nodeIds.incrementAndGet());
            nodeRows.put(row.getId(), row);
            return 1;
        }).when(nodeMapper).insertCmsTopicNode(any());
        when(nodeMapper.updateCmsTopicNode(any())).thenReturn(1);
    }

    private Long copy(Map<String, Object> params) {
        return com.chengyinhub.business.service.support.TopicCopyTestWiring.owner(service)
                .copyCmsTopic(SOURCE_ID, params);
    }

    private CmsTopicNode copiedNode() {
        assertEquals(1, nodeRows.size(), "应复制一个节点");
        return nodeRows.values().iterator().next();
    }

    @Test
    void legacyUseKeepsFactsAndOnlyWarns() {
        Long copyId = copy(Collections.<String, Object>singletonMap("copyType", 2));
        assertEquals(Long.valueOf(COPY_ID), copyId);

        CmsTopic copy = persisted.get();
        assertEquals("310100", copy.getCityCode(), "旧版采用沿用来源城市");
        assertEquals("上海市黄浦区中山东一路", copy.getAddressName(), "旧版采用保持原复制结果");

        CmsTopicNode node = copiedNode();
        assertEquals("上海市黄浦区中山东一路 1 号", node.getAddress(), "旧版采用不得静默清空地点");
        assertNotNull(node.getLongitude());
        assertEquals("09:00-18:00", node.getBusinessTime());
        assertNotNull(node.getCheckinCode());
        assertNull(node.getMerchantMemberId(), "旧商家承接不随副本走");
        assertNull(node.getNodeAuditStatus(), "旧审核痕迹不随副本走");

        String contract = copy.getContentContractJson();
        assertNotNull(contract, "旧版采用也写来源血缘");
        JsonNode adoption = TopicLocationContract.parse(contract).path("adoption");
        assertEquals("LEGACY", adoption.path("bindingIntent").asText());
        assertEquals(String.valueOf(SOURCE_ID), adoption.path("sourceTemplateTopicId").asText());
        assertEquals(7L, adoption.path("sourceConfigVersion").asLong(),
                "来源身份用现有 configVersion，不另建 hash 口径");

        for (Map<String, Object> issue : TopicLocationContract.bindingIssues(contract, "310100")) {
            assertEquals("warn", issue.get("level"), "旧版采用的要求缺口只能是提示，不能拦发布");
        }

        // 源主题一字未改：地址/坐标/城市/合同都保持原值。
        assertEquals("上海市黄浦区中山东一路", original.getAddressName());
        assertEquals("310100", original.getCityCode());
        assertNull(original.getContentContractJson());
        assertEquals(7L, original.getConfigVersion());

        // 通用宽口不得写地点合同：否则任何把 CmsTopic 绑进请求的入口都能绕过 owner/窗口/revision。
        // 这里不假定宽口被调了几次（没有运行时配置时可能一次都不调），只断言「凡是调过，都不得带合同」。
        for (org.mockito.invocation.Invocation invocation
                : org.mockito.Mockito.mockingDetails(topicMapper).getInvocations()) {
            if (!"updateCmsTopic".equals(invocation.getMethod().getName())) continue;
            assertNull(((CmsTopic) invocation.getArgument(0)).getContentContractJson(),
                    "updateCmsTopic 不得携带地点合同");
        }
        ArgumentCaptor<Long> contractOwner = ArgumentCaptor.forClass(Long.class);
        verify(topicMapper).updateTopicLocationContract(org.mockito.ArgumentMatchers.eq(COPY_ID),
                contractOwner.capture(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), any());
        assertNotNull(contractOwner.getValue(), "专用写口必须带 owner 交给 SQL 判定");
    }

    @Test
    void copyContractWriteIsRejectedWhenNewTopicIsNotOwnedByItsMember() {
        // 唯一写口按 owner+id 返回 0 时,复制必须回滚而不是当作成功。
        when(topicMapper.updateTopicLocationContract(anyLong(), anyLong(), any(), any(), any())).thenReturn(0);
        assertThrows(IllegalStateException.class,
                () -> copy(Collections.<String, Object>singletonMap("copyType", 2)));
    }

    @Test
    void newCityClearsOldCityFactsAndRequiresRebind() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("bindingIntent", "NEW_CITY");
        params.put("targetCityCode", "320100");
        assertEquals(Long.valueOf(COPY_ID), copy(params));

        CmsTopic copy = persisted.get();
        assertEquals("320100", copy.getCityCode());
        assertNull(copy.getAddressName(), "跨城后主题地址属于旧城事实，必须清空");

        CmsTopicNode node = copiedNode();
        assertNull(node.getAddress(), "跨城不得把旧城地址当新实例已确认地点");
        assertNull(node.getLongitude());
        assertNull(node.getLatitude());
        assertNull(node.getBusinessTime(), "营业时间属于旧城现场条件");
        assertNull(node.getImgUrl(), "旧城实景图不作为新城地点证据");
        assertEquals("外滩观景台", node.getName(), "故事内容与节点名称必须保留");
        assertNotNull(node.getCheckinCode());

        JsonNode contract = TopicLocationContract.parse(copy.getContentContractJson());
        JsonNode binding = contract.path("locationBindings").get(0);
        assertEquals("REBIND_REQUIRED", binding.path("verificationStatus").asText());
        assertTrue(binding.path("nodeId").isNull());
        assertEquals(String.valueOf(NODE_ID), contract.path("locationSlots").get(0)
                .path("sourceNodeId").asText(), "重绑仍需知道原来对应哪个源节点");
        assertFalse(TopicLocationContract.bindingIssues(copy.getContentContractJson(), "320100").isEmpty());
        for (Map<String, Object> issue : TopicLocationContract.bindingIssues(
                copy.getContentContractJson(), "320100")) {
            assertEquals("error", issue.get("level"), "NEW_CITY 必填未绑才挡发布");
        }
        // 草稿可正常落库（不因清空坐标而 NPE）；发布由 location 缺口拒，而不是复制崩。
        assertEquals(Long.valueOf(COPY_ID), copy.getId());
    }

    @Test
    void newCityWithoutTargetCityIsRejectedBeforeAnyWrite() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("bindingIntent", "NEW_CITY");
        assertThrows(IllegalArgumentException.class, () -> copy(params));
        assertNull(persisted.get(), "参数不合法时不得写入副本");
        assertNoCopyWrites("缺目标城市");
    }

    @Test
    void targetCityNotMatchingFormatIsRejected() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("bindingIntent", "NEW_CITY");
        params.put("targetCityCode", "3201");
        assertThrows(IllegalArgumentException.class, () -> copy(params));
        assertNull(persisted.get());
        assertNoCopyWrites("目标城市不是 6 位 adcode");
    }

    @Test
    void newCityEqualToSourceCityIsRejectedBeforeAnyWrite() {
        // 目标城市 = 来源城市（310100）：真正该走同城复用。原来只在地点合同末尾才判，
        // 那时副本父行/章节/节点已经落库；现在必须在第一个写之前拒。
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("bindingIntent", "NEW_CITY");
        params.put("targetCityCode", "310100");
        assertThrows(IllegalArgumentException.class, () -> copy(params));
        assertNull(persisted.get());
        assertNoCopyWrites("换城目标与来源相同");
    }

    @Test
    void sameCityWithDifferentTargetCityIsRejectedBeforeAnyWrite() {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("bindingIntent", "SAME_CITY");
        params.put("targetCityCode", "320100");
        assertThrows(IllegalArgumentException.class, () -> copy(params));
        assertNull(persisted.get());
        assertNoCopyWrites("同城复用却给了别城");
    }

    @Test
    void explicitLegacyIntentIsRejectedBeforeAnyWrite() {
        // 裸 LEGACY 不是客户端契约：不传 bindingIntent 才是旧版采用。
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("bindingIntent", "LEGACY");
        assertThrows(IllegalArgumentException.class, () -> copy(params));
        assertNull(persisted.get());
        assertNoCopyWrites("显式 LEGACY");
    }

    @Test
    void sameCityWithoutTargetCityStaysLegalAndKeepsSourceFacts() {
        // 合法旧路径不得被新校验挡住：SAME_CITY 不要求 targetCityCode，事实照旧继承。
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("bindingIntent", "SAME_CITY");
        assertEquals(Long.valueOf(COPY_ID), copy(params));

        CmsTopic copy = persisted.get();
        assertEquals("310100", copy.getCityCode(), "同城复用沿用来源城市");
        assertEquals("上海市黄浦区中山东一路", copy.getAddressName());
        CmsTopicNode node = copiedNode();
        assertEquals("上海市黄浦区中山东一路 1 号", node.getAddress(), "同城复用不清空地点事实");
        assertNotNull(node.getLongitude());
        JsonNode adoption = TopicLocationContract.parse(copy.getContentContractJson()).path("adoption");
        assertEquals("SAME_CITY", adoption.path("bindingIntent").asText());
        assertTrue(adoption.path("targetCityCode").isNull(), "同城复用不伪造目标城市");
    }

    /**
     * 负例的硬口径：不合法请求不得产生**任何** copy/chapter/node 写。
     *
     * <p>只断言 {@code persisted == null} 是不够的：insert 之后被回滚、或先写了章节/节点再抛，
     * 在 mock 上都会留下调用痕迹。这里逐条 mapper 验「一次都没调过写方法」。
     */
    private void assertNoCopyWrites(String reason) {
        verify(topicMapper, org.mockito.Mockito.never()).insertCmsTopic(any());
        verify(topicMapper, org.mockito.Mockito.never()).updateCmsTopic(any());
        verify(topicMapper, org.mockito.Mockito.never()).updateTopicLocationContract(
                anyLong(), anyLong(), any(), any(), any());
        verify(chapterMapper, org.mockito.Mockito.never()).insertCmsTopicChapter(any());
        verify(chapterMapper, org.mockito.Mockito.never()).updateCmsTopicChapter(any());
        verify(nodeMapper, org.mockito.Mockito.never()).insertCmsTopicNode(any());
        verify(nodeMapper, org.mockito.Mockito.never()).updateCmsTopicNode(any());
        assertTrue(chapterRows.isEmpty() && nodeRows.isEmpty(),
                reason + "：不得留下章节/节点行");
    }

    @Test
    void noLocationNodesProduceNoSlotsAndStayLegal() {
        // 家里开场节点没有地址/坐标：不生成虚假地点槽，也不因缺少地点而失败。
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.singletonList(indoorNode()));
        Map<String, Object> params = new HashMap<String, Object>();
        params.put("copyType", 2);
        params.put("bindingIntent", "NEW_CITY");
        params.put("targetCityCode", "320100");
        assertEquals(Long.valueOf(COPY_ID), copy(params));
        JsonNode contract = TopicLocationContract.parse(persisted.get().getContentContractJson());
        assertEquals(0, contract.path("locationSlots").size());
        assertTrue(TopicLocationContract.bindingIssues(
                persisted.get().getContentContractJson(), "320100").isEmpty());
        assertNull(copiedNode().getAddress());
    }

    private CmsTopicNode indoorNode() {
        CmsTopicNode node = new CmsTopicNode();
        node.setId(NODE2_ID);
        node.setTopicId(SOURCE_ID);
        node.setChapterId(CHAPTER_ID);
        node.setName("家里开场");
        node.setSortId(1L);
        return node;
    }
}
