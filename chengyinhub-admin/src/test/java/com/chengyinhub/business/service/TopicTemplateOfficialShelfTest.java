package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicTemplateMeta;
import com.chengyinhub.business.domain.vo.TopicTemplateListItemVO;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.service.impl.TopicQueryService;
import com.chengyinhub.business.util.TemplateStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 货架推荐闸 + 官方范本标识。
 *
 * <p>钉死三件事:
 * ① recommendedOnly=true 只回 VERIFIED —— EXPERIMENTAL/PILOT/RETIRED/未录入元数据一律不出现
 *    (货架 SQL 已是 VERIFIED-only,本闸是 service 层第二道,防日后 SQL 放开时推荐口径被连带放宽);
 * ② recommendedOnly=false 默认行为不变,SQL 回什么就出什么;
 * ③ isOfficial 从元数据透传,且官方范本排在非官方之前、组内保持既有次序。
 * <p>负控已实测:注掉 service 中 {@code recommendedOnly} 的 removeIf 后,
 * ①的断言变红(4≠1);恢复后全绿。
 */
class TopicTemplateOfficialShelfTest {

    private TopicQueryService service;
    private CmsTopicMapper cmsTopicMapper;
    private CmsTopicTemplateMetaMapper metaMapper;

    @BeforeEach
    void setUp() {
        service = new TopicQueryService();
        cmsTopicMapper = mock(CmsTopicMapper.class);
        metaMapper = mock(CmsTopicTemplateMetaMapper.class);
        CmsTopicChapterMapper chapterMapper = mock(CmsTopicChapterMapper.class);
        CmsTopicNodeMapper nodeMapper = mock(CmsTopicNodeMapper.class);
        ReflectionTestUtils.setField(service, "cmsTopicMapper", cmsTopicMapper);
        ReflectionTestUtils.setField(service, "cmsTopicChapterMapper", chapterMapper);
        ReflectionTestUtils.setField(service, "cmsTopicNodeMapper", nodeMapper);
        ReflectionTestUtils.setField(service, "cmsTopicTemplateMetaMapper", metaMapper);
        when(chapterMapper.selectCmsTopicChapterList(any())).thenReturn(Collections.emptyList());
        when(nodeMapper.selectCmsTopicNodeList(any())).thenReturn(Collections.emptyList());
    }

    private CmsTopic topic(long id) {
        CmsTopic t = new CmsTopic();
        t.setId(id);
        t.setName("模板" + id);
        return t;
    }

    private CmsTopicTemplateMeta meta(long topicId, String status, Integer isOfficial) {
        CmsTopicTemplateMeta m = new CmsTopicTemplateMeta();
        m.setTopicId(topicId);
        m.setTemplateStatus(status);
        m.setIsOfficial(isOfficial);
        return m;
    }

    /** ① 推荐口径只认 VERIFIED;未录入元数据的也不算推荐。 */
    @Test
    void recommendedOnly_filtersOutEverythingButVerified() {
        when(cmsTopicMapper.selectTopicTemplateList()).thenReturn(Arrays.asList(
                topic(5L), topic(4L), topic(3L), topic(2L), topic(1L)));
        when(metaMapper.selectByTopicIds(anyList())).thenReturn(Arrays.asList(
                meta(1L, TemplateStatus.VERIFIED, 0),
                meta(2L, TemplateStatus.EXPERIMENTAL, 0),
                meta(3L, TemplateStatus.PILOT, 0),
                meta(4L, TemplateStatus.RETIRED, 0)));
                // topic 5 无元数据行 = 未录入,同样不得进推荐

        List<TopicTemplateListItemVO> result = service.buildTopicTemplateListVO(true);

        assertEquals(1, result.size(), "推荐口径只允许 VERIFIED,实验/试点/停用/未录入都不得出现");
        assertEquals(1L, (long) result.get(0).getId());
        assertEquals(TemplateStatus.VERIFIED, result.get(0).getTemplateStatus());
    }

    /** ② 默认(recommendedOnly=false)不过滤,SQL 回什么就出什么 —— 别破坏既有货架调用。 */
    @Test
    void defaultList_keepsAllRowsFromSql() {
        when(cmsTopicMapper.selectTopicTemplateList()).thenReturn(Arrays.asList(
                topic(2L), topic(1L)));
        when(metaMapper.selectByTopicIds(anyList())).thenReturn(Arrays.asList(
                meta(1L, TemplateStatus.VERIFIED, 0),
                meta(2L, TemplateStatus.EXPERIMENTAL, 0)));

        List<TopicTemplateListItemVO> result = service.buildTopicTemplateListVO(false);

        assertEquals(2, result.size(), "默认行为不变:service 层不做状态过滤");
    }

    /** ③ isOfficial 透传 + 官方置顶,组内保持 SQL 既有次序(id desc)。 */
    @Test
    void official_isPassedThrough_andSortedFirst() {
        when(cmsTopicMapper.selectTopicTemplateList()).thenReturn(Arrays.asList(
                topic(30L), topic(20L), topic(10L)));
        when(metaMapper.selectByTopicIds(anyList())).thenReturn(Arrays.asList(
                meta(30L, TemplateStatus.VERIFIED, 0),
                meta(20L, TemplateStatus.VERIFIED, 1),
                meta(10L, TemplateStatus.VERIFIED, 0)));

        List<TopicTemplateListItemVO> result = service.buildTopicTemplateListVO(false);

        assertEquals(Arrays.asList(20L, 30L, 10L),
                result.stream().map(TopicTemplateListItemVO::getId).collect(Collectors.toList()),
                "官方范本必须排最前,非官方保持既有 id desc 次序");
        assertEquals(Integer.valueOf(1), result.get(0).getIsOfficial());
        assertEquals(Integer.valueOf(0), result.get(1).getIsOfficial());
    }

    /** 未录入元数据时 isOfficial 留空,前端按非官方渲染,不得默认成官方。 */
    @Test
    void missingMeta_leavesIsOfficialNull() {
        when(cmsTopicMapper.selectTopicTemplateList()).thenReturn(Collections.singletonList(topic(1L)));
        when(metaMapper.selectByTopicIds(anyList())).thenReturn(Collections.emptyList());

        List<TopicTemplateListItemVO> result = service.buildTopicTemplateListVO(false);

        assertEquals(1, result.size());
        assertNull(result.get(0).getIsOfficial());
    }

    @Test
    void anonymousListSerializesOnlyExplicitPublicFields() throws Exception {
        CmsTopic source = topic(9L);
        source.setMemberId(901L);
        source.setClubId(902L);
        source.setFeePlatformRate(new java.math.BigDecimal("0.20"));
        source.setFeeGuaranteeBaseRate(new java.math.BigDecimal("0.05"));
        source.setFeeGuaranteeStepRate(new java.math.BigDecimal("0.01"));
        source.setFeeGuaranteeMaxMerchant(4);
        source.setRouteGraphJson("{\"private\":true}");
        source.setPublishedRouteGraphJson("{\"private\":true}");
        source.setCompleteRuleJson("{\"secret\":true}");
        source.setConfigVersion(77L);
        source.setReason("内部审核原因");
        source.setCreateBy("admin-secret");
        source.setUpdateBy("reviewer-secret");
        source.setCheckStatus(2);
        source.setDepositStatus(1);
        source.setTeamStatus(1);
        when(cmsTopicMapper.selectTopicTemplateList()).thenReturn(Collections.singletonList(source));
        when(metaMapper.selectByTopicIds(anyList())).thenReturn(Collections.singletonList(
                meta(9L, TemplateStatus.VERIFIED, 1)));

        String json = new ObjectMapper().writeValueAsString(service.buildTopicTemplateListVO(false));

        assertTrue(json.contains("\"id\":9"));
        assertTrue(json.contains("\"name\":\"模板9\""));
        for (String forbidden : Arrays.asList(
                "memberId", "clubId", "feePlatformRate", "feeGuaranteeBaseRate",
                "feeGuaranteeStepRate", "feeGuaranteeMaxMerchant", "routeGraphJson",
                "publishedRouteGraphJson", "completeRuleJson", "configVersion", "reason",
                "createBy", "updateBy", "status", "userStatus", "merchantStatus",
                "checkStatus", "depositStatus", "teamStatus", "lifecycle", "delFlag")) {
            assertFalse(json.contains("\"" + forbidden + "\""),
                    "匿名模板列表不得序列化内部字段: " + forbidden + "; body=" + json);
        }
    }
}
