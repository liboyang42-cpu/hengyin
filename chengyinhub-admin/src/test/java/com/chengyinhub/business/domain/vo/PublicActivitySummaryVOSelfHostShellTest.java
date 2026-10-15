package com.chengyinhub.business.domain.vo;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.CmsTopic;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-W5-08(a):info-to-user 的 activityList 标出主题自办活动壳,俱乐部「接下来」据此不把它当场次。 */
class PublicActivitySummaryVOSelfHostShellTest {

    private static CmsTopic topic(Long publisher, Long clubId, Integer memberOnly) {
        CmsTopic t = new CmsTopic();
        t.setId(30L);
        t.setMemberId(publisher);
        t.setClubId(clubId);
        t.setClubMemberOnly(memberOnly);
        return t;
    }

    private static CmsActivity activity(Long memberId, Long clubId) {
        CmsActivity a = new CmsActivity();
        a.setId(400L);
        a.setTopicId(30L);
        a.setMemberId(memberId);
        a.setClubId(clubId);
        return a;
    }

    @Test
    void publisherOwnedActivityWithoutClubIsTheShell() {
        assertTrue(PublicActivitySummaryVO.from(activity(7L, null), topic(7L, 9L, 0)).isSelfHostShell());
        assertTrue(PublicActivitySummaryVO.from(activity(7L, 0L), topic(7L, null, null)).isSelfHostShell());
    }

    @Test
    void memberOnlyTopicShellInheritsTheTopicClubAndIsStillTheShell() {
        assertTrue(PublicActivitySummaryVO.from(activity(7L, 9L), topic(7L, 9L, 1)).isSelfHostShell());
    }

    @Test
    void clubSessionsAndOtherPublishersAreNotTheShell() {
        assertFalse(PublicActivitySummaryVO.from(activity(7L, 9L), topic(7L, 9L, 0)).isSelfHostShell(),
                "公开主题下挂俱乐部的是俱乐部场次");
        assertFalse(PublicActivitySummaryVO.from(activity(8L, 9L), topic(7L, 9L, 1)).isSelfHostShell(),
                "别人开的场次");
        assertFalse(PublicActivitySummaryVO.from(activity(8L, null), topic(7L, 9L, 0)).isSelfHostShell());
        assertFalse(PublicActivitySummaryVO.from(activity(7L, null)).isSelfHostShell(), "无主题上下文不猜");
    }

    @Test
    void jsonCarriesTheFlag() throws Exception {
        String json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(PublicActivitySummaryVO.from(activity(7L, null), topic(7L, null, 0)));
        assertTrue(json.contains("\"selfHostShell\":true"), json);
        assertEquals(-1, json.indexOf("memberId"), "白名单投影不得因此带出发布者 id");
    }
}
