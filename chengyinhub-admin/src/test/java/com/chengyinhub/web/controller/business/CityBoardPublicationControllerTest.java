package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.cityboard.CityBoardDraftDocument;
import com.chengyinhub.business.cityboard.CityBoardMemoryMapper;
import com.chengyinhub.business.cityboard.CityBoardPermissions;
import com.chengyinhub.business.cityboard.CityBoardPublicationService;
import com.chengyinhub.business.cityboard.CityBoardSourceProbe;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysUser;
import com.chengyinhub.common.core.domain.model.LoginUser;
import com.chengyinhub.framework.web.service.PermissionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CityBoardPublicationControllerTest {

    private AnnotationConfigApplicationContext context;
    private CityBoardPublicationController controller;
    private CityBoardMemoryMapper mapper;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(CityBoardPublicationController.class);
        mapper = context.getBean(CityBoardMemoryMapper.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void unrelatedPermissionCannotSaveOrPublish() throws Exception {
        login(7L, "system:config:edit");
        assertThrows(AccessDeniedException.class, () -> controller.save(json(saveBody("save-1", 0, null))));
        assertThrows(AccessDeniedException.class, () -> controller.publish(json(draftBody("pub-1", 1, 1))));
        assertEquals(0, mapper.draftCount());
        assertEquals(0, mapper.releaseCount());
    }

    @Test
    void permissionsFollowTheActorAndClientApprovalIsNotAccepted() throws Exception {
        login(7L, CityBoardPermissions.DRAFT_SAVE);
        ResponseEntity<AjaxResult> saved = controller.save(json(saveBody("save-1", 0, null)));
        assertEquals(200, saved.getStatusCode().value());
        long draftId = ((Number) map(saved).get("draftId")).longValue();
        assertEquals(7L, ((Number) map(saved).get("ownerUserId")).longValue());
        assertEquals(Boolean.TRUE, saved.getBody().get("candidate"));
        assertThrows(AccessDeniedException.class, () -> controller.validate(json(draftBody("approve-1", draftId, 1))));

        login(8L, CityBoardPermissions.REVIEW_VALIDATE);
        ResponseEntity<AjaxResult> approved = controller.validate(json(draftBody("approve-1", draftId, 1)));
        assertEquals(200, approved.getStatusCode().value());
        assertEquals(8L, ((Number) map(approved).get("approverUserId")).longValue());

        login(9L, CityBoardPermissions.PUBLISH);
        ResponseEntity<AjaxResult> rejected = controller.publish(json(
                "{\"requestId\":\"pub-client\",\"draftId\":" + draftId + ",\"expectedVersion\":1,\"approvalHash\":\"abc\"}"));
        assertEquals(400, rejected.getStatusCode().value());
        assertEquals("CLIENT_APPROVAL_REJECTED", rejected.getBody().get("errorCode"));
        assertEquals(0, mapper.releaseCount());

        ResponseEntity<AjaxResult> published = controller.publish(json(draftBody("pub-1", draftId, 1)));
        assertEquals(200, published.getStatusCode().value());
        assertEquals("city-board", published.getHeaders().getFirst("X-Chengyin-Candidate"));
        assertEquals(1, mapper.releaseCount());

        login(7L, CityBoardPermissions.DRAFT_SAVE);
        assertThrows(AccessDeniedException.class, () -> controller.operation("pub-1"));
        login(4L, CityBoardPermissions.OPERATION_QUERY);
        ResponseEntity<AjaxResult> receipt = controller.operation("pub-1");
        assertEquals(200, receipt.getStatusCode().value());
        assertEquals(map(published).get("contentHash"), map(receipt).get("contentHash"));
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> map(ResponseEntity<AjaxResult> response) {
        return (java.util.Map<String, Object>) response.getBody().get(AjaxResult.DATA_TAG);
    }

    private static JsonNode json(String text) throws Exception {
        return CityBoardDraftDocument.mapper().readTree(text);
    }

    private static String saveBody(String requestId, long version, Long draftId) {
        String draft = draftId == null ? "" : ",\"draftId\":" + draftId;
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version + draft + ",\"document\":" + doc() + "}";
    }

    private static String draftBody(String requestId, long draftId, long version) {
        return "{\"requestId\":\"" + requestId + "\",\"draftId\":" + draftId + ",\"expectedVersion\":" + version + "}";
    }

    private static void login(long userId, String... permissions) {
        SysUser user = new SysUser();
        user.setUserName("city-board");
        user.setPassword("ignored");
        LoginUser loginUser = new LoginUser(userId, 1L, user, new HashSet<String>(java.util.Arrays.asList(permissions)));
        SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null, java.util.Collections.emptyList()));
        SecurityContextHolder.setContext(securityContext);
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

    @Configuration
    @EnableMethodSecurity(prePostEnabled = true)
    static class Config {
        @Bean("ss")
        PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        CityBoardMemoryMapper cityBoardMapper() {
            return new CityBoardMemoryMapper();
        }

        @Bean
        CityBoardPublicationService cityBoardPublicationService(CityBoardMemoryMapper mapper) {
            return new CityBoardPublicationService(mapper, new CityBoardSourceProbe());
        }

        @Bean
        CityBoardPublicationController cityBoardPublicationController(CityBoardPublicationService service, PermissionService permissions) {
            return new CityBoardPublicationController(service, permissions);
        }
    }
}
