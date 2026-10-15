package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.PersonalExplorationExportService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import org.springframework.web.bind.annotation.*;
import java.io.IOException;

@RestController
@RequestMapping("/api/personal-exploration-export")
public class PersonalExplorationExportController extends BaseController {
    private final PersonalExplorationExportService service;
    public PersonalExplorationExportController(PersonalExplorationExportService service){this.service=service;}
    public static class Prepare {
        @JsonDeserialize(using=StrictString.class) public String startDate;
        @JsonDeserialize(using=StrictString.class) public String endDateExclusive;
        @JsonDeserialize(using=StrictInteger.class) public Integer pageSize;
    }
    public static class Page { @JsonDeserialize(using=StrictString.class) public String cursor; }
    public static class StrictString extends JsonDeserializer<String> {
        @Override public String deserialize(JsonParser p,DeserializationContext c)throws IOException{if(p.getCurrentToken()!=JsonToken.VALUE_STRING)throw JsonMappingException.from(p,"必须为字符串");return p.getText();}
    }
    public static class StrictInteger extends JsonDeserializer<Integer> {
        @Override public Integer deserialize(JsonParser p,DeserializationContext c)throws IOException{
            if((p.getCurrentToken()!=JsonToken.VALUE_NUMBER_INT&&p.getCurrentToken()!=JsonToken.VALUE_STRING)||!p.getText().matches("[1-9][0-9]*"))throw JsonMappingException.from(p,"页大小须为正整数");
            try{return Integer.valueOf(p.getText());}catch(NumberFormatException e){throw JsonMappingException.from(p,"页大小越界");}
        }
    }
    @PostMapping("/prepare") public AjaxResult prepare(@RequestBody Prepare body){if(body==null)return error("请求不能为空");return success(service.prepare(getAppUserId(),body.startDate,body.endDateExclusive,body.pageSize));}
    @PostMapping("/page") public AjaxResult page(@RequestBody Page body){if(body==null)return error("请求不能为空");return success(service.page(getAppUserId(),body.cursor));}
}
