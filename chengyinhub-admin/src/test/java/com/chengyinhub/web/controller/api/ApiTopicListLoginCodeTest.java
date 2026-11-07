package com.chengyinhub.web.controller.api;

import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

/** 第十二轮 k9(j6 N8):「我发布的」主题列表在没有会话时答 401,让小程序静默重登后重试。 */
class ApiTopicListLoginCodeTest {

    @Test
    void myTopicListWithoutSessionAnswersLoginCode() {
        ApiTopicController controller = spy(new ApiTopicController());
        doThrow(new RuntimeException("no session")).when(controller).getAppUserId();

        AjaxResult result = controller.topicList("1", null, null, null, null, null, null, null, null);

        assertEquals(401, result.get(AjaxResult.CODE_TAG));
        assertEquals("请先登录", result.get(AjaxResult.MSG_TAG));
    }
}
