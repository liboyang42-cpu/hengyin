package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ContentSearchService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * O-14 统一内容搜索(主题 / 活动 / 帖文)。
 * 与 /api/topic/list、/api/activity/list 同一套约定：表单参数 keyword + pageNum/pageSize,返回 data.rows / data.total。
 * 现有接口不改；本接口默认关闭(chengyin.search.unified.enabled=false),且只对登录用户开放(不改匿名放行名单)。
 */
@RestController
@Tag(name = "O-14 统一搜索")
@RequestMapping("/api/search")
public class ApiContentSearchController extends BaseController {

    @Autowired
    private ContentSearchService contentSearchService;

    @Value("${chengyin.search.unified.enabled:false}")
    private boolean unifiedSearchEnabled;

    @Operation(summary = "统一内容搜索")
    @Parameters({
            @Parameter(name = "keyword", description = "搜索关键词(必填，最长 50)"),
            @Parameter(name = "types", description = "逗号分隔 topic,activity,post;缺省全部"),
            @Parameter(name = "city_code", description = "6 位城市编码(可选)")
    })
    @PostMapping("/unified")
    public AjaxResult unified(String keyword, String types, String city_code) {
        if (!unifiedSearchEnabled) {
            return new AjaxResult(503, "统一搜索暂未开放");
        }
        Long memberId;
        try {
            memberId = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        try {
            // 参数先校验再分页：校验失败不能把 PageHelper 线程变量留给下一条 SQL。
            ContentSearchService.validate(keyword, types, city_code);
            startPage();
            return success(getDataTable(contentSearchService.search(keyword, types, city_code, memberId)));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } finally {
            clearPage();
        }
    }
}
