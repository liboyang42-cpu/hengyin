package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.ai.AiNodeGenReq;
import com.chengyinhub.business.domain.dto.seed.SeedPlace;
import com.chengyinhub.business.domain.vo.ai.AiNodeContent;
import com.chengyinhub.business.domain.vo.ai.AiNodeGenResp;
import com.chengyinhub.business.mapper.SeedContentMapper;
import com.chengyinhub.business.service.DistrictDensityService;
import com.chengyinhub.business.service.IAiCreatorService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.SeedPlaceImportService;
import com.chengyinhub.business.service.SeedRouteImportService;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.common.core.domain.AjaxResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** GAM-11: the admin console calls node generation as the authorised official operator, for official places only. */
class SeedContentControllerTest {
    private SeedContentMapper mapper;
    private IAiCreatorService ai;
    private IOfficialEventV2Service operators;
    private SeedContentController controller;

    @BeforeEach void setUp() {
        mapper = mock(SeedContentMapper.class);
        ai = mock(IAiCreatorService.class);
        operators = mock(IOfficialEventV2Service.class);
        controller = spy(new SeedContentController(mock(SeedPlaceImportService.class), mock(SeedRouteImportService.class),
                mock(DistrictDensityService.class), mapper, ai, operators));
        doReturn(1L).when(controller).getUserId();
        when(operators.resolveAuthorizedOfficialMember(1L)).thenReturn(7L);
    }

    private static SeedPlace place(int type, Long merchant) {
        SeedPlace p = new SeedPlace();
        p.setId(42L); p.setType(type); p.setMerchantId(merchant); p.setName("孤山"); p.setAddress("孤山路"); p.setDescription("湖中小山");
        return p;
    }

    private static boolean ok(AjaxResult r) { return Integer.valueOf(200).equals(r.get(AjaxResult.CODE_TAG)); }

    @Test void generatesDraftAsOfficialOperatorWithoutSaving() {
        when(mapper.selectPlacesByIds(List.of(42L))).thenReturn(List.of(place(1, null)));
        AiNodeContent node = new AiNodeContent();
        node.setQuestionName("山上亭子叫什么？");
        AiNodeGenResp resp = new AiNodeGenResp(); resp.setNode(node);
        when(ai.nodeGenerate(any(), eq(7L))).thenReturn(resp);
        AjaxResult result = controller.aiNodeDraft(42L);
        assertTrue(ok(result));
        assertSame(resp, result.get(AjaxResult.DATA_TAG));
        ArgumentCaptor<AiNodeGenReq> req = ArgumentCaptor.forClass(AiNodeGenReq.class);
        verify(ai).nodeGenerate(req.capture(), eq(7L));
        assertEquals("孤山", req.getValue().getNodeName());
        assertEquals("孤山路", req.getValue().getAddress());
        assertEquals(3, req.getValue().getValidationMethod(), "official places use the option question");
        verify(mapper, never()).insertImportedPlace(any());
    }

    @Test void refusesUnauthorisedOperatorsAndNonOfficialPlaces() {
        when(operators.resolveAuthorizedOfficialMember(1L)).thenReturn(null);
        assertFalse(ok(controller.aiNodeDraft(42L)));
        when(operators.resolveAuthorizedOfficialMember(1L)).thenReturn(7L);
        when(mapper.selectPlacesByIds(List.of(42L))).thenReturn(List.of(place(2, 5L)));
        assertFalse(ok(controller.aiNodeDraft(42L)));
        when(mapper.selectPlacesByIds(List.of(42L))).thenReturn(List.of());
        assertFalse(ok(controller.aiNodeDraft(42L)));
        verify(ai, never()).nodeGenerate(any(), any());
    }

    @Test void emptyOrFailedGenerationIsAnHonestError() {
        when(mapper.selectPlacesByIds(List.of(42L))).thenReturn(List.of(place(1, null)));
        when(ai.nodeGenerate(any(), eq(7L))).thenReturn(new AiNodeGenResp());
        AjaxResult empty = controller.aiNodeDraft(42L);
        assertFalse(ok(empty));
        assertEquals("AI 这次没有给出可用草稿，请重试或手动填写", empty.get(AjaxResult.MSG_TAG));
        when(ai.nodeGenerate(any(), eq(7L))).thenThrow(new AiServiceException("今日 AI 额度已用完"));
        AjaxResult failed = controller.aiNodeDraft(42L);
        assertEquals("今日 AI 额度已用完", failed.get(AjaxResult.MSG_TAG));
    }
}
