package com.chengyinhub.web.controller.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.chengyinhub.business.domain.dto.ContentDraftCommand;
import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.IContentDraftService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;

@RestController
@RequestMapping("/api/content-draft")
public class ApiContentDraftController extends BaseController {
    @Autowired
    private IContentDraftService draftService;
    @Autowired
    private IMerchantAccessService merchantAccessService;

    @PostMapping("/save")
    public AjaxResult save(@RequestBody ContentDraftCommand command) {
        return invoke(() -> draftService.save(ownerMemberId(getAppUserId(), command.getScope()), command));
    }

    @PostMapping("/restore")
    public AjaxResult restore(String draft_id, String scope) {
        Long id = com.chengyinhub.common.core.text.Convert.toLong(draft_id, 0L);
        return invoke(() -> draftService.restore(ownerMemberId(getAppUserId(), scope), id));
    }

    @PostMapping("/list")
    public AjaxResult list(String business_type, String scope) {
        return invoke(() -> draftService.list(ownerMemberId(getAppUserId(), scope), business_type));
    }

    @PostMapping("/delete")
    public AjaxResult delete(@RequestBody ContentDraftCommand command) {
        return invoke(() -> draftService.delete(ownerMemberId(getAppUserId(), command.getScope()), command));
    }

    /** 既有发布接口成功返回资源 id 后调用；本接口校验资源归属并 CAS 归档草稿。 */
    @PostMapping("/publish")
    public AjaxResult publish(@RequestBody ContentDraftCommand command) {
        return invoke(() -> draftService.publish(ownerMemberId(getAppUserId(), command.getScope()), command));
    }

    private Long ownerMemberId(Long principalMemberId, String scope) {
        if (!MerchantOperationScope.isMerchant(scope)) return principalMemberId;
        return merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE).getOwnerMemberId();
    }

    private AjaxResult invoke(Action action) {
        try {
            return success(action.run());
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        } catch (Exception e) {
            logger.error("草稿接口异常", e);
            return error("操作失败，请稍后重试");
        }
    }

    private interface Action { Object run(); }
}
