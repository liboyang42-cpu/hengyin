package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.CmsPresetRoute;
import com.chengyinhub.business.domain.dto.IpWorldQueryDTO;
import com.chengyinhub.business.domain.vo.IpWorldHomeVO;
import com.chengyinhub.business.service.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ConfigSourceBindingTest {
    @Test void presetGetBindsExactIdAndOptionalCityWithoutParameterMetadata() throws Exception {
        ApiPresetController controller=new ApiPresetController();IPresetService service=mock(IPresetService.class);
        ReflectionTestUtils.setField(controller,"presetService",service);
        CmsPresetRoute route=new CmsPresetRoute();route.setId(7L);when(service.getRouteWithNodes(7L)).thenReturn(route);
        when(service.listRoutes("上海")).thenReturn(Collections.singletonList(route));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/preset/route/info").param("id","7")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200)).andExpect(jsonPath("$.data.id").value(7));
        mvc.perform(get("/api/preset/route/list").param("city","上海")).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(7));
        verify(service).getRouteWithNodes(7L);verify(service).listRoutes("上海");
        mvc.perform(get("/api/preset/route/info").param("id","bad")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/preset/route/info")).andExpect(status().isBadRequest());
        verifyNoMoreInteractions(service);
    }
    @Test void worldPostBindsSelectedIdInsteadOfSilentlyFallingBackToFirstWorld() throws Exception {
        ApiIpWorldController controller=new ApiIpWorldController();IIpWorldService service=mock(IIpWorldService.class);
        ReflectionTestUtils.setField(controller,"ipWorldService",service);when(service.getHome(any())).thenReturn(new IpWorldHomeVO());
        MockMvc mvc=MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post("/api/world/home").param("world_id","3").param("city_code","310000")).andExpect(status().isOk()).andExpect(jsonPath("$.code").value(200));
        ArgumentCaptor<IpWorldQueryDTO> query=ArgumentCaptor.forClass(IpWorldQueryDTO.class);verify(service).getHome(query.capture());
        assertEquals(3L,query.getValue().getWorldId());assertEquals("310000",query.getValue().getCityCode());
    }
}
