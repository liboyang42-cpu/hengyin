package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.city.draft.CityBoardDraftService;
import com.chengyinhub.common.core.domain.AjaxResult;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;

/** No production service/authority bean is provided. Enabling the flag alone cannot grant access. */
@RestController
@RequestMapping("/business/city-board-drafts")
@ConditionalOnProperty(name = "questify.city-board-drafts.enabled", havingValue = "true", matchIfMissing = false)
public class CityBoardDraftController {
    private final CityBoardDraftService service;
    public CityBoardDraftController(CityBoardDraftService service) { this.service = service; }
    @PreAuthorize("@ss.hasPermi('business:cityBoardDraft:edit')")
    @GetMapping("/catalog") public AjaxResult catalog() { return invoke(service::catalog); }
    @PreAuthorize("@ss.hasPermi('business:cityBoardDraft:edit')")
    @GetMapping("/{draftId}") public AjaxResult reopen(@PathVariable String draftId) { return invoke(() -> service.reopen(draftId)); }
    @PreAuthorize("@ss.hasPermi('business:cityBoardDraft:edit')")
    @PostMapping("/validate") public AjaxResult validate(@RequestBody Map<String, Object> input) { return invoke(() -> service.validate(input)); }
    @PreAuthorize("@ss.hasPermi('business:cityBoardDraft:edit')")
    @PostMapping("/save") public AjaxResult save(@RequestBody Map<String, Object> input) { return invoke(() -> service.save(input)); }
    @PreAuthorize("@ss.hasPermi('business:cityBoardDraft:edit')")
    @PostMapping("/preview") public AjaxResult preview(@RequestBody Map<String, Object> input) { return invoke(() -> service.preview(input)); }
    private AjaxResult invoke(Supplier<Map<String, Object>> action) {
        try { return AjaxResult.success(action.get()); }
        catch (CityBoardDraftService.Failure e) { return new AjaxResult(e.status, e.getMessage()).put("errorCode", e.getMessage()); }
    }
}
