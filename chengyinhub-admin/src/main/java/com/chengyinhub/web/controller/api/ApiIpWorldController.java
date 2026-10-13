package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.dto.IpWorldQueryDTO;
import com.chengyinhub.business.service.IIpWorldService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * P3 IP世界接口。
 */
@RestController
@Tag(name = "P3 IP世界")
@RequestMapping("/api/world")
public class ApiIpWorldController extends BaseController {

    @Autowired
    private IIpWorldService ipWorldService;

    @Operation(summary = "IP世界首页")
    @PostMapping("/home")
    public AjaxResult home(String city_code, Long world_id) {
        IpWorldQueryDTO query = new IpWorldQueryDTO();
        query.setCityCode(city_code);
        query.setWorldId(world_id);
        return success(ipWorldService.getHome(query));
    }

    @Operation(summary = "世界任务")
    @PostMapping("/quests")
    public AjaxResult quests(Long world_id) {
        return success(ipWorldService.listPublishedQuests(world_id));
    }
}
