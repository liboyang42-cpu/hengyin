package com.chengyinhub.business.payout;

import org.junit.jupiter.api.Test;

import static com.chengyinhub.business.payout.SellerOfRecordPolicy.ProductKind.*;
import static com.chengyinhub.business.payout.SellerOfRecordPolicy.SellerType.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SellerOfRecordPolicyTest {
    private static final PayoutSwitches ON = new PayoutSwitches(true, true, true, "1900000001", "wxsp", "", 30, false, "none");

    private static SellerOfRecordPolicy.Facts facts(SellerOfRecordPolicy.ProductKind kind, boolean official,
                                                    SellerOfRecordPolicy.SellerType type, String state, String sub) {
        return new SellerOfRecordPolicy.Facts(kind, official, type, 7L, state, sub);
    }

    @Test
    void switchOffAlwaysPlatform() {
        SellerOfRecordPolicy.Decision d = SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, false, MERCHANT, "FINISH", "1900000101"),
                PayoutSwitches.allOff());
        assertFalse(d.subMerchant());
        assertEquals("SWITCH_OFF", d.reason());
        // 总开关开但路由开关关
        PayoutSwitches noRouting = new PayoutSwitches(true, false, true, "1900000001", "wxsp", "", 30, false, "none");
        assertEquals("SWITCH_OFF", SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, false, MERCHANT, "FINISH", "1900000101"), noRouting).reason());
        // 缺 sp_appid 时路由视为关闭
        PayoutSwitches noAppid = new PayoutSwitches(true, true, true, "1900000001", "", "", 30, false, "none");
        assertEquals("SWITCH_OFF", SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, false, MERCHANT, "FINISH", "1900000101"), noAppid).reason());
    }

    @Test
    void onboardedMerchantAndClubTicketsUseSubMchid() {
        SellerOfRecordPolicy.Decision m = SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, false, MERCHANT, "FINISH", "1900000101"), ON);
        assertTrue(m.subMerchant());
        assertEquals("1900000101", m.subMchid());
        SellerOfRecordPolicy.Decision c = SellerOfRecordPolicy.decide(facts(ACTIVITY_TICKET, false, CLUB, "FINISH", "1900000102"), ON);
        assertTrue(c.subMerchant());
        assertEquals(CLUB, c.sellerType());
        assertTrue(SellerOfRecordPolicy.decide(facts(CLUB_DUES, false, CLUB, "FINISH", "1900000102"), ON).subMerchant());
    }

    @Test
    void officialThemesAndPlatformProductsStayPlatform() {
        assertEquals("OFFICIAL_THEME", SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, true, MERCHANT, "FINISH", "1900000101"), ON).reason());
        for (SellerOfRecordPolicy.ProductKind k : new SellerOfRecordPolicy.ProductKind[]{PLATFORM_PASS, MARKETING_PRODUCT,
                WORKSHOP_TEMPLATE, MERCHANT_PURCHASE, COOP_DEPOSIT}) {
            assertEquals("PLATFORM_PRODUCT", SellerOfRecordPolicy.decide(facts(k, false, MERCHANT, "FINISH", "1900000101"), ON).reason());
        }
    }

    @Test
    void anyUncertaintyFallsBackToPlatform() {
        assertEquals("SELLER_NOT_MERCHANT_OR_CLUB", SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, false, MEMBER, "FINISH", "1900000101"), ON).reason());
        assertEquals("SELLER_NOT_ONBOARDED", SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, false, MERCHANT, "AUDITING", null), ON).reason());
        assertEquals("SELLER_NOT_ONBOARDED", SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, false, MERCHANT, "FROZEN", "1900000101"), ON).reason());
        assertEquals("SELLER_NOT_ONBOARDED", SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, false, MERCHANT, "FINISH", "abc"), ON).reason());
        assertEquals("SUB_MCHID_IS_PLATFORM", SellerOfRecordPolicy.decide(facts(TOPIC_TICKET, false, MERCHANT, "FINISH", "1900000001"), ON).reason());
        assertEquals("NO_FACTS", SellerOfRecordPolicy.decide(null, ON).reason());
        assertEquals("SELLER_UNKNOWN", SellerOfRecordPolicy.decide(new SellerOfRecordPolicy.Facts(TOPIC_TICKET, false, MERCHANT, null, "FINISH", "1900000101"), ON).reason());
    }

    @Test
    void applymentStateTransitions() {
        assertEquals(ApplymentState.NEED_SIGN, ApplymentState.fromWechat("NEED_SIGN"));
        assertEquals(ApplymentState.FINISH, ApplymentState.fromWechat("APPLYMENT_STATE_FINISHED".replace("FINISHED", "FINISH")));
        assertEquals(null, ApplymentState.fromWechat("DRAFT"));
        assertEquals(null, ApplymentState.fromWechat("WHATEVER"));
        assertTrue(ApplymentState.AUDITING.canMoveTo(ApplymentState.FINISH));
        assertTrue(ApplymentState.FINISH.canMoveTo(ApplymentState.FROZEN));
        assertFalse(ApplymentState.FINISH.canMoveTo(ApplymentState.AUDITING));
        assertFalse(ApplymentState.CANCELED.canMoveTo(ApplymentState.FINISH));
        assertTrue(ApplymentState.FROZEN.canMoveTo(ApplymentState.FINISH));
        assertFalse(ApplymentState.FROZEN.canMoveTo(ApplymentState.AUDITING));
    }

    @Test
    void platformReceiverDefaultsToSpMchid() {
        assertEquals("1900000001", ON.platformReceiverMchid());
        assertEquals("1900009999", new PayoutSwitches(true, true, true, "1900000001", "wx", "1900009999", 30, false, "none").platformReceiverMchid());
        assertFalse(new PayoutSwitches(true, true, true, "", "wx", "", 30, false, "none").ecommerceEnabled());
        assertFalse(new PayoutSwitches(false, false, false, "", "", "", 30, true, "none").creatorEnabled());
        assertTrue(new PayoutSwitches(false, false, false, "", "", "", 30, true, "acme").creatorEnabled());
    }
}
