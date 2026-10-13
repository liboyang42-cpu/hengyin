package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.util.TemplateSecrets;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P0-B:「给商家/店员看的话」不许流到玩家眼前。
 *
 * <p>起因是现有 18 个具名玩法包的种子,把「商家改造指南:①把题面里的细节换成…」
 * 和店员剧本写在 {@code ruleInstructions} 里,而那一列会随节点出参渲染给玩家。
 * 「神秘店员的暗号」那一包最严重:剧本里写着「你回答『可惜看不见星星』」,
 * 而那句话正是本题答案 —— 等于题目自带答案。
 *
 * <p>分列之后,{@code merchantGuide} 必须在每一条整实体下发的路上被剥掉。
 */
class MerchantGuideNotLeakedTest {

    private static final String GUIDE =
            "店员一页纸剧本:①客人对你说「今晚的月亮不错」;②你回答「可惜看不见星星」,不要多解释。";

    private CmsMemberTemplate memberTemplate() {
        CmsMemberTemplate t = new CmsMemberTemplate();
        t.setTitle("玩法包·接头回令");
        t.setRuleInstructions("走到吧台,对店员说出暗号。");
        t.setMerchantGuide(GUIDE);
        t.setQuestionAnswer("可惜看不见星星");
        t.setHint1("找那个知情的人");
        return t;
    }

    private CmsTemplateLibrary libraryTemplate() {
        CmsTemplateLibrary t = new CmsTemplateLibrary();
        t.setTitle("玩法包·接头回令");
        t.setRuleInstructions("走到吧台,对店员说出暗号。");
        t.setMerchantGuide(GUIDE);
        t.setQuestionAnswer("可惜看不见星星");
        t.setHint1("找那个知情的人");
        return t;
    }

    @Test
    void memberTemplateStripRemovesMerchantGuide() {
        CmsMemberTemplate stripped = TemplateSecrets.strip(memberTemplate());

        assertNull(stripped.getMerchantGuide(),
                "店员剧本里带着本题回令,下发即泄题");
        assertNull(stripped.getQuestionAnswer(), "答案本来就该剥");
        assertNull(stripped.getHint1(), "提示本来就该剥");
    }

    @Test
    void libraryTemplateStripRemovesMerchantGuide() {
        CmsTemplateLibrary stripped = TemplateSecrets.strip(libraryTemplate());

        assertNull(stripped.getMerchantGuide(), "货架详情同样是整实体下发的路");
        assertNull(stripped.getQuestionAnswer(), "答案本来就该剥");
    }

    /** 剥的是商家执行说明,不是玩家规则 —— 后者是玩家需要看的,剥掉就没人知道怎么玩了。 */
    @Test
    void stripKeepsThePlayerFacingRuleText() {
        CmsMemberTemplate stripped = TemplateSecrets.strip(memberTemplate());

        assertEquals("走到吧台,对店员说出暗号。", stripped.getRuleInstructions(),
                "玩家规则要留着");
        assertEquals("玩法包·接头回令", stripped.getTitle());
    }

    /** 核销商家视角保留全部内容:那份说明本来就是写给他看的。 */
    @Test
    void merchantViewKeepsTheGuideBecauseItIsWrittenForThem() {
        CmsMemberTemplate seen = TemplateSecrets.merchantView(memberTemplate());

        assertEquals(GUIDE, seen.getMerchantGuide());
        assertEquals("可惜看不见星星", seen.getQuestionAnswer(), "核销要对答案");
    }
}
