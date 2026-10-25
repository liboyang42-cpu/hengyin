package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.MediaPublicationTestFixtures;
import com.chengyinhub.business.service.support.RewardRulesValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real existing coupon-owner guard, resource lookup double; no issue/redeem/provider or database proof. */
class TemplateRewardOwnershipTest {
    private static final Long OWNER = 7L, OTHER = 99L;
    private static final String FOREIGN = "[{\"outcomeCode\":\"COMPLETED\",\"couponId\":32}]";
    private static final String OWNED = "[{\"outcomeCode\":\"COMPLETED\",\"couponId\":31,\"feedbackText\":\"原条件反馈\"}]";
    private TemplatePublishServiceImpl service;
    private CmsMemberTemplateServiceImpl copies;
    private ICmsMemberTemplateService members;
    private ICmsTemplateLibraryService libraries;
    private IAuditTaskService audits;
    private CmsMemberTemplateMapper memberMapper;
    private CmsTemplateLibraryMapper libraryMapper;
    private AuditTaskMapper tasks;
    private ISmsCouponService coupons;
    private CouponRewardAuthorizationServiceImpl authorization;
    private final Map<Long,CmsMemberTemplate> records = new HashMap<>();

    @BeforeEach void setup() {
        members=mock(ICmsMemberTemplateService.class); libraries=mock(ICmsTemplateLibraryService.class); audits=mock(IAuditTaskService.class);
        memberMapper=mock(CmsMemberTemplateMapper.class); libraryMapper=mock(CmsTemplateLibraryMapper.class); tasks=mock(AuditTaskMapper.class);
        coupons=mock(ISmsCouponService.class); authorization=new CouponRewardAuthorizationServiceImpl(); field(authorization,"smsCouponService",coupons);
        SmsCoupon own=new SmsCoupon();own.setId(31L);own.setMemberId(OWNER);own.setDelFlag(0);
        SmsCoupon other=new SmsCoupon();other.setId(32L);other.setMemberId(OTHER);other.setDelFlag(0);
        when(coupons.selectSmsCouponById(31L)).thenReturn(own);when(coupons.selectSmsCouponById(32L)).thenReturn(other);
        service=new TemplatePublishServiceImpl();field(service,"couponRewardAuthorizationService",authorization);
        field(service,"memberTemplateService",members);field(service,"memberTemplateMapper",memberMapper);
        field(service,"templateLibraryService",libraries);field(service,"libraryMapper",libraryMapper);field(service,"auditTaskMapper",tasks);
        field(service,"auditTaskService",audits);field(service,"capabilityGuard",mock(ICapabilityGuard.class));
        field(service,"mediaPublicationGuard",MediaPublicationTestFixtures.template(OWNER));
        when(members.selectCmsMemberTemplateById(anyLong())).thenAnswer(call->records.get(call.getArgument(0)));
        when(memberMapper.selectCmsMemberTemplateByIdsForUpdate(anyList())).thenAnswer(call->{
            List<CmsMemberTemplate> result=new ArrayList<>();for(Long id:(List<Long>)call.getArgument(0))if(records.containsKey(id))result.add(records.get(id));return result;
        });
        when(libraryMapper.selectByMemberTemplateForUpdate(anyLong())).thenReturn(Collections.emptyList());
        when(members.insertCmsMemberTemplate(any())).thenAnswer(call->{CmsMemberTemplate row=call.getArgument(0);row.setId(100L);records.put(row.getId(),row);return 1;});
        when(members.updateCmsMemberTemplate(any())).thenAnswer(call->{CmsMemberTemplate row=call.getArgument(0);records.put(row.getId(),row);return 1;});
        copies=new CmsMemberTemplateServiceImpl();field(copies,"cmsMemberTemplateMapper",memberMapper);field(copies,"couponRewardAuthorizationService",authorization);
        when(memberMapper.selectCmsMemberTemplateById(anyLong())).thenAnswer(call->records.get(call.getArgument(0)));
        when(memberMapper.insertCmsMemberTemplate(any())).thenAnswer(call->{CmsMemberTemplate row=call.getArgument(0);row.setId(200L);records.put(row.getId(),row);return 1;});
    }
    private static void field(Object target,String name,Object value){ReflectionTestUtils.setField(target,name,value);}
    private CmsTemplatePublishRequest request(){CmsTemplatePublishRequest r=new CmsTemplatePublishRequest();r.setTitle("原内容");r.setValidationMethod(3);r.setQuestionName("原题面");r.setQuestionAnswer("原秘密");return r;}
    private CmsMemberTemplate canonical(){CmsMemberTemplate row=new CmsMemberTemplate();row.setId(50L);row.setMemberId(OWNER);row.setTitle("已审核内容");
        row.setOriginalTemplateId(0L);row.setStatus(1L);row.setCheckStatus(1);row.setDraftStatus(1);row.setDelFlag(0);records.put(row.getId(),row);return row;}
    private void noWrites(){verify(members,never()).insertCmsMemberTemplate(any());verify(members,never()).updateCmsMemberTemplate(any());
        verify(memberMapper,never()).insertCmsMemberTemplate(any());verify(memberMapper,never()).updateCmsMemberTemplate(any());
        verify(libraries,never()).insertCmsTemplateLibrary(any());verify(libraries,never()).updateCmsTemplateLibrary(any());verifyNoInteractions(audits,tasks);}

    @Test void appPublishCannotHideForeignCouponOnlyInConditionalRules(){
        CmsTemplatePublishRequest r=request();r.setRewardRulesJson(FOREIGN);
        assertThrows(IllegalArgumentException.class,()->service.publishTemplateWithTransaction(r,OWNER,"author"));noWrites();
        verify(coupons).selectSmsCouponById(32L);
    }
    @Test void ordinaryDraftUsesTheSameConditionalCouponOwnerGuard(){
        CmsTemplatePublishRequest r=request();r.setRewardRulesJson(FOREIGN);
        assertThrows(IllegalArgumentException.class,()->service.saveOrUpdateDraft(r,OWNER,"author"));noWrites();
    }
    @Test void merchantNodeCannotPublishAnotherMerchantFlatCoupon(){
        CmsMemberTemplate row=new CmsMemberTemplate();row.setTitle("据点内容");row.setValidationMethod(3);row.setCouponId(32L);
        assertThrows(IllegalArgumentException.class,()->service.saveMerchantNodeTemplate(row,OWNER,"merchant"));noWrites();
    }
    @Test void adminCreateChecksTheContentOwnerInsteadOfAdminIdentity(){
        CmsMemberTemplate row=new CmsMemberTemplate();row.setMemberId(OWNER);row.setRewardRulesJson(FOREIGN);
        assertThrows(IllegalArgumentException.class,()->service.createAdminTemplate(row,"admin"));noWrites();
    }
    @Test void adminEditCannotUseClientOwnerToAuthorizeForeignCoupon(){
        canonical();CmsMemberTemplate patch=new CmsMemberTemplate();patch.setId(50L);patch.setMemberId(OTHER);patch.setRewardRulesJson(FOREIGN);
        assertThrows(IllegalArgumentException.class,()->service.updateAdminTemplate(patch,"admin"));assertEquals(OWNER,patch.getMemberId());noWrites();
    }
    @Test void persistedPersonalResubmissionCannotBypassRewardOwnership(){
        canonical().setRewardRulesJson(FOREIGN);
        assertThrows(IllegalArgumentException.class,()->service.submitTemplateForReview(50L));noWrites();
    }
    @Test void marketSubmissionCannotPublishAnOldForeignCouponReference(){
        canonical().setRewardRulesJson(FOREIGN);
        assertThrows(IllegalArgumentException.class,()->service.submitTemplateToMarket(50L,OWNER,"author"));noWrites();
    }
    @Test void adminMarketProjectionUsesLockedCanonicalOwnerForConditionalCoupon(){
        canonical();CmsTemplateLibrary stored=new CmsTemplateLibrary();stored.setId(60L);stored.setMemberTemplateId(50L);stored.setMemberId(OWNER);stored.setDelFlag(0);
        when(libraries.selectCmsTemplateLibraryById(60L)).thenReturn(stored);when(libraryMapper.selectByMemberTemplateForUpdate(50L)).thenReturn(List.of(stored));
        CmsTemplateLibrary patch=new CmsTemplateLibrary();patch.setId(60L);patch.setMemberId(OTHER);patch.setRewardRulesJson(FOREIGN);
        assertThrows(IllegalArgumentException.class,()->service.updateTemplateFromLibraryProjection(patch,"admin"));noWrites();
    }
    @Test void validOwnedRewardsKeepPersonalIdentityAndCompleteContentFields(){
        CmsTemplatePublishRequest r=request();r.setCouponId(31L);r.setRewardRulesJson(OWNED);r.setInteractionType("hidden_menu");r.setStoryText("原剧情");
        Long id=service.saveOrUpdateDraft(r,OWNER,"author");CmsMemberTemplate saved=records.get(id);
        assertEquals(OWNER,saved.getMemberId());assertEquals(31L,saved.getCouponId());assertEquals(OWNED,saved.getRewardRulesJson());
        assertEquals("原秘密",saved.getQuestionAnswer());assertEquals("hidden_menu",saved.getInteractionType());assertEquals("原剧情",saved.getStoryText());
        verify(coupons,times(1)).selectSmsCouponById(31L);assertEquals(0L,saved.getOriginalTemplateId());
    }
    @Test void republishPreflightDoesNotAuthorizeForeignCouponEvenWithMatchingReview(){
        canonical();CmsTemplateLibrary row=new CmsTemplateLibrary();row.setId(60L);row.setMemberTemplateId(50L);row.setMemberId(OWNER);
        row.setDelFlag(0);row.setStatus(1L);row.setPublishStatus(0L);row.setRewardRulesJson(FOREIGN);
        when(libraryMapper.selectByMemberTemplateForUpdate(50L)).thenReturn(List.of(row));
        var review=com.chengyinhub.business.service.support.TemplateLibraryRepublishReview.freeze(50L,OWNER,List.of(row));
        assertThrows(IllegalArgumentException.class,()->service.updateTemplateLibraryStatus(50L,1,OWNER,review));
        assertEquals(0L,row.getPublishStatus());noWrites();
    }
    @Test void offliningRemainsAvailableWhenAnOldRewardReferenceIsNoLongerOwned(){
        canonical().setRewardRulesJson(FOREIGN);CmsTemplateLibrary row=new CmsTemplateLibrary();row.setId(60L);row.setMemberTemplateId(50L);row.setMemberId(OWNER);
        row.setDelFlag(0);row.setStatus(1L);row.setPublishStatus(1L);row.setRewardRulesJson(FOREIGN);
        when(libraryMapper.selectByMemberTemplateForUpdate(50L)).thenReturn(List.of(row));when(libraries.updateCmsTemplateLibrary(any())).thenReturn(1);
        assertTrue(service.updateTemplateLibraryStatus(50L,0,OWNER));assertEquals(0L,row.getPublishStatus());verifyNoInteractions(coupons);
        verify(libraries).updateCmsTemplateLibrary(row);
    }
    @Test void omittedRulesStillMeanKeepWhileExplicitClearCanRemoveAnInvalidOldReference(){
        CmsMemberTemplate existing=canonical();existing.setRewardRulesJson(FOREIGN);
        CmsTemplatePublishRequest keep=request();keep.setId(50L);
        assertThrows(IllegalArgumentException.class,()->service.saveOrUpdateDraft(keep,OWNER,"author"));noWrites();
        CmsTemplatePublishRequest clear=request();clear.setId(50L);clear.setClearFields(Set.of("rewardRulesJson"));
        assertEquals(50L,service.saveOrUpdateDraft(clear,OWNER,"author"));
        CmsMemberTemplate saved=records.get(50L);assertNull(saved.getRewardRulesJson());assertTrue(saved.getClearFields().contains("rewardRulesJson"));
        verify(coupons,times(1)).selectSmsCouponById(32L);
    }
    @Test void couponLookupFailureIsNotAnEmptyOwnedResourceOrSuccessfulDraft(){
        CmsTemplatePublishRequest r=request();r.setRewardRulesJson(OWNED);
        org.springframework.dao.DataAccessResourceFailureException cause=new org.springframework.dao.DataAccessResourceFailureException("controlled read failure");
        when(coupons.selectSmsCouponById(31L)).thenThrow(cause);
        assertSame(cause,assertThrows(org.springframework.dao.DataAccessResourceFailureException.class,()->service.saveOrUpdateDraft(r,OWNER,"author")));noWrites();
    }
    @Test void entireTopicCopyCannotCloneFlatOrConditionalCouponToAnotherOwner(){
        CmsMemberTemplate row=canonical();row.setMemberId(OTHER);row.setCouponId(32L);
        assertThrows(IllegalArgumentException.class,()->copies.copyForContentOwner(50L,OWNER,false));
        row.setCouponId(null);row.setRewardRulesJson(FOREIGN);
        assertThrows(IllegalArgumentException.class,()->copies.copyForContentOwner(50L,OWNER,false));noWrites();
        assertEquals(OTHER,row.getMemberId());assertEquals(FOREIGN,row.getRewardRulesJson());
    }
    @Test void sameOwnerForcedCopyKeepsOwnedCouponAndAllOriginalContentWithoutLibraryIdConfusion(){
        CmsMemberTemplate row=canonical();row.setCouponId(31L);row.setRewardRulesJson(OWNED);row.setOriginalTemplateId(900L);
        row.setAdvancedConfigJson("{\"schemaVersion\":1}");row.setQuestionAnswer("原秘密");row.setInteractionType("hidden_menu");
        Long id=copies.copyForContentOwner(50L,OWNER,true);CmsMemberTemplate saved=records.get(id);
        assertNotEquals(row.getId(),id);assertEquals(900L,saved.getOriginalTemplateId());assertEquals(OWNER,saved.getMemberId());
        assertEquals(31L,saved.getCouponId());assertEquals(OWNED,saved.getRewardRulesJson());assertEquals(row.getAdvancedConfigJson(),saved.getAdvancedConfigJson());
        assertEquals("原秘密",saved.getQuestionAnswer());assertEquals("hidden_menu",saved.getInteractionType());assertEquals(1L,saved.getStatus());assertEquals(1,saved.getDraftStatus());
        verify(coupons,times(1)).selectSmsCouponById(31L);
    }
    @Test void pureReferenceCollectionDeduplicatesAndRejectsInvalidRulesWithoutChangingTheInput(){
        RewardRulesValidator validator=new RewardRulesValidator();String raw="[{\"outcomeCode\":\"A\",\"couponId\":31},{\"outcomeCode\":\"B\",\"couponId\":31},{\"outcomeCode\":\"C\",\"xp\":1}]";
        Set<Long> ids=validator.couponIds(raw);assertEquals(Set.of(31L),ids);assertThrows(UnsupportedOperationException.class,()->ids.add(32L));
        assertTrue(validator.couponIds(null).isEmpty());assertThrows(IllegalArgumentException.class,()->validator.couponIds("[{\"couponId\":31}]"));
        verifyNoInteractions(coupons);assertTrue(raw.contains("\"couponId\":31"));
    }
}
