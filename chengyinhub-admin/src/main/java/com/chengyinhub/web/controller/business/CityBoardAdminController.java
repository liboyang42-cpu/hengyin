package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.citygame.CityBoardAdminService;
import com.chengyinhub.business.citygame.CityGameException;
import com.chengyinhub.business.citygame.RoamTerritoryService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.enums.BusinessType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Admin console endpoints for CITY boards (draft, dice preview, publish, freeze),
 * merchant-ops approval, fairness reports, and merged Roam territories.
 */
@RestController
@RequestMapping("/business/city-game")
public class CityBoardAdminController extends BaseController {
    private final CityBoardAdminService boardAdmin;
    private final RoamTerritoryService territories;

    public CityBoardAdminController(CityBoardAdminService boardAdmin, RoamTerritoryService territories) {
        this.boardAdmin = boardAdmin;
        this.territories = territories;
    }

    private Long adminUid() {
        try {
            return getUserId();
        } catch (Exception e) {
            return 1L;
        }
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/boards")
    public AjaxResult listBoards(@RequestParam(required = false) String regionId,
                                 @RequestParam(required = false) String status) {
        return success(boardAdmin.listBoards(regionId, status));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/boards/{boardId}")
    public AjaxResult getBoard(@PathVariable String boardId) {
        return success(boardAdmin.getBoardDetail(boardId));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @PostMapping("/boards/dice/preview")
    public AjaxResult previewDice(@RequestBody Map<String, Object> body) {
        return success(boardAdmin.previewDice(body));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "CITY棋盘草稿", businessType = BusinessType.UPDATE)
    @PostMapping("/boards/draft")
    public AjaxResult saveDraft(@RequestBody Map<String, Object> body) {
        return success(boardAdmin.saveDraft(adminUid(), body));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "CITY棋盘更新", businessType = BusinessType.UPDATE)
    @PutMapping("/boards/draft/{boardId}")
    public AjaxResult updateDraft(@PathVariable String boardId, @RequestBody Map<String, Object> body) {
        body.put("boardId", boardId);
        return success(boardAdmin.saveDraft(adminUid(), body));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "CITY棋盘发布", businessType = BusinessType.UPDATE)
    @PostMapping("/boards/{boardId}/publish")
    public AjaxResult publishBoard(@PathVariable String boardId) {
        return success(boardAdmin.publishBoard(adminUid(), boardId));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "CITY棋盘冻结", businessType = BusinessType.UPDATE)
    @PostMapping("/boards/{boardId}/freeze")
    public AjaxResult freezeBoard(@PathVariable String boardId) {
        return success(boardAdmin.freezeBoard(adminUid(), boardId));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/boards/{boardId}/audit")
    public AjaxResult boardAudit(@PathVariable String boardId, @RequestParam(required = false) Integer limit) {
        return success(boardAdmin.boardAudit(boardId, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/merchant-ops/queue")
    public AjaxResult merchantOpsQueue(@RequestParam(required = false) String status) {
        return success(boardAdmin.merchantOpsQueue(status));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "CITY商家运营审批", businessType = BusinessType.UPDATE)
    @PostMapping("/merchant-ops/review")
    public AjaxResult reviewMerchantOp(@RequestBody Map<String, Object> body) {
        return success(boardAdmin.reviewMerchantOp(adminUid(), body));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/fairness/report")
    public AjaxResult fairnessReport(@RequestParam(required = false) String boardId,
                                     @RequestParam(required = false) String regionId) {
        return success(boardAdmin.adminFairnessReport(boardId, regionId));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/territories")
    public AjaxResult listTerritories(@RequestParam(required = false) String boardId,
                                      @RequestParam(required = false) String seasonId,
                                      @RequestParam(required = false) String status,
                                      @RequestParam(required = false) Integer limit) {
        return success(territories.adminList(boardId, seasonId, status, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:edit')")
    @Log(title = "漫游领地治理", businessType = BusinessType.UPDATE)
    @PostMapping("/territories/{territoryId}/status")
    public AjaxResult updateTerritoryStatus(@PathVariable String territoryId, @RequestBody Map<String, Object> body) {
        String status = body.get("status") instanceof String ? (String) body.get("status") : null;
        Long toll = body.get("checkinTollPoints") instanceof Number ? ((Number) body.get("checkinTollPoints")).longValue() : null;
        Long rev = body.get("ownerRevenuePoints") instanceof Number ? ((Number) body.get("ownerRevenuePoints")).longValue() : null;
        String reason = body.get("reason") instanceof String ? (String) body.get("reason") : null;
        return success(territories.adminUpdateStatus(territoryId, status, toll, rev, adminUid(), reason));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/territories/audit")
    public AjaxResult territoryAudit(@RequestParam(required = false) String verdict,
                                     @RequestParam(required = false) Integer limit) {
        return success(territories.adminAuditList(verdict, limit));
    }

    @PreAuthorize("@ss.hasPermi('business:citygame:list')")
    @GetMapping("/territories/leaderboard")
    public AjaxResult territoryLeaderboard(@RequestParam(required = false) String boardId,
                                           @RequestParam(required = false) String regionId,
                                           @RequestParam(required = false) String seasonId,
                                           @RequestParam(required = false) Integer limit) {
        return success(territories.leaderboard(boardId, regionId, seasonId, limit));
    }

    @ExceptionHandler(CityGameException.class)
    public ResponseEntity<AjaxResult> failed(CityGameException e) {
        AjaxResult body = new AjaxResult(e.getStatus(), e.getMessage(), e.getData());
        body.put("errorCode", e.getErrorCode());
        return ResponseEntity.status(e.getStatus()).body(body);
    }
}
