package com.chengyinhub.business.home;

import com.chengyinhub.web.controller.api.ApiPrivateHomeController;
import com.chengyinhub.business.home.PrivateHomeContracts.*;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

class PrivateHomeHttpContractTest {
    PrivateHomeService service; MockMvc mvc;
    @BeforeEach void setup(){service=mock(PrivateHomeService.class);ApiPrivateHomeController controller=new ApiPrivateHomeController();ReflectionTestUtils.setField(controller,"service",service);mvc=MockMvcBuilders.standaloneSetup(controller).build();}
    @Test void endpointDefaultsOffAndDoesNotExposeProofOrRestore() throws Exception {
        ConditionalOnProperty condition=ApiPrivateHomeController.class.getAnnotation(ConditionalOnProperty.class);
        assertFalse(condition.matchIfMissing());assertEquals("true",condition.havingValue());assertEquals("app.private-home.owner-api-enabled",condition.name()[0]);
        mvc.perform(post("/api/native/home/restore")).andExpect(status().isNotFound());
        mvc.perform(post("/api/native/home/verify")).andExpect(status().isNotFound());
    }
    @Test void ownerFieldsAreRejectedRatherThanBound() throws Exception {
        mvc.perform(put("/api/native/home").contentType("application/json").content("{\"requestId\":\"x\",\"expectedVersion\":0,\"ownerId\":99,\"latitude\":1,\"longitude\":2,\"datum\":\"WGS84\",\"label\":\"private\"}"))
            .andExpect(jsonPath("msg").value("HOME_REQUEST_INVALID"));verifyNoInteractions(service);
    }
    @Test void privateReadCannotBeCachedAndOmitsOwner() throws Exception {
        when(service.getMyHome()).thenReturn(new HomeView(null));
        mvc.perform(get("/api/native/home")).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("data.ownerId").doesNotExist()).andExpect(jsonPath("data.status").value("DELETED"));
    }
    @Test void providerDetailsNeverReachHttpResponse() throws Exception {
        when(service.getMyHome()).thenThrow(new ServiceException("provider error secret location 30.123456"));
        mvc.perform(get("/api/native/home")).andExpect(jsonPath("msg").value("HOME_UNAVAILABLE"));
    }
    @Test void redactedContractsDoNotLogPrivateFields(){Home home=new Home();home.label="secret";assertEquals("PrivateHome[redacted]",home.toString());}
}
