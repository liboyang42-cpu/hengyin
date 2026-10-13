package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsRegistrationMerchant;
import com.chengyinhub.business.domain.CmsTemplateLibrary;

/**
 * 玩法模板敏感字段出参剥离(答案、提示与高级玩法未揭示配置)。
 *
 * 背景:多处 app 端口把模板整实体下发,questionAnswer/correctAnswer 直接泄题
 * (绕过 /api/play/nodes 侧「敏感答案不下发」防作弊层,游玩答题可被抓包作弊),
 * hint1/hint2 则泄露积分解锁付费墙内的内容。
 *
 * 口径:模板 owner(编辑回填场景)与已通过承接关系校验的核销商家可见明文;
 * 其余任何出口 —— 公共库列表/详情、
 * 非本人 myinfo、报名单/主题详情附带的模板实体，以及主题节点携带的商家报名题目 ——
 * 一律经本工具剥离 questionAnswer/correctAnswer/hint1/hint2/answerReveal，并把
 * advancedConfigJson 裁成不含权重、未来分支和角色分配的能力摘要后再出。
 * 不用 @JsonIgnore:web 后台管理端与 owner 编辑回填仍需要这些字段。
 */
public final class TemplateSecrets {

    private TemplateSecrets() {
    }

    public static CmsMemberTemplate strip(CmsMemberTemplate t) {
        if (t == null) {
            return null;
        }
        // 商家执行说明里写的是「店员回令是…」「答案换成你店里的年份」这类东西,
        // 对玩家就是答案本身。玩家侧的节点出参是逐字段白名单、天然不带它,
        // 但这条路是整实体下发,不剥就漏。
        t.setMerchantGuide(null);
        t.setQuestionAnswer(null);
        t.setCorrectAnswer(null);
        t.setHint1(null);
        t.setHint2(null);
        t.setAnswerReveal(null);
        t.setAdvancedConfigJson(AdvancedGamePublicProjection.safeConfigJson(t.getAdvancedConfigJson()));
        return t;
    }

    /**
     * 商家核销视角:保留答案与提示。
     * 调用者必须先校验当前用户归属且承接已中标;本方法只定义出参可见性。
     */
    public static CmsMemberTemplate merchantView(CmsMemberTemplate t) {
        return t;
    }

    public static CmsTemplateLibrary strip(CmsTemplateLibrary t) {
        if (t == null) {
            return null;
        }
        t.setMerchantGuide(null);
        t.setQuestionAnswer(null);
        t.setCorrectAnswer(null);
        t.setHint1(null);
        t.setHint2(null);
        t.setAnswerReveal(null);
        t.setAdvancedConfigJson(AdvancedGamePublicProjection.safeConfigJson(t.getAdvancedConfigJson()));
        return t;
    }

    /** 商家报名自带一套题目，答案键同样不得随公开主题端点下发。 */
    public static CmsRegistrationMerchant strip(CmsRegistrationMerchant t) {
        if (t == null) {
            return null;
        }
        t.setQuestionAnswer(null);
        t.setCorrectAnswer(null);
        return t;
    }
}
