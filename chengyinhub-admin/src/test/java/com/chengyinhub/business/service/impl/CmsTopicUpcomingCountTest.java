package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.mapper.CmsTopicMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CmsTopicUpcomingCountTest {

    @Test
    void returnsBatchCountsAndLeavesMissingClubsForCallerToDefault() {
        CmsTopicMapper mapper = mock(CmsTopicMapper.class);
        TopicQueryService service = new TopicQueryService();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mapper);
        Map<String, Object> row = new HashMap<>();
        row.put("clubId", 11L);
        row.put("topicCount", 3L);
        when(mapper.selectUpcomingTopicCounts(eq(Arrays.asList(11L, 22L)), any()))
                .thenReturn(Collections.singletonList(row));

        Map<Long, Integer> result = service.selectUpcomingTopicCounts(Arrays.asList(11L, 22L));

        assertEquals(Integer.valueOf(3), result.get(11L));
        assertEquals(1, result.size());
    }

    @Test
    void emptyClubListDoesNotQueryMapper() {
        CmsTopicMapper mapper = mock(CmsTopicMapper.class);
        TopicQueryService service = new TopicQueryService();
        ReflectionTestUtils.setField(service, "cmsTopicMapper", mapper);

        assertEquals(Collections.emptyMap(), service.selectUpcomingTopicCounts(Collections.emptyList()));
        verify(mapper, never()).selectUpcomingTopicCounts(any(), any());
    }
}
