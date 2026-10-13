package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.service.ISettlementRuleService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 负控：空、零或负数的会员账户 ID 必须在 Controller 层被 @Valid 拒绝。 */
class SettlementRulePlatformAccountValidationTest {

    private ISettlementRuleService settlementRuleService;
    private LocalValidatorFactoryBean validator;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        settlementRuleService = mock(ISettlementRuleService.class);
        SettlementRuleController controller = new SettlementRuleController();
        ReflectionTestUtils.setField(controller, "settlementRuleService", settlementRuleService);
        validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setValidator(validator).build();
    }

    @AfterEach
    void tearDown() {
        validator.destroy();
    }

    @Test
    void rejectsMissingZeroAndNegativeMemberIdBeforeServiceCall() throws Exception {
        for (String payload : new String[] { "{}", "{\"memberId\":0}", "{\"memberId\":-1}" }) {
            mockMvc.perform(put("/business/settlementrule/platform-account")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isBadRequest());
        }

        verify(settlementRuleService, never()).updatePlatformSettlementAccount(anyLong());
    }
}
