package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.support.AuthorAttributeRegistryReader;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Same owner / explicit merchant PROJECT_MANAGE contract as /api/topic/state-keys. */
@RestController
@RequestMapping("/api/topic")
public class ApiTopicAttributeBindingController extends BaseController {
    private final AuthorAttributeRegistryReader reader;
    private final IMerchantAccessService merchantAccess;
    public ApiTopicAttributeBindingController(AuthorAttributeRegistryReader reader, IMerchantAccessService merchantAccess) {
        this.reader = reader; this.merchantAccess = merchantAccess;
    }
    @GetMapping("/attribute-binding")
    public AjaxResult read(Long topicId, String scope) {
        if (topicId == null || topicId <= 0L) return AjaxResult.error(400, "主题编号无效");
        Long member;
        try { member = getAppUserId(); } catch (Exception unauthenticated) { return AjaxResult.error(401, "请先登录"); }
        if (member == null || member <= 0L) return AjaxResult.error(401, "请先登录");
        if (MerchantOperationScope.isMerchant(scope)) {
            member = merchantAccess.require(member, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
        }
        return AjaxResult.success(reader.read(topicId, member));
    }
}
