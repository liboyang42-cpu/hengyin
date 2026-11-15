package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.citygame.CityMonopolyService;
import com.chengyinhub.business.citygame.RoamTerritoryService;
import com.chengyinhub.business.service.impl.RoamLocationConsentService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * HIGH-S8：领地与城市大富翁中接收 lat/lng 的写命令，必须先在同一事务里过漫游定位授权闸，
 * 未同意时服务层不被调用、不落任何坐标。
 */
class CityLocationConsentGateTest {

    private static final long MEMBER = 42L;
    private static final Map<String, Object> BODY = Map.of("lat", 31.2, "lng", 121.4);

    @Test
    void territoryCommandsRequireConsentBeforeService() throws Exception {
        RoamTerritoryService service = mock(RoamTerritoryService.class);
        RoamLocationConsentService consent = mock(RoamLocationConsentService.class);
        ApiRoamTerritoryController controller = spy(new ApiRoamTerritoryController(service, consent));
        doReturn(MEMBER).when(controller).getAppUserId();

        controller.claim(BODY);
        controller.checkin(BODY);
        InOrder order = inOrder(consent, service);
        order.verify(consent).requireCurrentAgreement(MEMBER);
        order.verify(service).claim(MEMBER, BODY);
        order.verify(consent).requireCurrentAgreement(MEMBER);
        order.verify(service).checkin(MEMBER, BODY);

        doThrow(new ServiceException("请先明确同意漫游定位")).when(consent).requireCurrentAgreement(MEMBER);
        clearInvocations(service);
        assertThrows(ServiceException.class, () -> controller.claim(BODY));
        assertThrows(ServiceException.class, () -> controller.checkin(BODY));
        verifyNoInteractions(service);

        assertTransactional(ApiRoamTerritoryController.class, "claim", "checkin");
    }

    @Test
    void monopolyCommandsWithEvidenceRequireConsentBeforeService() throws Exception {
        CityMonopolyService service = mock(CityMonopolyService.class);
        RoamLocationConsentService consent = mock(RoamLocationConsentService.class);
        ApiCityMonopolyController controller = spy(new ApiCityMonopolyController(service, consent));
        doReturn(MEMBER).when(controller).getAppUserId();
        List<Consumer<ApiCityMonopolyController>> commands = List.of(
                c -> c.claimCommand(BODY), c -> c.upgradeCommand(BODY), c -> c.challengeCommand(BODY));

        for (Consumer<ApiCityMonopolyController> command : commands) command.accept(controller);
        verify(consent, times(3)).requireCurrentAgreement(MEMBER);
        verify(service).claimCommand(MEMBER, BODY);
        verify(service).upgradeCommand(MEMBER, BODY);
        verify(service).challengeCommand(MEMBER, BODY);

        doThrow(new ServiceException("请先明确同意漫游定位")).when(consent).requireCurrentAgreement(MEMBER);
        clearInvocations(service);
        for (Consumer<ApiCityMonopolyController> command : commands) {
            assertThrows(ServiceException.class, () -> command.accept(controller));
        }
        verify(service, never()).claimCommand(any(), any());
        verify(service, never()).upgradeCommand(any(), any());
        verify(service, never()).challengeCommand(any(), any());

        assertTransactional(ApiCityMonopolyController.class, "claimCommand", "upgradeCommand", "challengeCommand");
    }

    /** requireCurrentAgreement 只能在原事务里加会员锁，因此控制器入口必须开启事务。 */
    private static void assertTransactional(Class<?> type, String... methods) throws Exception {
        for (String name : methods) {
            assertNotNull(type.getMethod(name, Map.class).getAnnotation(Transactional.class), name);
        }
    }
}
