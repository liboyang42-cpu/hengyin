package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.cityboard.CityBoardDraftDocument;
import com.chengyinhub.business.cityboard.CityBoardMemoryMapper;
import com.chengyinhub.business.cityboard.CityBoardPublicationService;
import com.chengyinhub.business.cityboard.CityBoardSourceProbe;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ApiCityGameReadControllerTest {

    private CityBoardMemoryMapper mapper;
    private CityBoardPublicationService service;
    private ApiCityGameReadController controller;

    @BeforeEach
    void setUp() {
        mapper = new CityBoardMemoryMapper();
        service = new CityBoardPublicationService(mapper, new CityBoardSourceProbe());
        controller = new ApiCityGameReadController(service);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousAndAdminPrincipalsAreUnauthorized() {
        ResponseEntity<AjaxResult> anonymous = controller.current(Map.of("regionId", "city-1"));
        assertEquals(401, anonymous.getStatusCode().value());
        assertEquals(401, anonymous.getBody().get(AjaxResult.CODE_TAG));
        assertEquals("UNAUTHORIZED", anonymous.getBody().get("errorCode"));

        SysUser user = new SysUser();
        user.setUserName("staff");
        user.setPassword("ignored");
        LoginUser loginUser = new LoginUser(7L, 1L, user, new HashSet<String>());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(loginUser, null, Collections.emptyList()));
        assertEquals(401, controller.current(Map.of("regionId", "city-1")).getStatusCode().value());
    }

    @Test
    void playerReadsNoCurrentBoardUntilTheSameFrozenReleaseExists() throws Exception {
        loginPlayer(7L);
        ResponseEntity<AjaxResult> missing = controller.current(Map.of("regionId", "city-1"));
        assertEquals(200, missing.getStatusCode().value());
        Map<?, ?> missingData = data(missing);
        assertEquals("NO_CURRENT_BOARD", missingData.get("status"));
        assertEquals("CITY_PLAYER_READ_V1", missingData.get("contract"));
        assertEquals("CITY", missingData.get("scope"));
        assertNull(missingData.get("board"));
        assertNull(missingData.get("participationStatus"));
        assertNull(missingData.get("points"));
        assertEquals("NOT_APPROVED", missingData.get("transport"));
        assertEquals(Boolean.TRUE, missing.getBody().get("candidate"));
        String missingJson = CityBoardDraftDocument.mapper().writeValueAsString(missingData);
        assertFalse(missingJson.contains("JOINED"));

        JsonNode saved = json("{\"requestId\":\"save-1\",\"expectedVersion\":0,\"document\":" + doc() + "}");
        long draftId = ((Number) service.save(7L, saved).data.get("draftId")).longValue();
        service.validate(8L, json("{\"requestId\":\"approve-1\",\"draftId\":" + draftId + ",\"expectedVersion\":1}"));
        service.publish(9L, json("{\"requestId\":\"pub-1\",\"draftId\":" + draftId + ",\"expectedVersion\":1}"));

        ResponseEntity<AjaxResult> current = controller.current(Map.of("regionId", "city-1"));
        Map<?, ?> currentData = data(current);
        Map<?, ?> board = (Map<?, ?>) currentData.get("board");
        assertEquals("AVAILABLE", currentData.get("status"));
        assertEquals("UNAVAILABLE", currentData.get("participationStatus"));
        assertEquals("UNAVAILABLE", currentData.get("pointsStatus"));
        assertEquals("FROZEN", board.get("lifecycle"));
        assertEquals(64, board.get("rulesHash").toString().length());
        assertNull(currentData.get("participation"));
        assertNull(currentData.get("points"));

        ResponseEntity<AjaxResult> points = controller.points(Map.of(
                "regionId", "city-1",
                "boardId", String.valueOf(board.get("boardId")),
                "seasonId", String.valueOf(board.get("seasonId")),
                "rulesReleaseId", String.valueOf(board.get("rulesReleaseId")),
                "revision", String.valueOf(board.get("revision"))));
        assertEquals(503, points.getStatusCode().value());
        assertEquals(503, points.getBody().get(AjaxResult.CODE_TAG));
        assertEquals("CITY_POINTS_UNAVAILABLE", points.getBody().get("errorCode"));
        assertNull(points.getBody().get(AjaxResult.DATA_TAG));
        assertEquals(1, mapper.releaseCount());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ResponseEntity<AjaxResult> response) {
        return (Map<String, Object>) response.getBody().get(AjaxResult.DATA_TAG);
    }

    private static JsonNode json(String text) throws Exception {
        return CityBoardDraftDocument.mapper().readTree(text);
    }

    private static void loginPlayer(long memberId) {
        AppUser appUser = new AppUser();
        appUser.setUserId(memberId);
        appUser.setUserName("player");
        LoginAppUser principal = new LoginAppUser(memberId, appUser);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, Collections.emptyList()));
    }

    private static String doc() {
        return "{"
                + "\"title\": \"Synthetic CITY\",\"regionId\": \"city-1\",\"gameId\": \"game-1\",\"boardId\": \"board-1\","
                + "\"season\": {\"seasonId\": \"season-1\", \"budget\": {\"currency\": \"CNY\", \"amountMinor\": 10000}},"
                + "\"seasonSettlement\": {\"budgetCurrency\": \"CNY\", \"budgetAmountMinor\": 10000},"
                + "\"rules\": {\"rulesId\": \"rules-1\", \"text\": \"Walk every point.\"},"
                + "\"dice\": {\"diceId\": \"dice-1\", \"faces\": [1, 2, 3, 4]},"
                + "\"pointCost\": {\"unit\": \"POINT\", \"amount\": 0},"
                + "\"serviceFee\": {\"currency\": \"CNY\", \"amountMinor\": 100},"
                + "\"protection\": {\"challengeId\": \"protect-1\", \"text\": \"Stay on marked path\"},"
                + "\"safety\": {\"statement\": \"Operator confirmed the board text\", \"confirmed\": true},"
                + "\"pointRoles\": [{\"roleId\": \"walker\", \"title\": \"Walker\"}],"
                + "\"points\": [{\"pointId\": \"point-1\", \"title\": \"Gate\", \"latitude\": 31.2, \"longitude\": 121.5, \"roleId\": \"walker\", \"domain\": \"PLATFORM_VIRTUAL\"}],"
                + "\"tasks\": [{\"taskId\": \"task-1\", \"title\": \"Visit gate\", \"pointId\": \"point-1\", \"steps\": [\"Arrive\"]}],"
                + "\"locationPermits\": [],"
                + "\"permissionMatrix\": [{\"permission\": \"cityboard:player:read\", \"scope\": \"region:city-1\"}]"
                + "}";
    }
}
