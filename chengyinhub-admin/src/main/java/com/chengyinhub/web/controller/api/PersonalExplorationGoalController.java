package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.PersonalExplorationGoalService;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/exploration-goals")
public class PersonalExplorationGoalController extends BaseController {
    private final PersonalExplorationGoalService service;
    public PersonalExplorationGoalController(PersonalExplorationGoalService service) { this.service = service; }
    public static class Create {
        public String requestId; public String metric;
        @JsonDeserialize(using = StrictInteger.class)
        public Integer target;
        public String startDate; public String endDateExclusive;
    }
    /** Do not silently truncate 1.9 to 1 or accept a numeric string as a target. */
    public static final class StrictInteger extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (parser.currentToken() != JsonToken.VALUE_NUMBER_INT) throw JsonMappingException.from(parser, "选项必须是整数");
            return parser.getIntValue();
        }
    }
    @PostMapping("/create") public AjaxResult create(@RequestBody Create input) {
        return success(service.create(getAppUserId(), input.requestId, input.metric, input.target, input.startDate, input.endDateExclusive));
    }
    @GetMapping("/receipt") public AjaxResult receipt(@RequestParam String requestId) { return success(service.receipt(getAppUserId(), requestId)); }
    @GetMapping("/detail") public AjaxResult detail(@RequestParam Long id) { return success(service.detail(getAppUserId(), id)); }
    @GetMapping("/list") public AjaxResult list(Long beforeId, Integer limit) { return success(service.list(getAppUserId(), beforeId, limit)); }
    @PostMapping("/cancel") public AjaxResult cancel(@RequestParam Long id) { return success(service.cancel(getAppUserId(), id)); }
}
