package com.chengyinhub.web.controller.api;
import com.chengyinhub.business.service.impl.AppMediaAssetService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/media/asset")
public class ApiMediaAssetController extends BaseController {
    private final AppMediaAssetService media;
    public ApiMediaAssetController(AppMediaAssetService media) {this.media=media;}
    @GetMapping("/list") public AjaxResult list(@RequestParam(required=false) Long beforeId) {return success(media.ownedRecent(getAppUserId(),beforeId));}
    @GetMapping("/{id}") public AjaxResult status(@PathVariable Long id) {
        Map<String,Object> result=media.status(id,getAppUserId());
        if("APPROVED".equals(result.get("reviewState")))result.put("url",media.approvedUrl(id));
        return success(result);
    }
    @GetMapping("/{id}/preview") public AjaxResult preview(@PathVariable Long id) {
        return success(java.util.Collections.singletonMap("authorPreviewUrl",media.ownerPreview(id,getAppUserId())));
    }
    @PostMapping("/{id}/retry") public AjaxResult retry(@PathVariable Long id) {
        return success(media.retry(id,getAppUserId()));
    }
}
