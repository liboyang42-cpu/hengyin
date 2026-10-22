package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** O5b-5:模板标题相似度提示 —— 确定性、只 WARN 不拦,开关默认关。 */
class TemplateTitleSimilarityServiceTest {
    private static final Long OWNER = 7L;

    private CmsTemplateLibraryMapper mapper;
    private TemplateTitleSimilarityService service;

    @BeforeEach
    void setUp() {
        mapper = mock(CmsTemplateLibraryMapper.class);
        service = new TemplateTitleSimilarityService(mapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        when(mapper.selectPublicTitlesForSimilarity(eq(OWNER), eq(TemplateTitleSimilarityService.POOL_LIMIT)))
                .thenReturn(Arrays.asList(row(1L, 8L, "西湖边的寻宝之旅"), row(2L, 9L, "古城密室逃脱"),
                        row(3L, OWNER, "西湖边的寻宝之旅")));
    }

    @Test
    void nearDuplicateTitleProducesOneWarnInPublishCheckShape() {
        List<TopicPublishCheckService.PublishCheckIssue> issues = service.check("西湖边的寻宝之旅!", OWNER);

        assertEquals(1, issues.size());
        TopicPublishCheckService.PublishCheckIssue issue = issues.get(0);
        assertEquals(TopicPublishCheckService.PublishCheckIssue.LEVEL_WARN, issue.getLevel());
        assertEquals(TemplateTitleSimilarityService.CODE_TEMPLATE_TITLE_SIMILAR, issue.getCode());
        assertTrue(issue.getMessage().contains("西湖边的寻宝之旅"));
        assertTrue(issue.getMessage().contains("不影响发布"));
    }

    @Test
    void distinctTitleProducesNothing() {
        assertTrue(service.check("海边日落摄影打卡", OWNER).isEmpty());
    }

    @Test
    void ownTemplatesAreNeverComparedEvenIfThePoolReturnsThem() {
        when(mapper.selectPublicTitlesForSimilarity(eq(OWNER), anyInt()))
                .thenReturn(Arrays.asList(row(3L, OWNER, "西湖边的寻宝之旅")));
        assertTrue(service.check("西湖边的寻宝之旅", OWNER).isEmpty());
    }

    @Test
    void switchOffDoesNotEvenQuery() {
        ReflectionTestUtils.setField(service, "enabled", false);

        assertTrue(service.check("西湖边的寻宝之旅", OWNER).isEmpty());
        verify(mapper, never()).selectPublicTitlesForSimilarity(anyLong(), anyInt());
    }

    @Test
    void poolReadFailureIsSwallowedBecauseTheHintIsNotAGate() {
        when(mapper.selectPublicTitlesForSimilarity(eq(OWNER), anyInt())).thenThrow(new IllegalStateException("db"));
        assertTrue(service.check("西湖边的寻宝之旅", OWNER).isEmpty());
    }

    @Test
    void similarityIsDeterministicAndSymmetric() {
        assertEquals(1d, TemplateTitleSimilarityService.similarity("City Walk", "city-walk"));
        double ab = TemplateTitleSimilarityService.similarity("西湖边的寻宝之旅", "西湖边的寻宝之旅二");
        double ba = TemplateTitleSimilarityService.similarity("西湖边的寻宝之旅二", "西湖边的寻宝之旅");
        assertEquals(ab, ba);
        assertTrue(ab >= TemplateTitleSimilarityService.WARN_THRESHOLD, "末尾加一个字仍算高度相似: " + ab);
        assertTrue(TemplateTitleSimilarityService.similarity("古城密室逃脱", "古城密室大逃脱")
                < TemplateTitleSimilarityService.WARN_THRESHOLD, "中间插字(Dice≈0.73)不提示,阈值偏保守");
        assertTrue(TemplateTitleSimilarityService.similarity("古城密室逃脱", "海边日落摄影") < 0.2d);
    }

    @Test
    void atMostThreeWarnings() {
        when(mapper.selectPublicTitlesForSimilarity(eq(OWNER), anyInt())).thenReturn(Arrays.asList(
                row(1L, 8L, "城市寻宝"), row(2L, 9L, "城市寻宝!"), row(3L, 10L, "城市 寻宝"), row(4L, 11L, "城市寻宝。")));
        assertEquals(TemplateTitleSimilarityService.MAX_MATCHES, service.check("城市寻宝", OWNER).size());
    }

    private static CmsTemplateLibrary row(Long id, Long memberId, String title) {
        CmsTemplateLibrary row = new CmsTemplateLibrary();
        row.setId(id);
        row.setMemberId(memberId);
        row.setTitle(title);
        return row;
    }
}
