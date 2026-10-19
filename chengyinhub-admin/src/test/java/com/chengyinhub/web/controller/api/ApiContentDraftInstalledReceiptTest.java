package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.*;
import com.chengyinhub.business.domain.ContentDraft;
import com.chengyinhub.business.domain.vo.ContentDraftRestoreView;
import com.chengyinhub.business.marketplace.editor.*;
import com.chengyinhub.business.service.IContentDraftService;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiContentDraftInstalledReceiptTest {
    private ApiContentDraftController controller;
    private IContentDraftService drafts;
    private IMerchantAccessService merchants;
    private InstalledDraftReceiptReader reader;
    private ContentDraft draft;
    @BeforeEach void setup(){
        controller=spy(new ApiContentDraftController());doReturn(42L).when(controller).getAppUserId();
        drafts=mock(IContentDraftService.class);merchants=mock(IMerchantAccessService.class);reader=mock(InstalledDraftReceiptReader.class);
        ReflectionTestUtils.setField(controller,"draftService",drafts);ReflectionTestUtils.setField(controller,"merchantAccessService",merchants);
        draft=new ContentDraft();draft.setId(91L);draft.setOwnerMemberId(42L);draft.setBusinessType("TOPIC");draft.setClientDraftKey("stable");draft.setPayloadJson("{\"title\":\"mine\"}");draft.setPayloadHash("hash");draft.setVersion(1L);draft.setStatus("DRAFT");
        draft.setSubjectId(55L);draft.setUpdatedByDevice("fixture-device");draft.setPublishedResourceId(71L);
        draft.setCreateTime(new Date(1759440000000L));draft.setUpdateTime(new Date(1759440060000L));
        when(drafts.restore(42L,91L)).thenReturn(draft);
    }
    private ContentDraftRestoreView view(AjaxResult r){assertEquals(200,r.get(AjaxResult.CODE_TAG));return (ContentDraftRestoreView)r.get(AjaxResult.DATA_TAG);}
    @Test void defaultUnregisteredReaderLeavesRestoreWorkingAndExplicitlyDisabled(){ContentDraftRestoreView v=view(controller.restore("91",null));assertEquals(draft.getPayloadJson(),v.getPayloadJson());assertEquals(InstalledDraftReceipts.Availability.NOT_ENABLED,v.getInstalledModules().getAvailability());verifyNoInteractions(reader);}
    @Test void ownedRestoreCallsProjectionWithExactServerDraftBinding(){
        ReflectionTestUtils.setField(controller,"installedDraftReceiptReader",reader);
        InstalledDraftReceipts receipts=new InstalledDraftReceipts(InstalledDraftReceipts.Availability.HISTORICAL_RECEIPTS_ONLY,Collections.emptyList(),false);
        when(reader.read(42,91,1,"hash")).thenReturn(receipts);
        assertSame(receipts,view(controller.restore("91",null)).getInstalledModules());verify(reader).read(42,91,1,"hash");verifyNoInteractions(merchants);
    }
    @Test void merchantDelegateStillRestoresDraftButCannotReadIndividualReceipts(){
        ReflectionTestUtils.setField(controller,"installedDraftReceiptReader",reader);
        when(merchants.require(42L,MerchantPermission.PROJECT_MANAGE)).thenReturn(new MerchantAccessContext(42L,77L,43L,MerchantRoleCode.MERCHANT_MANAGER));
        when(drafts.restore(43L,91L)).thenReturn(draft);
        assertEquals(InstalledDraftReceipts.Availability.OWNER_ONLY,view(controller.restore("91","MERCHANT")).getInstalledModules().getAvailability());verifyNoInteractions(reader);
    }
    @Test void merchantOwnerScopeAlsoDoesNotMixIndividualAndMerchantRights(){
        ReflectionTestUtils.setField(controller,"installedDraftReceiptReader",reader);
        when(merchants.require(42L,MerchantPermission.PROJECT_MANAGE)).thenReturn(new MerchantAccessContext(42L,77L,42L,MerchantRoleCode.MERCHANT_OWNER));
        assertEquals(InstalledDraftReceipts.Availability.OWNER_ONLY,view(controller.restore("91","MERCHANT")).getInstalledModules().getAvailability());verifyNoInteractions(reader);
    }
    @Test void restoreDenialCannotCallReadModel(){ReflectionTestUtils.setField(controller,"installedDraftReceiptReader",reader);when(drafts.restore(42L,91L)).thenThrow(new ServiceException("missing",404));assertEquals(404,controller.restore("91",null).get(AjaxResult.CODE_TAG));verifyNoInteractions(reader);}
    @Test void concurrentDraftChangeReturns409InsteadOfAttachingReceipt(){ReflectionTestUtils.setField(controller,"installedDraftReceiptReader",reader);when(reader.read(42,91,1,"hash")).thenThrow(new ServiceException("DRAFT_CHANGED",409));assertEquals(409,controller.restore("91",null).get(AjaxResult.CODE_TAG));}
    @Test void readModelFailureCannotFallbackToSuccessOrEmptyReceipts(){ReflectionTestUtils.setField(controller,"installedDraftReceiptReader",reader);when(reader.read(42,91,1,"hash")).thenThrow(new IllegalStateException("missing-table"));assertEquals(500,controller.restore("91",null).get(AjaxResult.CODE_TAG));}
    @Test void existingFlatRestoreJsonIsPreserved() throws Exception {ContentDraftRestoreView view=view(controller.restore("91",null));ObjectMapper json=new ObjectMapper();Map<String,Object> original=json.convertValue(draft,Map.class),projected=json.convertValue(view,Map.class);for(String key:original.keySet())assertEquals(original.get(key),projected.get(key),key);assertEquals(original.size()+1,projected.size());}
    @Test void conflictRemainsAnAjaxResultBusinessCodeInHttp200Envelope() throws Exception {
        ReflectionTestUtils.setField(controller,"installedDraftReceiptReader",reader);
        when(reader.read(42,91,1,"hash")).thenThrow(new ServiceException("DRAFT_CHANGED",409));
        ApiContentDraftController httpController=new ApiContentDraftController(){@Override public Long getAppUserId(){return 42L;}};
        ReflectionTestUtils.setField(httpController,"draftService",drafts);
        ReflectionTestUtils.setField(httpController,"merchantAccessService",merchants);
        ReflectionTestUtils.setField(httpController,"installedDraftReceiptReader",reader);
        org.springframework.mock.web.MockHttpServletResponse response=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(httpController).build()
            .perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/content-draft/restore").param("draft_id","91"))
            .andReturn().getResponse();
        assertEquals(200,response.getStatus());
        assertEquals(409,new ObjectMapper().readTree(response.getContentAsString()).path("code").asInt());
    }

}
