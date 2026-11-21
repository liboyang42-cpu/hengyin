package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.gamemech.CreatorVerificationService;
import com.chengyinhub.business.gamemech.TreasureHuntService;
import com.chengyinhub.business.gamemech.WalkTestService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.function.Supplier;

/** G2 admin console: treasure hunt authoring and review, walk-test records and waivers, verified creators. */
@RestController
public class GameMechanicsAdminController extends BaseController {
    @Autowired
    private TreasureHuntService treasure;
    @Autowired
    private WalkTestService walkTests;
    @Autowired
    private CreatorVerificationService creators;

    private AjaxResult run(Supplier<Object> body) {
        try {
            return success(body.get());
        } catch (ServiceException e) {
            return error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:treasureHunt:list')")
    @GetMapping("/business/treasureHunt/list")
    public AjaxResult huntList(Integer status) {
        return run(() -> treasure.adminList(status));
    }

    @PreAuthorize("@ss.hasPermi('business:treasureHunt:list')")
    @GetMapping("/business/treasureHunt/{id}")
    public AjaxResult huntDetail(@PathVariable("id") Long id) {
        return run(() -> treasure.adminDetail(id));
    }

    @PreAuthorize("@ss.hasPermi('business:treasureHunt:edit')")
    @Log(title = "寻宝", businessType = BusinessType.UPDATE)
    @PostMapping("/business/treasureHunt")
    public AjaxResult huntSave(@RequestBody Map<String, Object> body) {
        return run(() -> treasure.adminSave(body, getUsername()));
    }

    /**
     * W7 GAME-16: putting a hunt live is its own permission, apart from editing it (granted to no role by the
     * migration). The literal path wins over {@code {decision}} below.
     */
    @PreAuthorize("@ss.hasPermi('business:treasureHunt:approve')")
    @Log(title = "寻宝审核", businessType = BusinessType.UPDATE)
    @PutMapping("/business/treasureHunt/{id}/approve")
    public AjaxResult huntApprove(@PathVariable("id") Long id, @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null || body.get("note") == null ? null : body.get("note").toString();
        return run(() -> { treasure.adminDecide(id, "approve", note, getUsername()); return treasure.adminDetail(id); });
    }

    /** decision: reject (退回,须写原因) / end (结束). Approve has its own route and permission above. */
    @PreAuthorize("@ss.hasPermi('business:treasureHunt:edit')")
    @Log(title = "寻宝审核", businessType = BusinessType.UPDATE)
    @PutMapping("/business/treasureHunt/{id}/{decision}")
    public AjaxResult huntDecide(@PathVariable("id") Long id, @PathVariable("decision") String decision,
                                 @RequestBody(required = false) Map<String, Object> body) {
        String note = body == null || body.get("note") == null ? null : body.get("note").toString();
        if ("approve".equals(decision)) return AjaxResult.error(403, "上线需要单独的审核权限");
        return run(() -> { treasure.adminDecide(id, decision, note, getUsername()); return treasure.adminDetail(id); });
    }

    @PreAuthorize("@ss.hasPermi('business:walkTest:list')")
    @GetMapping("/business/walkTest/list")
    public AjaxResult walkTestList() {
        return run(() -> Map.of("mode", walkTests.mode().name(), "rows", walkTests.adminSummary()));
    }

    @PreAuthorize("@ss.hasPermi('business:walkTest:list')")
    @GetMapping("/business/walkTest/{topicId}")
    public AjaxResult walkTestStatus(@PathVariable("topicId") Long topicId) {
        return run(() -> walkTests.status(topicId));
    }

    @PreAuthorize("@ss.hasPermi('business:walkTest:waive')")
    @Log(title = "试走豁免", businessType = BusinessType.UPDATE)
    @PutMapping("/business/walkTest/{topicId}/waive")
    public AjaxResult walkTestWaive(@PathVariable("topicId") Long topicId, @RequestBody Map<String, Object> body) {
        String note = body == null || body.get("note") == null ? null : body.get("note").toString();
        return run(() -> walkTests.waive(topicId, note, getUsername()));
    }

    @PreAuthorize("@ss.hasPermi('business:walkTest:waive')")
    @Log(title = "撤销试走豁免", businessType = BusinessType.UPDATE)
    @PutMapping("/business/walkTest/{topicId}/unwaive")
    public AjaxResult walkTestUnwaive(@PathVariable("topicId") Long topicId) {
        return run(() -> walkTests.unwaive(topicId));
    }

    /** Marks a player creator verified. Changes permissions only when creator.player.verified_unlock=true. */
    @PreAuthorize("@ss.hasPermi('business:member:edit')")
    @Log(title = "认证创作者", businessType = BusinessType.UPDATE)
    @PutMapping("/business/creatorVerify/{memberId}/{state}")
    public AjaxResult creatorVerify(@PathVariable("memberId") Long memberId, @PathVariable("state") String state) {
        return run(() -> creators.set(memberId, "on".equals(state), getUserId()));
    }
}
