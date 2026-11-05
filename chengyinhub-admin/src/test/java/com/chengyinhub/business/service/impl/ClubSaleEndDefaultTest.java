package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 第十轮 f2 第 4 项(j2 F-02):编辑器不上送售票截止;俱乐部城市定向主题的售票截止跟主题结束时间走。 */
class ClubSaleEndDefaultTest {

    private final Date end = new Date(1_800_000_000_000L);

    private CmsTopic clubCity() {
        CmsTopic t = new CmsTopic();
        t.setClubId(990004L);
        t.setProductType(ProductType.ORIENTEERING);
        t.setEndDate(end);
        return t;
    }

    @Test
    void clubCityThemeWithoutSaleEnd_sellsUntilThemeEnd() {
        CmsTopic t = clubCity();
        CmsTopicServiceImpl.defaultClubSaleEnd(t, null);
        assertEquals(end, t.getSaleEndAt());
    }

    @Test
    void givenSaleEndAndNonClubThemeAreLeftAlone_butClubFreeExploreGetsADeadline() {
        CmsTopic given = clubCity();
        Date chosen = new Date(end.getTime() - 86_400_000L);
        given.setSaleEndAt(chosen);
        CmsTopicServiceImpl.defaultClubSaleEnd(given, null);
        assertEquals(chosen, given.getSaleEndAt());

        CmsTopic member = clubCity();
        member.setClubId(null);
        CmsTopicServiceImpl.defaultClubSaleEnd(member, null);
        assertNull(member.getSaleEndAt());

        CmsTopic explore = clubCity();
        explore.setProductType(ProductType.FREE_EXPLORE);
        CmsTopicServiceImpl.defaultClubSaleEnd(explore, null);
        assertEquals(end, explore.getSaleEndAt());
    }

    @Test
    void editFollowsNewEndDate_unlessSaleEndWasSetSeparately() {
        CmsTopic exist = clubCity();
        exist.setSaleEndAt(end);
        CmsTopic patch = new CmsTopic();
        Date later = new Date(end.getTime() + 7 * 86_400_000L);
        patch.setEndDate(later);
        CmsTopicServiceImpl.defaultClubSaleEnd(patch, exist);
        assertEquals(later, patch.getSaleEndAt());

        exist.setSaleEndAt(new Date(end.getTime() - 3_600_000L));
        CmsTopic patch2 = new CmsTopic();
        patch2.setEndDate(later);
        CmsTopicServiceImpl.defaultClubSaleEnd(patch2, exist);
        assertNull(patch2.getSaleEndAt());
    }
}
