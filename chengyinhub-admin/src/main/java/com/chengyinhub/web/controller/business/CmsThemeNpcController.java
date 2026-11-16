package com.chengyinhub.web.controller.business;

import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.service.support.ThemeNpcAdminService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;

/**
 * E1 主题剧本人物表(npc_profile scope_type=1)、3D形象任务审核、声音授权审计与AI提供方状态接口。
 */
@RestController
@RequestMapping("/business/themeNpc")
public class CmsThemeNpcController extends BaseController
{
    @Autowired
    private ThemeNpcAdminService themeNpcAdminService;

    @PreAuthorize("@ss.hasPermi('business:themeNpc:list')")
    @GetMapping("/list")
    public AjaxResult list(@RequestParam(required = false) Long topicId,
                           @RequestParam(required = false) String name)
    {
        return success(themeNpcAdminService.listThemeNpcs(topicId, name));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:query')")
    @GetMapping("/{id:[0-9]+}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(themeNpcAdminService.getThemeNpc(id));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:list')")
    @GetMapping("/topics")
    public AjaxResult topics(@RequestParam(required = false) String title)
    {
        return success(themeNpcAdminService.listTopics(title));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:list')")
    @GetMapping("/relationKeys")
    public AjaxResult relationKeys(@RequestParam Long topicId)
    {
        return success(themeNpcAdminService.listRelationKeys(topicId));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:add')")
    @PostMapping
    public AjaxResult add(@RequestBody NpcProfile body)
    {
        return toAjax(themeNpcAdminService.addThemeNpc(body));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:edit')")
    @PutMapping
    public AjaxResult edit(@RequestBody NpcProfile body)
    {
        return toAjax(themeNpcAdminService.updateThemeNpc(body));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:remove')")
    @DeleteMapping("/{id:[0-9]+}")
    public AjaxResult remove(@PathVariable("id") Long id)
    {
        return toAjax(themeNpcAdminService.deleteThemeNpc(id));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:list')")
    @GetMapping("/avatarJobs")
    public AjaxResult avatarJobs(@RequestParam(required = false) String status,
                                 @RequestParam(required = false) String reviewStatus,
                                 @RequestParam(required = false) Long merchantId)
    {
        return success(themeNpcAdminService.listAvatarJobs(status, reviewStatus, merchantId));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:edit')")
    @PostMapping("/avatarJobs/{id:[0-9]+}/review")
    public AjaxResult reviewAvatarJob(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, String> body)
    {
        String decision = body == null ? null : body.get("decision");
        String reason = body == null ? null : body.get("reason");
        return success(themeNpcAdminService.reviewAvatarJob(id, decision, reason));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:list')")
    @GetMapping("/voiceAudits")
    public AjaxResult voiceAudits(@RequestParam(required = false) Integer scopeType,
                                  @RequestParam(required = false) Long scopeId)
    {
        return success(themeNpcAdminService.listVoiceAudits(scopeType, scopeId));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:edit')")
    @PostMapping("/voiceAudits/{id:[0-9]+}/revoke")
    public AjaxResult revokeVoiceConsent(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body)
    {
        Long expectedVersion = null;
        if (body != null && body.get("voiceVersion") instanceof Number)
        {
            expectedVersion = ((Number) body.get("voiceVersion")).longValue();
        }
        return success(themeNpcAdminService.revokeVoiceConsent(id, expectedVersion));
    }

    @PreAuthorize("@ss.hasPermi('business:themeNpc:list')")
    @GetMapping("/providers")
    public AjaxResult providers()
    {
        return success(themeNpcAdminService.providerKeyStatuses());
    }
}
