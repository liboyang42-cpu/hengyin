package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.domain.entity.SysRole;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CityGameOperationsControllerTest {

    private AnnotationConfigApplicationContext context;
    private CityGameOperationsController controller;
    private CityGameOperationsMemoryMapper mapper;

    @BeforeEach
    void setUp() {
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(new MockHttpServletRequest()));
        context = new AnnotationConfigApplicationContext(Config.class);
        controller = context.getBean(CityGameOperationsController.class);
        mapper = context.getBean(CityGameOperationsMemoryMapper.class);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    void unrelatedPermissionHasZeroActions() {
        login(4L, "stranger", "system:config:edit");
        assertThrows(AccessDeniedException.class, () -> controller.trace(json(trace("tr-1"))));
        assertThrows(AccessDeniedException.class, () -> controller.propose(json("{}")));
        assertThrows(AccessDeniedException.class, () -> controller.apply(json("{}")));
        assertEquals(0, mapper.caseCount());
        assertEquals(0, mapper.requestCount());
        assertEquals(0, mapper.auditCount());
        assertEquals(0, mapper.storeCount());
    }

    @Test
    void operatorPauseIsRoleGatedAndReadable() throws Exception {
        login(7L, "city-operator", CityGameOperationsPermissions.TRACE, CityGameOperationsPermissions.PROPOSE);
        ResponseEntity<AjaxResult> traced = controller.trace(json(trace("tr-1")));
        assertEquals(200, traced.getStatusCode().value());
        assertEquals("city-ops", traced.getHeaders().getFirst("X-Chengyin-Candidate"));
        long caseId = ((Number) map(traced).get("caseId")).longValue();
        assertEquals("city-operator", map(traced).get("roleKeys"));

        ResponseEntity<AjaxResult> clientApproval = controller.propose(json(
                "{\"requestId\":\"pr-bad\",\"expectedVersion\":1,\"caseId\":" + caseId + ",\"approved\":true}"));
        assertEquals(400, clientApproval.getStatusCode().value());
        assertEquals("CLIENT_APPROVAL_REJECTED", clientApproval.getBody().get("errorCode"));
        assertEquals(0, mapper.storeCount());

        assertEquals(200, controller.propose(json(pause("pr-1", caseId, 1))).getStatusCode().value());
        login(8L, "city-reviewer", CityGameOperationsPermissions.REVIEW, CityGameOperationsPermissions.APPLY,
                CityGameOperationsPermissions.QUERY);
        assertEquals(200, controller.review(json(review("rv-1", caseId, 2))).getStatusCode().value());
        ResponseEntity<AjaxResult> applied = controller.apply(json(apply("ap-1", caseId, 3)));
        assertEquals(200, applied.getStatusCode().value());
        assertEquals("PAUSE_MERCHANT", map(applied).get("effectiveState"));
        assertEquals("PAUSED", mapper.selectStore("m-1").getFactStatus());
        assertEquals(null, mapper.selectStore("m-2"));

        ResponseEntity<AjaxResult> receipt = controller.readRequest("ap-1");
        assertEquals(200, receipt.getStatusCode().value());
        assertEquals("city-reviewer", map(receipt).get("roleKeys"));
        assertEquals(Boolean.TRUE, receipt.getBody().get("candidate"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(ResponseEntity<AjaxResult> response) {
        return (Map<String, Object>) response.getBody().get(AjaxResult.DATA_TAG);
    }

    private static JsonNode json(String text) throws Exception {
        return CityGameOperationsTexts.mapper().readTree(text);
    }

    private static String trace(String requestId) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":0,\"actionId\":\"act-1\",\"regionId\":\"city-1\",\"sourceRequestId\":\"src-1\"}";
    }

    private static String pause(String requestId, long caseId, long version) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version + ",\"caseId\":" + caseId
                + ",\"reason\":\"operator reason\",\"originalRequestId\":\"src-1\",\"objectType\":\"MERCHANT_STORE\","
                + "\"objectId\":\"m-1\",\"ownerUserId\":3,\"objectRole\":\"merchant-operator\",\"kind\":\"PAUSE_MERCHANT\","
                + "\"merchantId\":\"m-1\"}";
    }

    private static String review(String requestId, long caseId, long version) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version + ",\"caseId\":" + caseId
                + ",\"reason\":\"human check\",\"decision\":\"APPROVE\"}";
    }

    private static String apply(String requestId, long caseId, long version) {
        return "{\"requestId\":\"" + requestId + "\",\"expectedVersion\":" + version + ",\"caseId\":" + caseId + "}";
    }

    private static void login(long userId, String roleKey, String... permissions) {
        SysUser user = new SysUser();
        user.setUserName("city-ops");
        user.setPassword("ignored");
        SysRole role = new SysRole();
        role.setRoleKey(roleKey);
        user.setRoles(List.of(role));
        LoginUser loginUser = new LoginUser(userId, 1L, user, new HashSet<String>(java.util.Arrays.asList(permissions)));
        org.springframework.security.core.context.SecurityContext securityContext = SecurityContextHolder.createEmptyContext();
        securityContext.setAuthentication(new UsernamePasswordAuthenticationToken(loginUser, null, java.util.Collections.emptyList()));
        SecurityContextHolder.setContext(securityContext);
    }

    @Configuration
    @EnableMethodSecurity(prePostEnabled = true)
    static class Config {
        @Bean("ss")
        PermissionService permissionService() {
            return new PermissionService();
        }

        @Bean
        CityGameOperationsMemoryMapper cityGameOperationsMapper() {
            return new CityGameOperationsMemoryMapper();
        }

        @Bean
        CityGameOperationsFactPort facts() {
            CityGameOperationsFactPort.Slot ownership = CityGameOperationsFactPort.Slot.present(Map.of(
                    "ownerUserId", 3L, "objectRole", "merchant-operator", "sourceRequestId", "src-1"));
            CityGameOperationsFactPort.Slot place = CityGameOperationsFactPort.Slot.present(Map.of("merchantId", "m-1", "label", "North"));
            return (slot, actionId, regionId) -> {
                if ("ownership".equals(slot)) {
                    return ownership;
                }
                if ("placeFacts".equals(slot)) {
                    return place;
                }
                return CityGameOperationsFactPort.Slot.missing("NO_EVIDENCE_SOURCE");
            };
        }

        @Bean
        AwardProbe awards() {
            return new AwardProbe();
        }

        @Bean
        CityGameOperationsExecutionPolicy policy() {
            return new CityGameOperationsExecutionPolicy();
        }

        @Bean
        CityGameOperationsAssetExecution assets(AwardProbe awards, CityGameOperationsExecutionPolicy policy) {
            return new CityGameOperationsAssetExecution(awards, policy);
        }

        @Bean
        LoginCityGameOperationsPermissionPort permissions(PermissionService permissions) {
            return new LoginCityGameOperationsPermissionPort(permissions);
        }

        @Bean
        CityGameOperationsService operations(CityGameOperationsMemoryMapper mapper, CityGameOperationsFactPort facts,
                LoginCityGameOperationsPermissionPort permissions, CityGameOperationsAssetExecution assets) {
            return new CityGameOperationsService(mapper, facts, permissions, assets, List.of(
                    new CityGameOperationsCompensateCommand(), new CityGameOperationsRevokeCommand(),
                    new CityGameOperationsDisableCommand(), new CityGameOperationsRestoreCommand(),
                    new CityGameOperationsCorrectCommand()));
        }

        @Bean
        CityGameOperationsController controller(CityGameOperationsService operations) {
            return new CityGameOperationsController(operations);
        }
    }

    static class AwardProbe implements com.chengyinhub.business.service.IPointsAwardService {
        @Override
        public int award(Long memberId, Long eventType, Long eventId, String reason) {
            return 0;
        }

        @Override
        public int awardPublisherSettlement(Long memberId, Long topicId) {
            return 0;
        }
    }
}
