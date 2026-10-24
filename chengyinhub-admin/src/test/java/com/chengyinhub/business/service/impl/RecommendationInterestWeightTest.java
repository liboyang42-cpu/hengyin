package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BizEventLog;
import com.chengyinhub.business.mapper.MemberInterestProfileMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/** owner 2026-10-23 D2(P0-33 / P0-77):payment_success 兴趣权重 8 → 1;打分任务保持暂停登记。 */
class RecommendationInterestWeightTest {

    private static int weightWritten(String eventName) {
        MemberInterestProfileMapper mapper = mock(MemberInterestProfileMapper.class);
        RecommendationServiceImpl service = new RecommendationServiceImpl();
        ReflectionTestUtils.setField(service, "memberInterestProfileMapper", mapper);
        BizEventLog event = new BizEventLog();
        event.setMemberId(7L);
        event.setEventName(eventName);
        event.setBizType("activity");
        service.updateProfileFromEvent(event);
        org.mockito.ArgumentCaptor<Integer> weight = org.mockito.ArgumentCaptor.forClass(Integer.class);
        verify(mapper).incrementMemberCategoryWeight(eq(7L), any(), anyString(), weight.capture(), any(), any(), any());
        return weight.getValue();
    }

    @Test
    void paymentSuccessWeighsTheSameAsAView() {
        org.junit.jupiter.api.Assertions.assertEquals(1, weightWritten("payment_success"));
        org.junit.jupiter.api.Assertions.assertEquals(1, weightWritten("content_view"));
    }

    @Test
    void otherWeightsAreUnchanged() {
        org.junit.jupiter.api.Assertions.assertEquals(3, weightWritten("content_like"));
        org.junit.jupiter.api.Assertions.assertEquals(4, weightWritten("content_favorite"));
        org.junit.jupiter.api.Assertions.assertEquals(5, weightWritten("signup_submit"));
    }

    @Test
    void dailyScoringJobStaysRegisteredAsPaused() throws Exception {
        String sql = new String(Files.readAllBytes(Paths.get("sql",
                "migration_o14_reco_search_jobs_20261022.sql")), StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        assertTrue(sql.contains("'recommendationScoreDailyTask.run()', '0 20 3 * * ?', '2', '1', '1',"),
                "每日推荐打分任务必须以 status='1'(暂停)登记");
    }
}
