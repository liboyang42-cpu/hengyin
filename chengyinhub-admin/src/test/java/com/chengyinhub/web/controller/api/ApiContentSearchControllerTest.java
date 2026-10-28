package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ContentSearchService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.github.pagehelper.PageHelper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ApiContentSearchControllerTest {

    @AfterEach
    void clear() {
        PageHelper.clearPage();
    }

    @Test
    void disabledByDefaultReturns503WithoutQuerying() {
        ContentSearchService service = mock(ContentSearchService.class);
        ApiContentSearchController controller = controller(service, false);

        AjaxResult result = controller.unified("咖啡", null, null);

        assertEquals(503, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(service);
    }

    @Test
    void invalidParametersReturn400AndLeaveNoPagingState() {
        ContentSearchService service = mock(ContentSearchService.class);
        ApiContentSearchController controller = controller(service, true);

        AjaxResult result = controller.unified("咖啡", "member", null);

        assertEquals(400, result.get(AjaxResult.CODE_TAG));
        verifyNoInteractions(service);
        assertNull(PageHelper.getLocalPage());
    }

    @Test
    void unifiedResultPreservesBigintIdAsJsonString() throws Exception {
        com.chengyinhub.business.domain.vo.ContentSearchItemVO row = new com.chengyinhub.business.domain.vo.ContentSearchItemVO();
        row.setId(Long.MAX_VALUE); row.setType("post");
        com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode encoded = json.readTree(json.writeValueAsString(row));
        assertEquals(true, encoded.path("id").isTextual());
        assertEquals("9223372036854775807", encoded.path("id").asText());
    }

    private static ApiContentSearchController controller(ContentSearchService service, boolean enabled) {
        ApiContentSearchController controller = new ApiContentSearchController() {
            @Override
            public Long getAppUserId() {
                return 1001L;
            }
        };
        ReflectionTestUtils.setField(controller, "contentSearchService", service);
        ReflectionTestUtils.setField(controller, "unifiedSearchEnabled", enabled);
        return controller;
    }
}
