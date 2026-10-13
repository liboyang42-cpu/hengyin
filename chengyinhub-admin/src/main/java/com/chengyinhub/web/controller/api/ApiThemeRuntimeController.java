package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/** 通用主题接口；所有局身份按登录会员校验，不接受客户端传入数值或发放结果。 */
@RestController
@RequestMapping("/api/play/theme")
public class ApiThemeRuntimeController extends BaseController {
    @Autowired private ITopicRouteRuntimeService runtime;
    @GetMapping("/state")
    public AjaxResult state(Long topicId, Long activityId, Long sessionId) {
        return success(runtime.themeView(member(),topicId,activityId,sessionId));
    }
    @PostMapping("/action")
    public AjaxResult action(@RequestBody Action request) {
        if(request==null)throw new IllegalArgumentException("参数不正确");
        return success(runtime.themeAction(member(),request.sessionId,request.expectedVersion,request.actionId,request.operation,request.contentId));
    }
    @PostMapping("/replay")
    public AjaxResult replay(@RequestBody Action request) { if(request==null)throw new IllegalArgumentException("参数不正确"); return success(runtime.replayTheme(member(),request.sessionId)); }
    @GetMapping("/card")
    public AjaxResult card(Long sessionId) {return success(runtime.themeCard(member(),sessionId));}
    @GetMapping("/history")
    public AjaxResult historyPage(Long topicId,Long beforeSessionId,Integer limit) {return success(runtime.themeHistory(member(),topicId,beforeSessionId,limit));}
    public AjaxResult history(Long topicId) { return success(runtime.themeHistory(member(),topicId)); }
    private Long member() {
        Long id=getAppUserId();
        if(id==null || id<=0)throw new com.chengyinhub.common.exception.ServiceException("请先登录",403);
        return id;
    }
    public static class Action {
        public Long sessionId;
        public Integer expectedVersion;
        public String actionId;
        public String operation;
        public String contentId;
    }
}
