package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.mapper.CoopSettlementManualCaseReadMapper;
import com.chengyinhub.business.domain.vo.CoopSettlementManualCaseView;
import com.chengyinhub.business.service.impl.CoopSettlementManualCaseReadService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** MVC wiring and declared existing permission; not a claim of a new RBAC policy or full security-context acceptance. */
class SettlementManualCaseControllerTest {
    CoopSettlementManualCaseReadMapper rows;
    MockMvc mvc;
    @BeforeEach void caller(){rows=mock(CoopSettlementManualCaseReadMapper.class);SettlementController controller=new SettlementController();
        ReflectionTestUtils.setField(controller,"manualCases",new CoopSettlementManualCaseReadService(rows));mvc=MockMvcBuilders.standaloneSetup(controller).build();}
    @AfterEach void clearPagination(){com.github.pagehelper.PageHelper.clearPage();}
    @Test void listAndDetailKeepTheExistingSettlementReadPermissionAndGetOnlyRoutes() throws Exception {
        for(var method:List.of(SettlementController.class.getMethod("manualCaseList",Long.class,String.class),SettlementController.class.getMethod("manualCase",Long.class))) {
            assertEquals("@ss.hasPermi('business:settlement:list')",method.getAnnotation(PreAuthorize.class).value());assertNotNull(method.getAnnotation(GetMapping.class));
        }
    }
    @Test void operationalListUsesTopicFilterAndTheOriginalPaginatedResponseShape() throws Exception {
        var view=new CoopSettlementManualCaseView();view.setId(1L);view.setTopicId(11L);view.setState("MANUAL_REQUIRED");
        when(rows.selectList(11L,"MANUAL_REQUIRED")).thenReturn(List.of(view));
        mvc.perform(get("/business/settlement/manual-case/list").param("topicId","11").param("state","MANUAL_REQUIRED").param("pageNum","1").param("pageSize","10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rows[0].topicId").value(11)).andExpect(jsonPath("$.total").value(1));
        verify(rows).selectList(11L,"MANUAL_REQUIRED");
    }
    @Test void detailHidesPaymentIdentityAndMissingRecordIsExplicitWithoutAnyWriteRoute() throws Exception {
        var row=new CoopSettlementManualCaseReadMapper.Record();row.setId(1L);row.setTopicId(11L);row.setNegativeAmount(new BigDecimal("-1.00"));row.setState("MANUAL_REQUIRED");row.setSourceSnapshot("{\"paymentFactHash\":\"private-hash\"}");
        when(rows.selectById(1L)).thenReturn(row);
        mvc.perform(get("/business/settlement/manual-case/1")).andExpect(status().isOk()).andExpect(jsonPath("$.data.topicId").value(11))
                .andExpect(jsonPath("$.data.sourceSnapshot").doesNotExist()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("private-hash"))));
        mvc.perform(get("/business/settlement/manual-case/99")).andExpect(jsonPath("$.code").value(404));
        mvc.perform(post("/business/settlement/manual-case/1")).andExpect(status().isMethodNotAllowed());
    }
}
