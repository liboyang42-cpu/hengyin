package com.chengyinhub.business.longrun;

import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.service.ai.SpeechToTextService;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.web.controller.api.ApiAiNpcController;
import com.chengyinhub.web.controller.api.NpcFeatureFlags;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** NOT_RUN. Existing receipt must precede every ASR/file operation. */
class OWN_C04_07Test {
    @Test void storedProcessingAndTerminalReceiptsReturnBeforeAsrOrFileValidation() {
        for (String status : new String[] {"PROCESSING", "SUCCEEDED"}) {
            String id = "00000000-0000-4000-8000-000000000007";
            ApiAiNpcController controller = spy(new ApiAiNpcController());
            doReturn(1001L).when(controller).getAppUserId();
            NpcFeatureFlags flags = mock(NpcFeatureFlags.class);
            when(flags.shopChatOn()).thenReturn(true);
            NpcChatService chat = mock(NpcChatService.class);
            SpeechToTextService speech = mock(SpeechToTextService.class);
            NpcChatResp stored = new NpcChatResp(); stored.setRequestId(id); stored.setOutcomeStatus(status);
            when(chat.storedShopRequest(id, 1001L, 21L)).thenReturn(stored);
            ReflectionTestUtils.setField(controller, "npcFeatureFlags", flags);
            ReflectionTestUtils.setField(controller, "npcChatService", chat);
            ReflectionTestUtils.setField(controller, "speechToTextService", speech);
            AjaxResult result = controller.voiceChat(null, 21L, id);
            assertSame(stored, result.get("data"));
            verifyNoInteractions(speech);
            verify(chat, never()).chatWithShop(anyString(), anyLong(), anyLong(), anyLong(), anyString(), anyString());
        }
    }
}
