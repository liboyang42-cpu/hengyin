package com.chengyinhub.web.controller.business;

import java.util.List;
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
import org.springframework.web.bind.annotation.RestController;

import com.chengyinhub.business.domain.ThemeStateKey;
import com.chengyinhub.business.opsconsole.JourneyRulesAdminService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;

/** R10 BE-24 主题数值规则与状态键的结构化编辑器（校验与作者端同源，预览不落库）。 */
@RestController
@RequestMapping("/business/journeyRules")
public class JourneyRulesAdminController extends BaseController
{
    @Autowired
    private JourneyRulesAdminService service;

    @PreAuthorize("@ss.hasPermi('business:journeyRules:list')")
    @GetMapping("/topics")
    public AjaxResult topics(String keyword, Long topicId)
    {
        return success(service.topics(keyword, topicId));
    }

    @PreAuthorize("@ss.hasPermi('business:journeyRules:list')")
    @GetMapping("/{topicId}")
    public AjaxResult read(@PathVariable Long topicId)
    {
        return success(service.read(topicId));
    }

    @PreAuthorize("@ss.hasPermi('business:journeyRules:list')")
    @PostMapping("/{topicId}/preview")
    public AjaxResult preview(@PathVariable Long topicId, @RequestBody Map<String, Object> body)
    {
        return success(service.preview(topicId, RewardPledgeAdminController.str(body, "rulesJson")));
    }

    @PreAuthorize("@ss.hasPermi('business:journeyRules:edit')")
    @Log(title = "保存主题数值规则", businessType = BusinessType.UPDATE)
    @PutMapping("/{topicId}")
    public AjaxResult save(@PathVariable Long topicId, @RequestBody Map<String, Object> body)
    {
        return success(service.save(getUserId(), topicId, RewardPledgeAdminController.str(body, "rulesJson"),
                RewardPledgeAdminController.str(body, "note")));
    }

    @PreAuthorize("@ss.hasPermi('business:journeyRules:list')")
    @PostMapping("/state-keys/check")
    public AjaxResult checkKeys(@RequestBody List<ThemeStateKey> keys)
    {
        return success(service.checkStateKeys(keys));
    }

    @PreAuthorize("@ss.hasPermi('business:journeyRules:edit')")
    @Log(title = "新建主题状态键", businessType = BusinessType.INSERT)
    @PostMapping("/{topicId}/state-keys")
    public AjaxResult createKey(@PathVariable Long topicId, @RequestBody ThemeStateKey key)
    {
        service.registerStateKey(getUserId(), topicId, key);
        return success();
    }

    @PreAuthorize("@ss.hasPermi('business:journeyRules:edit')")
    @Log(title = "修改主题状态键", businessType = BusinessType.UPDATE)
    @PutMapping("/{topicId}/state-keys/{stateKey}")
    public AjaxResult updateKey(@PathVariable Long topicId, @PathVariable String stateKey, @RequestBody ThemeStateKey patch)
    {
        return success(service.updateStateKey(getUserId(), topicId, stateKey, patch));
    }

    @PreAuthorize("@ss.hasPermi('business:journeyRules:edit')")
    @Log(title = "删除主题状态键", businessType = BusinessType.DELETE)
    @DeleteMapping("/{topicId}/state-keys/{stateKey}")
    public AjaxResult deleteKey(@PathVariable Long topicId, @PathVariable String stateKey, String note)
    {
        service.deleteStateKey(getUserId(), topicId, stateKey, note);
        return success();
    }
}
