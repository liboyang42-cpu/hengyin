package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.PersonalExplorationGoalService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PersonalExplorationGoalControllerTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void targetRejectsFractionStringBooleanAndOverflowWithoutGlobalMapperChanges() {
        for (String token : new String[]{"1.9", "1.0", "\"2\"", "true", "2147483648", "{}", "[]"}) {
            assertThrows(Exception.class, () -> json.readValue("{\"target\":" + token + "}", PersonalExplorationGoalController.Create.class));
        }
    }
    @Test void integerIsExactAndNullRemainsForServiceValidation() throws Exception {
        assertEquals(Integer.valueOf(2), json.readValue("{\"target\":2}", PersonalExplorationGoalController.Create.class).target);
        assertNull(json.readValue("{\"target\":null}", PersonalExplorationGoalController.Create.class).target);
    }
    @Test void createUsesAuthenticatedPrincipalAndBoundImmutableFields() {
        PersonalExplorationGoalService service = mock(PersonalExplorationGoalService.class);
        PersonalExplorationGoalController controller = spy(new PersonalExplorationGoalController(service));
        doReturn(7L).when(controller).getAppUserId();
        PersonalExplorationGoalController.Create input = new PersonalExplorationGoalController.Create();
        input.requestId = "original_request_01"; input.metric = "DISTINCT_ROUTES"; input.target = 2; input.startDate = "2026-10-12"; input.endDateExclusive = "2026-11-01";
        when(service.create(7L, input.requestId, input.metric, 2, input.startDate, input.endDateExclusive)).thenReturn(Collections.singletonMap("id", "9"));
        assertEquals(200, controller.create(input).get("code"));
        verify(service).create(7L, input.requestId, input.metric, 2, input.startDate, input.endDateExclusive);
    }
}
