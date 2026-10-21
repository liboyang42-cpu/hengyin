package com.chengyinhub.business.citygame.operations;

import com.chengyinhub.business.cityboard.CityBoardReleaseRow;
import com.chengyinhub.business.cityboard.mapper.CityBoardMapper;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IPointsAwardService;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExistingCityGameOperationsFactsTest {

    @Test
    void readsExistingSourcesAndDoesNotAward() {
        AtomicInteger runnerCalls = new AtomicInteger();
        CityBoardReleaseRow release = new CityBoardReleaseRow();
        release.setRulesReleaseId("rules-1");
        release.setContentHash("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef");
        release.setLifecycle("FROZEN");
        release.setTitle("Synthetic");
        MmsMerchant merchant = new MmsMerchant();
        merchant.setId(15L);
        merchant.setName("North Gate");
        merchant.setChargeType(3);
        RoamSession session = new RoamSession();
        session.setId(9L);
        session.setLastReportAt(new Date(0));
        ExistingCityGameOperationsFacts facts = new ExistingCityGameOperationsFacts(
                proxy(CityBoardMapper.class, (name, args) -> "selectCurrentRelease".equals(name) ? release : null),
                proxy(IMmsMerchantService.class, (name, args) -> "selectMmsMerchantById".equals(name) ? merchant : null),
                proxy(IUmsMemberPointsDetailService.class, (name, args) -> "selectUmsMemberPointsDetailList".equals(name)
                        ? List.of(new UmsMemberPointsDetail()) : null),
                proxy(RoamMapper.class, (name, args) -> {
                    if ("selectActivePresence".equals(name)) {
                        runnerCalls.incrementAndGet();
                        return session;
                    }
                    return null;
                }),
                new CityGameOperationsExecutionPolicy());

        CityGameOperationsTrace closed = CityGameOperationsTrace.capture(facts, "15", "city-1", "src-1");
        assertEquals(0, runnerCalls.get());
        assertTrue(closed.json.contains("NO_EVIDENCE_SOURCE"));
        assertTrue(closed.json.contains("NO_OWNERSHIP_SOURCE"));
        assertTrue(closed.json.contains("RUNNER_WINDOW_UNCONFIGURED"));
        assertTrue(closed.json.contains("North Gate"));
        assertFalse(closed.json.contains("chargeType"));
        assertFalse(closed.json.contains("\"fulfilled\""));
        assertTrue(closed.json.contains("rules-1"));
        assertTrue(closed.json.contains("detailRows"));

        ExistingCityGameOperationsFacts openWindow = new ExistingCityGameOperationsFacts(
                proxy(CityBoardMapper.class, (name, args) -> null),
                proxy(IMmsMerchantService.class, (name, args) -> null),
                proxy(IUmsMemberPointsDetailService.class, (name, args) -> null),
                proxy(RoamMapper.class, (name, args) -> {
                    if ("selectActivePresence".equals(name)) {
                        runnerCalls.incrementAndGet();
                        return session;
                    }
                    return null;
                }),
                new CityGameOperationsExecutionPolicy(false, 1000L));
        CityGameOperationsFactPort.Slot timeline = openWindow.read("timeline", "15", "city-1");
        assertEquals(1, runnerCalls.get());
        assertFalse(timeline.missing);
        assertFalse(timeline.value.containsKey("xpAwarded"));

        AwardProbe awards = new AwardProbe();
        CityGameOperationsAssetExecution disabled = new CityGameOperationsAssetExecution(awards, new CityGameOperationsExecutionPolicy());
        assertEquals("ASSET_EXECUTION_DISABLED", disabled.execute("COMPENSATE").blocker);
        CityGameOperationsAssetExecution enabled = new CityGameOperationsAssetExecution(awards, new CityGameOperationsExecutionPolicy(true, null));
        assertEquals("ASSET_INSTRUCTION_NOT_ACCEPTED", enabled.execute("COMPENSATE").blocker);
        assertEquals(0, awards.calls);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Call call) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> type.getName();
                };
            }
            Object value = call.get(method.getName(), args);
            if (value == null && method.getReturnType() == int.class) {
                return 0;
            }
            if (value == null && method.getReturnType() == boolean.class) {
                return false;
            }
            if (value == null && method.getReturnType() == long.class) {
                return 0L;
            }
            return value;
        });
    }

    private interface Call {
        Object get(String name, Object[] args);
    }

    private static final class AwardProbe implements IPointsAwardService {
        int calls;

        @Override
        public int award(Long memberId, Long eventType, Long eventId, String reason) {
            calls++;
            return 0;
        }

        @Override
        public int awardPublisherSettlement(Long memberId, Long topicId) {
            calls++;
            return 0;
        }
    }
}
