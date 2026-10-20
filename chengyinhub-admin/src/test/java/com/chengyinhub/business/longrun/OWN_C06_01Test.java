package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.GameCommandReceipt;
import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.mapper.GameSessionRuntimeMapper;
import com.chengyinhub.business.service.GameSessionException;
import com.chengyinhub.business.service.impl.GameSessionRuntimeServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SOURCE_ONLY regression; NOT_RUN. No providers or database. */
class OWN_C06_01Test {
    private GameSessionCommand command(String action, Map<String, Object> payload) {
        GameSessionCommand c = new GameSessionCommand();
        c.setActivityId(10L); c.setNodeId(20L); c.setExpectedRevision(3);
        c.setRequestId("original-request"); c.setAction(action); c.setPayload(payload);
        return c;
    }
    private Map<String, Object> payload(boolean reverse) {
        Map<String, Object> nested = new LinkedHashMap<>();
        if (reverse) { nested.put("b", 2); nested.put("a", 1); }
        else { nested.put("a", 1); nested.put("b", 2); }
        return Collections.<String, Object>singletonMap("nested", nested);
    }
    @Test void originalReceiptReplaysAcrossLocaleCaseAndNestedObjectOrder() {
        Locale original = Locale.getDefault();
        try {
            GameSessionRuntimeServiceImpl service = new GameSessionRuntimeServiceImpl();
            GameSessionRuntimeMapper mapper = mock(GameSessionRuntimeMapper.class);
            ReflectionTestUtils.setField(service, "runtimeMapper", mapper);
            Locale.setDefault(Locale.ENGLISH);
            String hash = ReflectionTestUtils.invokeMethod(service, "requestHash", command("FINISH", payload(false)));
            GameCommandReceipt receipt = new GameCommandReceipt();
            receipt.setId(1L); receipt.setActivityId(10L); receipt.setRequestId("original-request");
            receipt.setAction("FINISH"); receipt.setRequestHash(hash); receipt.setOutcome("APPLIED");
            receipt.setResultJson("{}"); receipt.setRevision(4);
            when(mapper.selectReceipt(10L, 7L, "original-request")).thenReturn(receipt);
            Locale.setDefault(new Locale("tr", "TR"));
            GameSessionCommand replay = command(" finish ", payload(true));
            assertTrue(service.command(GameActorContext.club(7L), replay).isReplayed());
            assertEquals("FINISH", replay.getAction());
            GameSessionException conflict = assertThrows(GameSessionException.class,
                    () -> service.command(GameActorContext.club(7L), command("START", payload(true))));
            assertEquals("GAME_IDEMPOTENCY_CONFLICT", conflict.getErrorCode());
            verify(mapper, never()).insertReceiptClaim(any());
        } finally { Locale.setDefault(original); }
    }
    @Test void arrayOrderAndPayloadValueRemainPartOfTheFingerprint() {
        GameSessionRuntimeServiceImpl service = new GameSessionRuntimeServiceImpl();
        String first = ReflectionTestUtils.invokeMethod(service, "requestHash",
                command("FINISH", Collections.<String, Object>singletonMap("ids", Arrays.asList(1, 2))));
        String reversed = ReflectionTestUtils.invokeMethod(service, "requestHash",
                command("finish", Collections.<String, Object>singletonMap("ids", Arrays.asList(2, 1))));
        assertNotEquals(first, reversed);
    }
}
