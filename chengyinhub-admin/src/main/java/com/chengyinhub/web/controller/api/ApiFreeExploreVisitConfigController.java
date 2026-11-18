package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.service.freeexplore.FreeExploreVisitConfigApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** FE-STORY 7.10:自由探索作者设置地点到访方式(GPS / 扫码 / 仅线上)与 GPS 半径。仅主题作者本人。 */
@RestController
@RequestMapping("/api/free-explore/visit-config")
public class ApiFreeExploreVisitConfigController extends BaseController {

    private final FreeExploreVisitConfigApplication application;

    public ApiFreeExploreVisitConfigController(FreeExploreVisitConfigApplication application) {
        this.application = application;
    }

    @GetMapping
    public AjaxResult list(@RequestParam("topicId") Long topicId) {
        return application.list(currentMemberId(), topicId);
    }

    @PostMapping
    public AjaxResult update(@RequestBody VisitConfigRequest request) {
        if (request == null) return AjaxResult.error("参数有误");
        return application.update(currentMemberId(), request.nodeId, request.visitCheck, request.visitRadius);
    }

    private Long currentMemberId() {
        try {
            LoginAppUser login = getLoginAppUser();
            return login == null || login.getAppUser() == null ? null : login.getAppUser().getUserId();
        } catch (RuntimeException notLoggedIn) {
            return null;
        }
    }

    public static class VisitConfigRequest {
        public Long nodeId;
        public String visitCheck;
        public Integer visitRadius;
    }
}
