package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.cityboard.CityBoardOutcome;
import com.chengyinhub.business.cityboard.CityBoardPublicationService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * CITY_PLAYER_READ_V1 候选只读。未接入 SecurityConfig，不批准 transport，不开放匿名。
 * 无当前发布返回 NO_CURRENT_BOARD。参与和点位真源不在本组，调用时返回 UNAVAILABLE，不编造成员。
 */
@RestController
@RequestMapping("/api/city-game")
public class ApiCityGameReadController extends BaseController {

    private final CityBoardPublicationService publicationService;

    public ApiCityGameReadController(CityBoardPublicationService publicationService) {
        this.publicationService = publicationService;
    }

    @GetMapping("/current")
    public ResponseEntity<AjaxResult> current(@RequestParam Map<String, String> query) {
        Long memberId = appMember();
        if (memberId == null) {
            return respond(CityBoardOutcome.fail(401, "UNAUTHORIZED", "请先登录", null));
        }
        return respond(publicationService.playerCurrent(memberId, query));
    }

    @GetMapping("/participation")
    public ResponseEntity<AjaxResult> participation(@RequestParam Map<String, String> query) {
        Long memberId = appMember();
        if (memberId == null) {
            return respond(CityBoardOutcome.fail(401, "UNAUTHORIZED", "请先登录", null));
        }
        return respond(publicationService.playerParticipation(memberId, query));
    }

    @GetMapping("/points")
    public ResponseEntity<AjaxResult> points(@RequestParam Map<String, String> query) {
        Long memberId = appMember();
        if (memberId == null) {
            return respond(CityBoardOutcome.fail(401, "UNAUTHORIZED", "请先登录", null));
        }
        return respond(publicationService.playerPoints(memberId, query));
    }

    private Long appMember() {
        try {
            Long memberId = getAppUserId();
            return memberId == null || memberId <= 0 ? null : memberId;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static ResponseEntity<AjaxResult> respond(CityBoardOutcome outcome) {
        AjaxResult body = outcome.httpStatus == 200
                ? AjaxResult.success(outcome.data)
                : AjaxResult.error(outcome.httpStatus, outcome.message);
        if (outcome.httpStatus != 200 && outcome.data != null) {
            body.put(AjaxResult.DATA_TAG, outcome.data);
        }
        if (outcome.errorCode != null) {
            body.put("errorCode", outcome.errorCode);
        }
        body.put("candidate", Boolean.TRUE);
        return ResponseEntity.status(outcome.httpStatus).header("X-Chengyin-Candidate", "city-board").body(body);
    }
}
