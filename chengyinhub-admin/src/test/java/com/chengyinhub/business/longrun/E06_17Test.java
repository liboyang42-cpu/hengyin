package com.chengyinhub.business.longrun;

import com.chengyinhub.business.mapper.RoamShareSnapshotMapper;
import com.chengyinhub.business.service.support.RoamSharePrivacyService;
import com.chengyinhub.common.utils.UserSafeMessage;
import com.chengyinhub.framework.web.exception.GlobalExceptionHandler;
import com.chengyinhub.web.controller.api.ApiRoamShareController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.sql.SQLTimeoutException;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** NOT_RUN. Real Spring MVC exception resolution, production global advice and privacy normalizer. */
class E06_17Test {
    private static final String TOKEN = "0123456789abcdef0123456789abcdef";
    private static final String PATH = "/api/roam/share/snapshot";
    private static final String GONE = "这条足迹已不再公开";
    private RoamShareSnapshotMapper mapper;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mapper = mock(RoamShareSnapshotMapper.class);
        ApiRoamShareController controller = new ApiRoamShareController();
        ReflectionTestUtils.setField(controller, "snapshotMapper", mapper);
        ReflectionTestUtils.setField(controller, "privacyService", new RoamSharePrivacyService());
        mvc = MockMvcBuilders.standaloneSetup(controller, new OtherController())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void queryTimeoutUsesHttp503WithExistingErrorShapeAndKeepsCauseOutOfResponse() throws Exception {
        SQLTimeoutException databaseCause = new SQLTimeoutException("private-db-host password=secret");
        QueryTimeoutException failure = new QueryTimeoutException(
                "SELECT snapshot_json FROM roam_share_snapshot WHERE share_token='" + TOKEN + "'", databaseCause);
        when(mapper.selectSnapshotJsonByToken(TOKEN)).thenThrow(failure);
        MvcResult result = mvc.perform(get(PATH).param("token", TOKEN))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(UserSafeMessage.GENERIC))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(not(containsString(GONE))))
                .andExpect(content().string(not(containsString(TOKEN))))
                .andExpect(content().string(not(containsString("SELECT"))))
                .andExpect(content().string(not(containsString("private-db-host"))))
                .andExpect(content().string(not(containsString("secret"))))
                .andReturn();
        assertNotNull(result.getResolvedException());
        assertSame(failure, result.getResolvedException().getCause());
        assertSame(databaseCause, result.getResolvedException().getCause().getCause());
        verify(mapper).selectSnapshotJsonByToken(TOKEN);
    }

    @Test
    void malformedAndMissingTokensKeepExistingHttp200GoneAndMalformedTokenNeverQueries() throws Exception {
        mvc.perform(get(PATH).param("token", "not-a-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(GONE));
        verifyNoInteractions(mapper);
        mvc.perform(get(PATH).param("token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(GONE));
        verify(mapper).selectSnapshotJsonByToken(TOKEN);
    }

    @Test
    void corruptAndPrivacyInvalidStoredSnapshotsKeepHttp200GoneWithoutPayloadLeak() throws Exception {
        when(mapper.selectSnapshotJsonByToken(TOKEN)).thenReturn("not json private-payload",
                "{\"ts\":1700000000000,\"privateKey\":\"private-payload\",\"track\":[{\"lat\":91,\"lng\":1}]}");
        for (int i = 0; i < 2; i++) {
            mvc.perform(get(PATH).param("token", TOKEN))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.msg").value(GONE))
                    .andExpect(jsonPath("$.data").doesNotExist())
                    .andExpect(content().string(not(containsString("private-payload"))));
        }
    }

    @Test
    void successfulAnonymousReadStillAppliesRealCoordinateAndIdentityWhitelist() throws Exception {
        when(mapper.selectSnapshotJsonByToken(TOKEN)).thenReturn(
                "{\"ts\":1700000000000,\"routeName\":\"外滩\",\"memberId\":9,"
                        + "\"track\":[{\"lat\":31.2304567,\"lng\":121.4737123,\"acc\":5}],\"pois\":[]}");
        mvc.perform(get(PATH).param("token", TOKEN))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.routeName").value("外滩"))
                .andExpect(jsonPath("$.data.ts").value(1699999200000L))
                .andExpect(jsonPath("$.data.track[0].lat").value(31.230))
                .andExpect(jsonPath("$.data.track[0].lng").value(121.474))
                .andExpect(jsonPath("$.data.memberId").doesNotExist())
                .andExpect(jsonPath("$.data.track[0].acc").doesNotExist());
    }

    @Test
    void unrelatedControllerDataAccessFailureStillUsesUnchangedGlobalAdvice() throws Exception {
        mvc.perform(get("/e06-17/unrelated"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.msg").value(UserSafeMessage.GENERIC))
                .andExpect(content().string(not(containsString("SQL SELECT"))))
                .andExpect(content().string(not(containsString("private unrelated table"))));
        verifyNoInteractions(mapper);
    }

    @RestController
    public static class OtherController {
        @GetMapping("/e06-17/unrelated")
        public Object read() {
            // Match the MyBatis diagnostic marker covered by the existing global advice contract.
            throw new DataAccessResourceFailureException(
                    "Error querying database. SQL SELECT private unrelated table");
        }
    }
}
