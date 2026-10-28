package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.service.support.AuthorAttributeRegistryReader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

class ApiTopicAttributeBindingControllerTest {
    @Test void actualMvcBindsLongTopicAndUsesAuthenticatedOwner() throws Exception {
        AuthorAttributeRegistryReader reader=mock(AuthorAttributeRegistryReader.class);
        IMerchantAccessService merchant=mock(IMerchantAccessService.class);
        ApiTopicAttributeBindingController controller=spy(new ApiTopicAttributeBindingController(reader,merchant));
        doReturn(11L).when(controller).getAppUserId();
        when(reader.read(Long.MAX_VALUE,11L)).thenReturn(new ObjectMapper().createObjectNode()
                .put("topicId",Long.toString(Long.MAX_VALUE)).put("registryVersion","2").put("configVersion","3"));
        // 生产 spring.http.converters.preferred-json-mapper=jackson2：Jackson 2 的 ObjectNode 须由同一转换器写出
        var mvc=MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter()).build();
        mvc.perform(get("/api/topic/attribute-binding").param("topicId",Long.toString(Long.MAX_VALUE)).param("memberId","12"))
                .andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.topicId").value(Long.toString(Long.MAX_VALUE)));
        verify(reader).read(Long.MAX_VALUE,11L);verifyNoInteractions(merchant);
        doReturn(0L).when(controller).getAppUserId();
        mvc.perform(get("/api/topic/attribute-binding").param("topicId","7")).andExpect(jsonPath("$.code").value(401));
        verifyNoMoreInteractions(reader);
    }
    @Test void authorConflictAdapterPreservesHttpStatusAndMachineCode() {
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        var response = new org.springframework.mock.web.MockHttpServletResponse();
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
                new org.springframework.web.context.request.ServletRequestAttributes(request,response));
        try {
            var conflict = com.chengyinhub.business.service.support.AuthorAttributeBinding.conflict(
                    "ATTRIBUTE_REGISTRY_VERSION_CONFLICT","注册版本已变化",409);
            com.chengyinhub.common.core.domain.AjaxResult result = org.springframework.test.util.ReflectionTestUtils
                    .invokeMethod(new ApiTopicController(),"authorAttributeError",conflict);
            org.junit.jupiter.api.Assertions.assertEquals(409,response.getStatus());
            org.junit.jupiter.api.Assertions.assertEquals(409,result.get("code"));
            org.junit.jupiter.api.Assertions.assertEquals("ATTRIBUTE_REGISTRY_VERSION_CONFLICT",
                    ((java.util.Map<?,?>) result.get("data")).get("errorCode"));
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
    }
}
