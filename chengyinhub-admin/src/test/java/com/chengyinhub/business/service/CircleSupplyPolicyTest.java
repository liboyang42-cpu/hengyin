package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantCircleSupplyProfile;
import com.chengyinhub.business.support.CircleSupplyPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 五类圈层供给必须只基于商家明确确认的事实，缺一项即失败关闭。 */
class CircleSupplyPolicyTest {
    private static final Date NOW = new Date(1_800_000_000_000L);

    @Test
    void commonSupplyMustBeARealPricedOfferOrExplicitZeroBrowse() {
        MerchantCircleSupplyProfile profile = validProfile();
        profile.setSupplyName(" ");
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("AFTER_WORK", "WORKDAY_EVENING", profile, NOW));

        MerchantCircleSupplyProfile zeroProfile = validProfile();
        zeroProfile.setSupplyKind("ZERO_BROWSE");
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("SOLO", "SOLO", zeroProfile, NOW));
        zeroProfile.setRegularPrice(BigDecimal.ZERO);
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("SOLO", "SOLO", zeroProfile, NOW));
    }

    @Test
    void afterWorkRequiresRealWeekdayEveningCoverage() {
        MerchantCircleSupplyProfile profile = validProfile();
        profile.setWorkdayEveningCovered(0);
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("AFTER_WORK", "AFTER_WORK", profile, NOW));
        profile.setWorkdayEveningCovered(1);
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("AFTER_WORK", "WORKDAY_EVENING", profile, NOW));
    }

    @Test
    void soloRequiresAnActualOnePersonPurchase() {
        MerchantCircleSupplyProfile profile = validProfile();
        profile.setSinglePersonAllowed(1);
        profile.setMinPeople(2);
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("SOLO", "SOLO", profile, NOW));
        profile.setMinPeople(1);
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("SOLO", "SOLO", profile, NOW));
    }

    @Test
    void parentChildRequiresAllEightMerchantDeclaredFacts() {
        MerchantCircleSupplyProfile valid = parentChildProfile();
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid(
                "PARENT_CHILD", "PARENT_CHILD", valid, NOW));

        MerchantCircleSupplyProfile noGuardian = parentChildProfile();
        noGuardian.setGuardianMode("无需监护人到场");
        noGuardian.setGuardianPresentThroughout(0);
        assertThrows(IllegalStateException.class, () -> CircleSupplyPolicy.assertValid(
                "PARENT_CHILD", "PARENT_CHILD", noGuardian, NOW));

        MerchantCircleSupplyProfile cannotLeave = parentChildProfile();
        cannotLeave.setGuardianMode("监护人必须全程在场、不可离场并承担监护责任");
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid(
                "PARENT_CHILD", "PARENT_CHILD", cannotLeave, NOW));

        MerchantCircleSupplyProfile merchantWatches = parentChildProfile();
        merchantWatches.setGuardianMode("监护人全程在场，商家负责看护，监护人承担监护责任");
        merchantWatches.setMerchantChildcareRequired(1);
        assertThrows(IllegalStateException.class, () -> CircleSupplyPolicy.assertValid(
                "PARENT_CHILD", "PARENT_CHILD", merchantWatches, NOW));

        for (java.util.function.Consumer<MerchantCircleSupplyProfile> missingOne
                : java.util.Arrays.<java.util.function.Consumer<MerchantCircleSupplyProfile>>asList(
                p -> p.setDeclaredAgeRange(null),
                p -> p.setGuardianMode(null),
                p -> p.setAdultChildCapacity(null),
                p -> p.setPriceUnit(null),
                p -> p.setRestrictions(null),
                p -> p.setToolsAnimalsMaterials(null),
                p -> p.setIncidentProcess(null),
                p -> p.setCancellationRule(null))) {
            MerchantCircleSupplyProfile profile = parentChildProfile();
            missingOne.accept(profile);
            MerchantCircleSupplyProfile invalid = profile;
            assertThrows(IllegalStateException.class, () -> CircleSupplyPolicy.assertValid(
                    "PARENT_CHILD", "PARENT_CHILD", invalid, NOW));
        }
    }

    @Test
    void ordinaryPhotoLocationsNeedExplicitPersonalPhotoRules() {
        MerchantCircleSupplyProfile profile = validProfile();
        profile.setPersonalPhotoAllowed(1);
        profile.setPhotoAllowedArea("一层消费区，不含后厨和其他客人");
        profile.setTripodPolicy("禁用");
        profile.setLightingPolicy("仅小型补光灯");
        profile.setPhotoDurationLimit("每桌20分钟");
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("PHOTO", "PHOTO_RULES", profile, NOW));

        profile.setPersonalPhotoAllowed(0);
        MerchantCircleSupplyProfile invalid = profile;
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("PHOTO", "PHOTO_RULES", invalid, NOW));

        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid(
                "PHOTO", "PHOTO_SERVICE", validProfile(), NOW));
    }

    @Test
    void nightSupplyExpiresAfterSevenDaysAndSingleEventsNeedFutureEnd() {
        MerchantCircleSupplyProfile profile = validProfile();
        profile.setNightHours("21:00—01:00");
        profile.setLastEntry("00:30");
        profile.setAlcoholInvolved(0);
        profile.setCheckedAt(new Date(NOW.getTime() - 8L * 24 * 60 * 60 * 1000));
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("NIGHT", "NIGHT", profile, NOW));

        profile.setCheckedAt(new Date(NOW.getTime() - 6L * 24 * 60 * 60 * 1000));
        profile.setSupplyKind("EVENT");
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("NIGHT", "NIGHT", profile, NOW));
        profile.setEventEndAt(new Date(NOW.getTime() + 60_000));
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("NIGHT", "NIGHT", profile, NOW));

        MerchantCircleSupplyProfile alcohol = validProfile();
        alcohol.setNightHours("21:00—01:00");
        alcohol.setLastEntry("00:30");
        alcohol.setAlcoholInvolved(1);
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("NIGHT", "NIGHT", alcohol, NOW));
        alcohol.setLegalDrinkingAge(21);
        alcohol.setLegalDrinkingAgeRule("仅面向达到当地法定饮酒年龄的成年人");
        alcohol.setAlcoholWarning("饮酒有风险，请勿酒驾，可随时结束行程");
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("NIGHT", "NIGHT", alcohol, NOW));
    }

    @Test
    void primaryDocumentThemesRequireReviewWithinThirtyDays() {
        MerchantCircleSupplyProfile profile = validProfile();
        profile.setCheckedAt(new Date(NOW.getTime() - 31L * 24 * 60 * 60 * 1000));
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("FITNESS", "COMMON", profile, NOW));
        profile.setCheckedAt(new Date(NOW.getTime() - 29L * 24 * 60 * 60 * 1000));
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("SHANGHAI", "COMMON", profile, NOW));
    }

    @Test
    void primaryThemesRequireDurationAndAGroupOrSplitBookingDeclaration() {
        MerchantCircleSupplyProfile missingDuration = validProfile();
        missingDuration.setDurationMinutes(null);
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("FRIENDS", "COMMON", missingDuration, NOW));

        MerchantCircleSupplyProfile profile = validProfile();
        profile.setMaxPeople(1);
        profile.setSplitBookingAllowed(0);
        MerchantCircleSupplyProfile cannotHostGroup = profile;
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("MIDLIFE", "COMMON", cannotHostGroup, NOW));
        profile.setSplitBookingAllowed(1);
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("MIDLIFE", "COMMON", cannotHostGroup, NOW));

        MerchantCircleSupplyProfile friends = validProfile();
        friends.setMaxPeople(4);
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("FRIENDS", "COMMON", friends, NOW));
        friends.setSplitBookingAllowed(1);
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("FRIENDS", "COMMON", friends, NOW));

        MerchantCircleSupplyProfile date = validProfile();
        date.setMinPeople(3);
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("DATE", "COMMON", date, NOW));
        date.setSplitBookingAllowed(1);
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("DATE", "COMMON", date, NOW));
        date.setMinPeople(1);
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("DATE", "COMMON", date, NOW));

        MerchantCircleSupplyProfile friendsMinimum = validProfile();
        friendsMinimum.setMinPeople(6);
        friendsMinimum.setMaxPeople(6);
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("FRIENDS", "COMMON", friendsMinimum, NOW));
        friendsMinimum.setSplitBookingAllowed(1);
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("FRIENDS", "COMMON", friendsMinimum, NOW));
        friendsMinimum.setMinPeople(1);
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid(
                "FRIENDS", "COMMON", friendsMinimum, NOW));
    }

    @Test
    void optionalBenefitNeedsItsOwnSourceValidityAndRedemptionRules() {
        MerchantCircleSupplyProfile profile = validProfile();
        profile.setOptionalBenefit("赠送一杯常规饮品");
        assertThrows(IllegalStateException.class,
                () -> CircleSupplyPolicy.assertValid("DATE", "COMMON", profile, NOW));
        profile.setBenefitRules("商家承担；领取后7天有效；到店报手机号核销");
        assertDoesNotThrow(() -> CircleSupplyPolicy.assertValid("DATE", "COMMON", profile, NOW));
    }

    private static MerchantCircleSupplyProfile validProfile() {
        MerchantCircleSupplyProfile profile = new MerchantCircleSupplyProfile();
        profile.setSupplyName("正常在售供给");
        profile.setSupplyKind("PRODUCT");
        profile.setRegularPrice(new BigDecimal("29.00"));
        profile.setActionType("BUY");
        profile.setActionValue("https://merchant.example/item/1");
        profile.setAvailableHours("每日10:00—22:00，以商家当日状态为准");
        profile.setMinPeople(1);
        profile.setMaxPeople(4);
        profile.setDurationMinutes(60);
        profile.setSplitBookingAllowed(0);
        profile.setCheckedAt(NOW);
        profile.setMerchantRules("沿用商家正常预约、取消和退款规则");
        profile.setWorkdayEveningCovered(1);
        profile.setSinglePersonAllowed(1);
        return profile;
    }

    private static MerchantCircleSupplyProfile parentChildProfile() {
        MerchantCircleSupplyProfile profile = validProfile();
        profile.setDeclaredAgeRange("6—12岁");
        profile.setGuardianMode("监护人必须全程在场并承担监护责任");
        profile.setGuardianPresentThroughout(1);
        profile.setMerchantChildcareRequired(0);
        profile.setAdultChildCapacity("1—2名成人，1—2名儿童");
        profile.setPriceUnit("按儿童计费");
        profile.setRestrictions("坚果过敏请勿参加");
        profile.setToolsAnimalsMaterials("涉及烤箱和热源，按商家现场规则使用");
        profile.setIncidentProcess("联系现场负责人和监护人，必要时联系急救");
        profile.setCancellationRule("提前24小时可改期一次");
        return profile;
    }
}
