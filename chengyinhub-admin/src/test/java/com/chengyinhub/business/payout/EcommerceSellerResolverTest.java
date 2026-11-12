package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IProductRoleService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class EcommerceSellerResolverTest {
    final FakeEcomPayoutMapper payout = new FakeEcomPayoutMapper();
    final CmsTopicMapper topics = mock(CmsTopicMapper.class);
    final CmsActivityMapper activities = mock(CmsActivityMapper.class);
    final MmsMerchantMapper merchants = mock(MmsMerchantMapper.class);
    final IProductRoleService roles = mock(IProductRoleService.class);

    EcommerceSellerResolver resolver(PayoutSwitches s) {
        return new EcommerceSellerResolver(s, payout, topics, activities, merchants, roles);
    }

    static CmsRegistration reg(long ownerType, long ownerId) {
        CmsRegistration r = new CmsRegistration();
        r.setOwnerType(ownerType); r.setOwnerId(ownerId);
        return r;
    }

    void finished(String type, long id, String mchid) {
        payout.sellerMembers.put(type + ":" + id, 1L);
        var row = new com.chengyinhub.business.domain.EcomSubMerchant();
        row.setOwnerType(type); row.setOwnerId(id); row.setOutRequestNo("X" + id);
        payout.insertSubMerchant(row);
        payout.markApplymentSubmitted(row.getId(), 1L);
        payout.updateApplymentStatus(row.getId(), "CHECKING", "FINISH", null, null, mchid, null);
    }

    @Test
    void switchOffReadsNothing() {
        assertEquals("SWITCH_OFF", resolver(PayoutSwitches.allOff()).decide(reg(1, 1)).reason());
        verifyNoInteractions(topics, activities, merchants, roles);
    }

    @Test
    void merchantTopicTicketRoutesToItsSubMchid() {
        CmsTopic t = new CmsTopic(); t.setId(1L); t.setMemberId(55L);
        when(topics.selectCmsTopicById(1L)).thenReturn(t);
        MmsMerchant m = new MmsMerchant(); m.setId(5L); m.setMemberId(55L); m.setStatus(1L); m.setAccountStatus(1L); m.setDelFlag(0);
        when(merchants.selectMmsMerchantByMemberId(55L)).thenReturn(m);
        finished("MERCHANT", 5, "1900000101");
        SellerOfRecordPolicy.Decision d = resolver(EcommercePayoutServicesTest.ON).decide(reg(1, 1));
        assertTrue(d.subMerchant());
        assertEquals("1900000101", d.subMchid());
    }

    @Test
    void officialTopicStaysPlatformEvenIfCreatorIsOnboarded() {
        CmsTopic t = new CmsTopic(); t.setId(1L); t.setMemberId(55L);
        when(topics.selectCmsTopicById(1L)).thenReturn(t);
        when(roles.isPlatformOwned(1L)).thenReturn(true);
        assertEquals("OFFICIAL_THEME", resolver(EcommercePayoutServicesTest.ON).decide(reg(1, 1)).reason());
    }

    @Test
    void clubActivityUsesClubAndUnapprovedMerchantIsJustAMember() {
        CmsActivity a = new CmsActivity(); a.setId(2L); a.setMemberId(66L); a.setClubId(9L);
        when(activities.selectCmsActivityById(2L)).thenReturn(a);
        finished("CLUB", 9, "1900000102");
        SellerOfRecordPolicy.Decision d = resolver(EcommercePayoutServicesTest.ON).decide(reg(2, 2));
        assertEquals(SellerOfRecordPolicy.SellerType.CLUB, d.sellerType());
        assertTrue(d.subMerchant());

        CmsActivity b = new CmsActivity(); b.setId(3L); b.setMemberId(77L);
        when(activities.selectCmsActivityById(3L)).thenReturn(b);
        MmsMerchant pending = new MmsMerchant(); pending.setId(7L); pending.setStatus(0L); pending.setAccountStatus(1L); pending.setDelFlag(0);
        when(merchants.selectMmsMerchantByMemberId(77L)).thenReturn(pending);
        assertEquals("SELLER_NOT_MERCHANT_OR_CLUB", resolver(EcommercePayoutServicesTest.ON).decide(reg(2, 3)).reason());
    }

    @Test
    void unknownOwnerFallsBackToPlatform() {
        when(topics.selectCmsTopicById(any())).thenReturn(null);
        assertFalse(resolver(EcommercePayoutServicesTest.ON).decide(reg(1, 99)).subMerchant());
        assertEquals("NO_FACTS", resolver(EcommercePayoutServicesTest.ON).decide(reg(3, 1)).reason());
    }
}
