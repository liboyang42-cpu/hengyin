package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.dto.ai.AiTemplateFillReq;
import com.chengyinhub.business.domain.vo.ai.AiTemplateFillResp;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.ai.AiGatewayResult;
import com.chengyinhub.business.service.ai.AiGatewayService;
import com.chengyinhub.business.service.ai.AiServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 升级1·AI 自动填表:店铺输入 → template 全字段;bizType=template_fill;
 * 模型回坏 JSON / mock 输出时失败关闭,不能把空模板包装成 HTTP success。
 */
class AiTemplateFillTest {

    private static final Long MEMBER = 7L;

    private AiCreatorServiceImpl service;
    private AiGatewayService gateway;

    @BeforeEach
    void setUp() {
        service = new AiCreatorServiceImpl();
        gateway = mock(AiGatewayService.class);
        ReflectionTestUtils.setField(service, "gateway", gateway);
        ReflectionTestUtils.setField(service, "umsMemberService", mock(IUmsMemberService.class));
        ReflectionTestUtils.setField(service, "roleService", mock(IRoleService.class));
    }

    private static AiTemplateFillReq req() {
        AiTemplateFillReq req = new AiTemplateFillReq();
        req.setShopName("角落咖啡");
        req.setCategory("咖啡店");
        req.setReward("隐藏饮品券");
        req.setPlayStyle("隐藏菜单");
        return req;
    }

    @Test
    void fillNormalFlow_parsesAllFacadeFields() {
        String aiJson = "{\"template\":{\"title\":\"今晚的暗号\",\"description\":\"说出暗号,解锁隐藏奖励\"," +
                "\"validationMethod\":1,\"ruleInstructions\":\"三步规则\",\"questionName\":\"暗号是什么\"," +
                "\"questionAnswer\":\"城市醒了\",\"hint1\":\"h1\",\"hint2\":\"h2\",\"answerReveal\":\"揭示\"," +
                "\"feedbackText\":\"反馈\",\"storyText\":\"剧情\",\"coverPrompt\":\"吧台暖光近景\",\"medalName\":\"隐藏入口发现者\"}}";
        when(gateway.generate(anyLong(), anyString(), eq("template_fill"), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-tf", aiJson, "real", 0));

        AiTemplateFillResp resp = service.templateFill(req(), MEMBER);

        assertEquals("trace-tf", resp.getTraceId());
        assertNotNull(resp.getTemplate());
        assertEquals("今晚的暗号", resp.getTemplate().getTitle());
        assertEquals("城市醒了", resp.getTemplate().getQuestionAnswer());
        assertEquals("吧台暖光近景", resp.getTemplate().getCoverPrompt());
        assertEquals("隐藏入口发现者", resp.getTemplate().getMedalName());

        // 用户输入按结构化 JSON 传给网关(店名/品类/奖励都在),不是自由拼接
        ArgumentCaptor<String> userJson = ArgumentCaptor.forClass(String.class);
        verify(gateway).generate(anyLong(), anyString(), eq("template_fill"), anyString(), userJson.capture());
        assertTrue(userJson.getValue().contains("角落咖啡"));
        assertTrue(userJson.getValue().contains("隐藏饮品券"));
    }

    @Test
    void badJson_failsClosed() {
        when(gateway.generate(anyLong(), anyString(), eq("template_fill"), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-bad", "抱歉,我不能输出JSON", "real", 0));

        assertThrows(AiServiceException.class, () -> service.templateFill(req(), MEMBER));
    }

    @Test
    void mockModel_failsClosed() {
        when(gateway.generate(anyLong(), anyString(), eq("template_fill"), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-mock", "{\"template\":{}}", "mock-fallback", 0));

        assertThrows(AiServiceException.class, () -> service.templateFill(req(), MEMBER));
    }

    @Test
    void missingRequiredTemplateFields_failsClosed() {
        when(gateway.generate(anyLong(), anyString(), eq("template_fill"), anyString(), anyString()))
                .thenReturn(new AiGatewayResult("trace-empty", "{\"template\":{\"title\":\"只有标题\"}}", "real", 0));

        assertThrows(AiServiceException.class, () -> service.templateFill(req(), MEMBER));
    }
}
