package com.chengyinhub.business.sponsoredquest;

/**
 * The visible label every sponsored quest carries wherever a player sees it. Not stored, not editable:
 * 《互联网广告管理办法》第九条 requires paid content to be clearly marked 「广告」, so the label is fixed in code
 * and added to every player row by {@link SponsoredQuestService}. Whether 「赞助」 alone would suffice is an open
 * counsel question (see docs/roadmap/sponsored-quests/RESULT.md); until answered, 「广告」 is used.
 */
public final class SponsoredQuestLabel {
    public static final String ZH_HANS = "广告";
    public static final String EN = "Ad";

    private SponsoredQuestLabel() {
    }
}
