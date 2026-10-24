package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.ChapterDTO;
import com.chengyinhub.business.exception.ChapterFlowClientUpgradeRequiredException;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.support.ChapterRecruitmentRules;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import org.springframework.stereotype.Component;
import java.util.*;

/** Existing content eligibility/shape rules, retaining their short-circuit reads. */
@Component
public final class TopicContentWriteGuard {
    private final CmsTopicMapper cmsTopicMapper;
    private final CmsTopicChapterMapper cmsTopicChapterMapper;
    private final CmsMemberTemplateMapper cmsMemberTemplateMapper;
    private final IProductRoleService productRoleService;
    private final ISysCategoryService sysCategoryService;
    @org.springframework.beans.factory.annotation.Autowired
    private CmsTopicNodeMapper cmsTopicNodeMapper;

    public TopicContentWriteGuard(CmsTopicMapper topics, CmsTopicChapterMapper chapters,
            CmsMemberTemplateMapper templates, IProductRoleService roles, ISysCategoryService categories) {
        this.cmsTopicMapper = topics;
        this.cmsTopicChapterMapper = chapters;
        this.cmsMemberTemplateMapper = templates;
        this.productRoleService = roles;
        this.sysCategoryService = categories;
    }

    private static <T> List<T> nullToEmpty(List<T> rows) {
        return rows == null ? Collections.<T>emptyList() : rows;
    }

    /** The parent row is the shared fence; always acquire it before any chapter/node write or budget read. */
    public void lockTopicContent(Long topicId) {
        if (topicId == null || topicId <= 0L
                || !topicId.equals(cmsTopicMapper.lockTopicContentBudget(topicId))) {
            throw new IllegalStateException("主题不存在或已变化，拒绝修改内容");
        }
    }

    /** Current reads deliberately do not use player-visible lists or a transaction's old RR read view. */
    public void assertTopicSizeWithinLimits(Long topicId) {
        lockTopicContent(topicId);
        Long chapterCount = cmsTopicChapterMapper.countContentByTopicForUpdate(topicId);
        Long nodeCount = cmsTopicNodeMapper.countContentByTopicForUpdate(topicId);
        if (chapterCount == null || nodeCount == null || chapterCount < 0L || nodeCount < 0L) {
            throw new IllegalStateException("主题内容计数无法核实，拒绝发布或保存");
        }
        String violation = TopicSizeLimits.violation(Math.toIntExact(chapterCount), Math.toIntExact(nodeCount));
        if (violation != null) throw new IllegalStateException("发布前请先调整:" + violation);
    }

    /** blocks 已物化后，旧客户端不得再用 description + nodes 覆盖它。 */
    public void assertLegacyChapterFlowWritable(Long topicId) {
        CmsTopicChapter query = new CmsTopicChapter();
        query.setTopicId(topicId);
        for (CmsTopicChapter chapter : nullToEmpty(cmsTopicChapterMapper.selectCmsTopicChapterList(query))) {
            if (chapter.getBlocksJson() != null && !chapter.getBlocksJson().trim().isEmpty()) {
                throw new ChapterFlowClientUpgradeRequiredException("请更新小程序后编辑该主题");
            }
        }
    }

    public void assertChapterShapeForTopic(ChapterDTO chapter, Long topicId) {
        boolean direct = TermsMode.REVSHARE.equals(chapter.getTermsMode())
                && com.chengyinhub.business.support.ClubDirectMerchantPolicy.payerClubId(
                        cmsTopicMapper.selectCmsTopicById(topicId), productRoleService.activeContentSlot(topicId)) != null;
        assertChapterShape(chapter, direct);
    }

    /** 无主题上下文的形状检查仍不得替平台承诺计酬。 */
    private void assertChapterShape(ChapterDTO c) { assertChapterShape(c, false); }

    private void assertChapterShape(ChapterDTO c, boolean clubDirect) {
        // 章节背景旁白:列宽 512,超了 MySQL 会静默截断成一条点不开的坏地址,所以挡在写之前。
        String audioUrl = c.getAudioUrl();
        if (audioUrl != null && audioUrl.length() > 512) {
            throw new IllegalStateException("章节音频地址不能超过 512 字符");
        }
        Integer recruitEnabled = c.getRecruitEnabled();
        ChapterRecruitmentRules.assertRecruitEnabled(recruitEnabled);
        ChapterRecruitmentRules.assertMaxMerchant(c.getMaxMerchant());
        if (ChapterRecruitmentRules.isEnabled(recruitEnabled)) {
            SysCategory category = c.getCategoryId() == null ? null
                    : sysCategoryService.selectSysCategoryById(c.getCategoryId().longValue());
            ChapterRecruitmentRules.assertMerchantCategory(c.getCategoryId(), category);
        }
        String termsMode = c.getTermsMode();
        if (termsMode != null) {
            if (!TermsMode.isValid(termsMode)) {
                throw new IllegalStateException("章节条款档不合法:" + termsMode);
            }
            if (TermsMode.REVSHARE.equals(termsMode) && !clubDirect) {
                throw new IllegalStateException("计酬档须由普通自由探索的归属俱乐部直接付款");
            }
        }
        // ⚠️ 形状必须与后台 configureSupplyTerms 的口径**一致**:那边拒的组合这边也得拒,
        //    否则发布器能造出后台改都改不动的章(改一下就被形状校验拒),而运营找不到人背锅。
        //    ⇒ 非权益档不得带权益门槛(null 的 termsMode 按 PERK 算,与 DDL DEFAULT 同口径)。
        ChapterRecruitmentRules.assertPerkMinValueForTerms(termsMode, c.getPerkMinValue());
        if (TermsMode.REVSHARE.equals(termsMode)) {
            if (c.getMaxPerHeadFee() == null || c.getMaxPerHeadFee().signum() <= 0
                    || c.getMaxPerHeadFee().scale() > 2) {
                throw new IllegalStateException("请填写正数计酬人头费上限，最多两位小数");
            }
        } else if (c.getMaxPerHeadFee() != null) {
            throw new IllegalStateException("仅计酬档填写人头费上限");
        }
        assertAllowedValidationMethods(c.getAllowedValidationMethods());
        if (c.getMaxNodeXp() != null && c.getMaxNodeXp() < 0) {
            throw new IllegalStateException("单节点探索值上限不能为负");
        }
    }

    /** 只收 1-5 的逗号串(对齐 cms_member_template.validation_method 的取值域);空 = 不限。 */
    private void assertAllowedValidationMethods(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return;
        }
        for (String one : raw.split(",")) {
            String v = one.trim();
            if (!("1".equals(v) || "2".equals(v) || "3".equals(v) || "4".equals(v) || "5".equals(v))) {
                throw new IllegalStateException("允许的验证方式不合法:" + raw);
            }
        }
    }

    public void assertBranchGraphOnlyForOrienteering(String routeMode, Integer productType) {
        if ("BRANCH_GRAPH".equalsIgnoreCase(StringUtils.trim(routeMode))
                && !Integer.valueOf(ProductType.ORIENTEERING).equals(productType)) {
            throw new IllegalArgumentException("BRANCH_GRAPH 仅支持城市定向主题");
        }
    }

    /**
     * [W4] 节点只能挂**自己的、已审核的、非草稿的**模板。
     *
     * <p><b>先分清两套模板,别把这条误当「模板本来就是公共的」</b>:
     * <ul>
     *   <li>{@code CmsTemplateLibrary} = 公共模板库({@code /api/template/list} 与 {@code /info},
     *       按 status=1+publishStatus=1 过滤、<b>不看 memberId</b>)—— 那个确实人人可用。</li>
     *   <li>{@code CmsMemberTemplate} = 我的模板(publish 时绑 memberId)。<b>节点的 templateId 指的是它</b>
     *       ({@code ApiRegistrationMerchantController:88/179} 拿它查 CmsMemberTemplate),
     *       小程序发布页选模板调的也是 {@code /api/template/my-list}(fabu/index.js:3329)。</li>
     * </ul>
     * ⇒ 挂他人 CmsMemberTemplate 从来不是产品意图,只是后端没拦。
     *
     * <p><b>为什么不止判归属</b>:my-list 的引用分支({@code ApiTemplateController:216-224})取的是
     * <b>memberId=自己 + status=1 + draftStatus=1</b> 三条。只判归属会放过「自己的草稿/未审核模板」——
     * 那同样是绕过前端拿到的东西,审核闸会被架空。
     *
     * @param templateId 节点上送的模板 id;null / ≤0 = 不挂模板(前端默认就是 0),放行
     * @param memberId   当前建题人
     * @param allowBundledDraft 是否放行建题人自己的私有草稿(随主题组合送审)
     */
    public void assertTemplateOwned(Long templateId, Long memberId, boolean allowBundledDraft) {
        if (templateId == null || templateId <= 0L) {
            return;
        }
        com.chengyinhub.business.domain.CmsMemberTemplate tpl =
                cmsMemberTemplateMapper.selectCmsMemberTemplateById(templateId);
        if (tpl == null || (tpl.getDelFlag() != null && tpl.getDelFlag() == 2)) {
            throw new RuntimeException("模板不存在或已删除:" + templateId);
        }
        if (memberId == null || !memberId.equals(tpl.getMemberId())) {
            throw new RuntimeException("不能引用他人的模板:" + templateId);
        }
        boolean privateDraft = allowBundledDraft
                && Long.valueOf(0L).equals(tpl.getStatus())
                && Integer.valueOf(0).equals(tpl.getDraftStatus())
                && Integer.valueOf(0).equals(tpl.getCheckStatus())
                && Long.valueOf(0L).equals(tpl.getPublishStatus());
        if (privateDraft) {
            return;
        }
        if (tpl.getStatus() == null || tpl.getStatus() != 1L) {
            throw new RuntimeException("模板尚未通过审核,不能引用:" + templateId);
        }
        if (tpl.getDraftStatus() == null || tpl.getDraftStatus() != 1) {
            throw new RuntimeException("草稿模板不能引用,请先发布:" + templateId);
        }
    }
}
