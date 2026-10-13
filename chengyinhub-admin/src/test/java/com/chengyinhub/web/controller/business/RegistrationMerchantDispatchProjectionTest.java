package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.service.ICmsRegistrationMerchantService;
import com.chengyinhub.business.service.ICmsTopicService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 调配列表是决策视图，不得把完整报名实体中的 PII/历史结算字段返给浏览器。 */
class RegistrationMerchantDispatchProjectionTest {

    @Test
    void dispatchTreeReturnsOnlyDecisionFields() throws Exception {
        ICmsRegistrationMerchantService registrations = mock(ICmsRegistrationMerchantService.class);
        ICmsTopicService topics = mock(ICmsTopicService.class);
        CmsRegistrationMerchant source = new CmsRegistrationMerchant();
        source.setId(7L);
        source.setMemberId(88L);
        source.setTopicId(11L);
        source.setMode(1);
        source.setNodeId(12L);
        source.setAddressName("市民广场");
        source.setTemplateId(13L);
        source.setActivityDesc("周末承接");
        source.setAuditStatus(0L);
        source.setReason("待补材料");
        source.setEmail("merchant@example.com");
        source.setLongitude("121.4737");
        source.setLatitude("31.2304");
        source.setAddress("上海市黄浦区示例路 1 号");
        source.setQuestionAnswer("私密答案");
        source.setCorrectAnswer("A");
        source.setSharingRate(new BigDecimal("99.99"));
        source.setSharingAmount(new BigDecimal("999.99"));
        when(registrations.selectCmsRegistrationMerchantList(any(CmsRegistrationMerchant.class)))
                .thenReturn(Collections.singletonList(source));
        CmsTopic topic = new CmsTopic();
        topic.setName("城市漫游");
        when(topics.selectCmsTopicById(11L)).thenReturn(topic);

        CmsRegistrationMerchantController controller = new CmsRegistrationMerchantController();
        ReflectionTestUtils.setField(controller, "cmsRegistrationMerchantService", registrations);
        ReflectionTestUtils.setField(controller, "cmsTopicService", topics);
        // #208 起 dispatchTree 开头有主题范围守卫;topicId=null 走 hasGlobalTopicScope,桩成放行以测 PII 投影。
        ITopicOperationsScopeAuthorizer scopeAuthorizer = mock(ITopicOperationsScopeAuthorizer.class);
        when(scopeAuthorizer.hasGlobalTopicScope()).thenReturn(true);
        ReflectionTestUtils.setField(controller, "topicOperationsScopeAuthorizer", scopeAuthorizer);

        AjaxResult result = controller.dispatchTree(null);
        String body = new ObjectMapper().writeValueAsString(result);

        assertTrue(body.contains("市民广场"));
        assertFalse(body.contains("merchant@example.com"));
        assertFalse(body.contains("121.4737"));
        assertFalse(body.contains("上海市黄浦区示例路"));
        assertFalse(body.contains("私密答案"));
        assertFalse(body.contains("99.99"));
        assertFalse(body.contains("999.99"));
    }
}
