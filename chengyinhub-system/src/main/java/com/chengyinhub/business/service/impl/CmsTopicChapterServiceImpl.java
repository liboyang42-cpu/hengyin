package com.chengyinhub.business.service.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import com.chengyinhub.common.utils.DateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.business.mapper.CmsTopicChapterMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicTemplateMetaMapper;
import com.chengyinhub.business.mapper.MerchantChapterApplicationMapper;
import com.chengyinhub.business.mapper.MerchantChapterOfferMapper;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.CmsTopicChapter;
import com.chengyinhub.business.service.support.CommercialTopicPolicy;
import com.chengyinhub.business.domain.MerchantChapterOffer;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import com.chengyinhub.business.domain.dto.ChapterSupplyTermsDTO;
import com.chengyinhub.business.domain.dto.ChapterServiceCapacityDTO;
import com.chengyinhub.business.domain.CmsTopicChapterTemplateMeta;
import com.chengyinhub.business.domain.vo.MerchantChapterRecruitmentStats;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.IProductRoleService;
import com.chengyinhub.business.service.ITopicOperationsScopeAuthorizer;
import com.chengyinhub.business.service.ITopicSaleCanonicalMutationService;
import com.chengyinhub.business.support.ChapterRecruitmentRules;
import com.chengyinhub.business.util.ChapterAtmospherePreset;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.constant.TermsMode;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;

/**
 * 主题章节Service业务层处理
 *
 * @author ruoyi
 * @date 2025-09-12
 */
@Service
public class CmsTopicChapterServiceImpl implements ICmsTopicChapterService
{
    private static final Logger log = LoggerFactory.getLogger(CmsTopicChapterServiceImpl.class);

    @Autowired
    private CmsTopicChapterMapper cmsTopicChapterMapper;

    @Autowired
    private CmsTopicMapper cmsTopicMapper;

    @Autowired
    private com.chengyinhub.business.service.support.TopicContentWriteGuard contentWriteGuard;

    @Autowired
    private CmsTopicTemplateMetaMapper cmsTopicTemplateMetaMapper;

    @Autowired
    private ISysCategoryService sysCategoryService;

    /** [D21] 改档前查该章还有没有生效供给 —— 有就不许改(改了会让存量 offer 的条款与章节口径打架) */
    @Autowired
    private MerchantChapterOfferMapper merchantChapterOfferMapper;

    /** 章节删除前须先确认没有未归档申请，不能留孤儿审核记录。 */
    @Autowired
    private MerchantChapterApplicationMapper merchantChapterApplicationMapper;

    /** [D21] 计酬档只对 ③-自营 开放,判据在这里(fail-closed) */
    @Autowired
    private IProductRoleService productRoleService;

    /** 条款配置下调名额时，复用申请 + 生效供给的去重占位口径。 */
    @Autowired
    private IMerchantChapterApplicationService merchantChapterApplicationService;

    /** Controller 不是可信边界；所有写入口必须在 service 内再次校验主题操作范围。 */
    @Autowired
    private ITopicOperationsScopeAuthorizer topicOperationsScopeAuthorizer;

    @Autowired(required = false)
    private ITopicSaleCanonicalMutationService canonicalMutationService;

    /** 9-15 拍板:已发布免费主题不能靠开章节招募转成商业主题。 */
    @Autowired
    private CommercialTopicPolicy commercialTopicPolicy;

    /**
     * 查询主题章节
     * 
     * @param id 主题章节主键
     * @return 主题章节
     */
    @Override
    public int setChapterRecruitAsOwner(Long chapterId, Long memberId, Integer enabled)
    {
        if (Integer.valueOf(1).equals(enabled)) {
            CmsTopicChapter chapter = cmsTopicChapterMapper.selectCmsTopicChapterById(chapterId);
            CmsTopic topic = chapter == null || chapter.getTopicId() == null ? null
                    : cmsTopicMapper.selectCmsTopicById(chapter.getTopicId());
            // 开招募本身就是商业事实(CommercialTopicPolicy.recruitmentOpen):已发布免费主题一律拒。
            // 只对归属人报此错;非归属人交给下面 owner-scoped SQL 返回 0,不暴露主题状态。
            if (topic != null && memberId != null && memberId.equals(topic.getMemberId())
                    && commercialTopicPolicy.isConversionProtected(topic)) {
                throw new IllegalStateException(CommercialTopicPolicy.FREE_TO_COMMERCIAL_REJECTED);
            }
        }
        return cmsTopicChapterMapper.setRecruitEnabledIfOwned(chapterId, memberId, enabled);
    }

    @Override
    public int finishChapterAsOwner(Long chapterId, Long memberId)
    {
        return cmsTopicChapterMapper.finishChapterIfOwned(chapterId, memberId);
    }

    @Override
    public CmsTopicChapter selectCmsTopicChapterById(Long id)
    {
        return cmsTopicChapterMapper.selectCmsTopicChapterById(id);
    }

    /**
     * 查询主题章节列表
     * 
     * @param cmsTopicChapter 主题章节
     * @return 主题章节
     */
    @Override
    public List<CmsTopicChapter> selectCmsTopicChapterList(CmsTopicChapter cmsTopicChapter)
    {
        return cmsTopicChapterMapper.selectCmsTopicChapterList(cmsTopicChapter);
    }

    /**
     * 新增主题章节
     *
     * <p>⚠️ <b>本方法不许直接建计酬档</b>。它是通用 CRUD 入口,收整个实体 ——
     * 不挡的话,一个 {@code POST /business/topicchapter {"topicId":X,"termsMode":"REVSHARE"}}
     * 就能在**非自营**主题上建出计酬档章节,把 {@link #configureSupplyTerms} 的自营闸整个绕过去。
     * 计酬档一律走 {@link #configureSupplyTerms}(先建成权益/引流档,再改判)。
     *
     * @param cmsTopicChapter 主题章节
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int insertCmsTopicChapter(CmsTopicChapter cmsTopicChapter)
    {
        assertNotCreatingRevshare(cmsTopicChapter);
        CmsTopic topic = assertTopicMutable(cmsTopicChapter == null ? null : cmsTopicChapter.getTopicId());
        assertRecruitmentConfigForInsert(cmsTopicChapter, topic);
        // 9-15 拍板:已发布免费主题不能转商业。开招募本身就是商业事实(recruitmentOpen),
        // 后台通用 CRUD 此前是漏口;写后复核,平台自营主题豁免。
        Long topicId = cmsTopicChapter.getTopicId();
        boolean conversionProtected = conversionProtectedFor(topicId);
        cmsTopicChapter.setAtmospherePreset(
                ChapterAtmospherePreset.requireValid(cmsTopicChapter.getAtmospherePreset()));
        cmsTopicChapter.setCreateTime(DateUtils.getNowDate());
        int rows = cmsTopicChapterMapper.insertCmsTopicChapter(cmsTopicChapter);
        if (rows > 0) {
            contentWriteGuard.assertTopicSizeWithinLimits(topicId);
            invalidateApprovedCanonical(topicId, "chapter-insert");
        }
        commercialTopicPolicy.assertNotConvertedToCommercial(topicId, conversionProtected);
        return rows;
    }

    /**
     * 通用 add 路径上的条款形状闸。
     *
     * <p>与 {@link #assertNotChangingSupplyTerms} 是**一对**:同一道闸的两个入口,只堵一个等于没堵。
     * 建章时主题**可能**已经是自营(后台发主题会声明 PLATFORM 槽),照理可以当场校验后放行 ——
     * 但那样就有了第二处「计酬档准入」判据,而 D21 的资格判定还牵着「该章有没有生效供给」
     * (见 configureSupplyTerms)。<b>两处判据迟早会漂</b>,不如收成一处:通用新增只允许
     * PERK/TRAFFIC 的自洽形状，计酬档及费用上限一律改走专用配置入口。
     */
    private void assertNotCreatingRevshare(CmsTopicChapter chapter)
    {
        if (chapter == null) {
            return;
        }
        String termsMode = chapter.getTermsMode();
        if (termsMode != null && !TermsMode.isValid(termsMode)) {
            throw new IllegalStateException("章节条款档不合法:" + termsMode);
        }
        if (TermsMode.REVSHARE.equals(termsMode)) {
            throw new IllegalStateException("计酬档不能在建章时直接设置(它决定平台要给商家掏多少钱);"
                    + "请先建成权益档/引流档,再走「改判条款档」入口 PUT /business/topicchapter/{id}/supply-terms");
        }
        ChapterRecruitmentRules.assertPerkMinValueForTerms(termsMode, chapter.getPerkMinValue());
        if (chapter.getMaxPerHeadFee() != null) {
            throw new IllegalStateException("只有计酬档谈人头费上限;通用建章请留空");
        }
    }

    /**
     * 修改主题章节
     *
     * <p>⚠️ <b>本方法不许改那四个供给条款列</b>(档位/权益门槛/人头费上限/商家上限)——
     * 它是通用 CRUD 入口、收整个实体,而 mapper 的 update 里这四列都有 {@code <if test="xx != null">}。
     * 不挡的话,一个 {@code PUT /business/topicchapter {"id":1,"termsMode":"REVSHARE"}} 就把
     * {@link #configureSupplyTerms} 的四道闸(档位合法 / 形状自洽 / 仅 ③-自营 / 无生效供给)
     * **整个绕过去**,且零告警。
     *
     * @param cmsTopicChapter 主题章节
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateCmsTopicChapter(CmsTopicChapter cmsTopicChapter)
    {
        CmsTopicChapter current = lockAndAssertChapterMutable(
                cmsTopicChapter == null ? null : cmsTopicChapter.getId());
        assertNotChangingSupplyTerms(cmsTopicChapter, current);
        assertRecruitmentConfigForUpdate(cmsTopicChapter, current);
        // 同 insert:通用编辑也能开招募/换品类,必须走同一道「已发布免费主题禁转商业」写后复核。
        Long topicId = current.getTopicId();
        boolean conversionProtected = conversionProtectedFor(topicId);
        if (cmsTopicChapter.getAtmospherePreset() != null) {
            cmsTopicChapter.setAtmospherePreset(
                    ChapterAtmospherePreset.requireValid(cmsTopicChapter.getAtmospherePreset()));
        }
        cmsTopicChapter.setUpdateTime(DateUtils.getNowDate());
        int rows = cmsTopicChapterMapper.updateCmsTopicChapter(cmsTopicChapter);
        if (rows > 0) {
            invalidateApprovedCanonical(topicId, "chapter-update");
        }
        commercialTopicPolicy.assertNotConvertedToCommercial(topicId, conversionProtected);
        return rows;
    }

    /**
     * 通用 edit 路径上的**供给条款旁路闸** —— 四个字段一个都不许从这儿改。
     *
     * <p>只挡这四个,是因为它们是<b>一套形状</b>(见 {@link #assertTermsShape}):
     * 从这条路单改其中一个,就能配出 enroll 不认的组合 —— 比如给引流档留个人头费上限、
     * 或者把计酬档的上限清空(⇒ 该章商家入驻恒被拒),而且**完全不经**自营闸和存量供给闸。
     *
     * <p>只在**真的要改**时拒(送来的值与库里当前值不同)。原样回传同一个**合法**值是放行的 ——
     * 若依的表单习惯把整个实体回传,一律拒会把「改个章节名」这种正常编辑也拒掉。历史非法名额
     * 则不得借「原样回传」再次写入。
     */
    private void assertNotChangingSupplyTerms(CmsTopicChapter patch, CmsTopicChapter current)
    {
        if (patch == null || patch.getId() == null) {
            return;
        }
        boolean touches = patch.getTermsMode() != null || patch.getPerkMinValue() != null
                || patch.getMaxPerHeadFee() != null || patch.getMaxMerchant() != null;
        if (!touches) {
            return;
        }
        // 通用 mapper 会把非 null 的 maxMerchant 原样写回；不能让历史 -1 之类的值借
        // 「与当前相同」的分支再次落库。null 仍表示本次不写该列，保留读存量的兼容性。
        if (patch.getMaxMerchant() != null) {
            ChapterRecruitmentRules.assertMaxMerchant(patch.getMaxMerchant());
        }
        if (current == null) {
            return;   // 章节不存在,交给下面的 update 返 0,别在这儿抢着报错
        }
        String effectiveTermsMode = patch.getTermsMode() == null ? current.getTermsMode() : patch.getTermsMode();
        ChapterRecruitmentRules.assertPerkMinValueForTerms(effectiveTermsMode, patch.getPerkMinValue());
        assertUnchanged("条款档", patch.getTermsMode(), current.getTermsMode());
        assertUnchanged("权益门槛", patch.getPerkMinValue(), current.getPerkMinValue());
        assertUnchanged("人头费上限", patch.getMaxPerHeadFee(), current.getMaxPerHeadFee());
        assertUnchanged("商家上限", patch.getMaxMerchant(), current.getMaxMerchant());
    }

    /**
     * ⚠️ BigDecimal 必须用 compareTo 判等,不能用 equals —— 后者连标度一起比,
     * 「5」和「5.00」会被判成不同 ⇒ 表单原样回传就被误拒。
     */
    private void assertUnchanged(String label, Object sent, Object current)
    {
        if (sent == null) {
            return;   // 没送这个字段 = 不打算改它
        }
        boolean same = (sent instanceof java.math.BigDecimal && current instanceof java.math.BigDecimal)
                ? ((java.math.BigDecimal) sent).compareTo((java.math.BigDecimal) current) == 0
                : sent.equals(current);
        if (!same) {
            throw new IllegalStateException(label + "不能在这里改(它决定这一章的商家怎么参与、平台掏不掏钱);"
                    + "请走「配置供给条款」入口 PUT /business/topicchapter/{id}/supply-terms");
        }
    }

    /**
     * 后台通用 CRUD 也能改 recruit_enabled/category_id/topic_id，必须与小程序发布入口同口径。
     * 其中 topic_id 也算配置的一部分：已开放章节被挪到①②主题，同样会绕过「仅自由探索」闸。
     */
    private void assertRecruitmentConfigForInsert(CmsTopicChapter chapter, CmsTopic topic)
    {
        if (chapter == null) {
            return;
        }
        ChapterRecruitmentRules.assertRecruitEnabled(chapter.getRecruitEnabled());
        ChapterRecruitmentRules.assertMaxMerchant(chapter.getMaxMerchant());
        if (ChapterRecruitmentRules.isEnabled(chapter.getRecruitEnabled())) {
            assertRecruitmentAllowed(topic, chapter.getCategoryId());
        }
    }

    private void assertRecruitmentConfigForUpdate(CmsTopicChapter patch, CmsTopicChapter current)
    {
        if (patch == null || patch.getId() == null) {
            return;
        }
        boolean touchesRecruitment = patch.getRecruitEnabled() != null
                || patch.getCategoryId() != null || patch.getTopicId() != null;
        if (!touchesRecruitment) {
            return;
        }
        if (current == null) {
            return;
        }
        if (patch.getTopicId() != null && !java.util.Objects.equals(patch.getTopicId(), current.getTopicId())) {
            throw new IllegalStateException("章节不能在通用编辑中更换主题；如需迁移必须走包含申请和供给数据的一致性迁移流程");
        }
        ChapterRecruitmentRules.assertRecruitEnabled(patch.getRecruitEnabled());
        Integer recruitEnabled = patch.getRecruitEnabled() == null
                ? current.getRecruitEnabled() : patch.getRecruitEnabled();
        // 不能借通用编辑把已有 V1 申请/供给的章节关掉：MerchantOfferService 会随即退回
        // 主题级旧中标授权，造成未通过本章节审核或品类校验的商家绕过章节闸入驻。
        if (ChapterRecruitmentRules.isEnabled(current.getRecruitEnabled())
                && !ChapterRecruitmentRules.isEnabled(recruitEnabled)) {
            int applicationCount = merchantChapterApplicationMapper.countCurrentByChapter(current.getId());
            int offerCount = merchantChapterOfferMapper.countAllByChapter(current.getId());
            if (applicationCount > 0 || offerCount > 0) {
                throw new IllegalStateException("章节已有" + applicationCount + "条商家申请和" + offerCount
                        + "条供给记录，不能关闭商家承接；如需停止新申请请保留现有授权并走专用流程");
            }
        }
        if (!ChapterRecruitmentRules.isEnabled(recruitEnabled)) {
            return;
        }
        ChapterRecruitmentRules.assertV1RecruitmentTermsMode(current.getTermsMode(),
                directPayer(current.getTopicId()) != null);
        ChapterRecruitmentRules.assertPerkMinValueForTerms(current.getTermsMode(), current.getPerkMinValue());
        Integer maxMerchant = patch.getMaxMerchant() == null
                ? current.getMaxMerchant() : patch.getMaxMerchant();
        ChapterRecruitmentRules.assertMaxMerchant(maxMerchant);
        Long topicId = current.getTopicId();
        Integer categoryId = patch.getCategoryId() == null ? current.getCategoryId() : patch.getCategoryId();
        assertRecruitmentAllowed(topicId, categoryId);
    }

    private void assertRecruitmentAllowed(Long topicId, Integer categoryId)
    {
        // 与主题软删对同一父主题行串行：先拿到删除锁的写入会在这里读到 del_flag=2 并被拒；
        // 先拿到本锁的建章会完整提交后才允许软删，避免出现“删后才开启招商”的交错状态。
        // 本方法只由 insert/update 两个事务入口调用，锁会覆盖校验到章节写入的整个窗口。
        CmsTopic topic = topicId == null ? null : cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        assertRecruitmentAllowed(topic, categoryId);
    }

    private void assertRecruitmentAllowed(CmsTopic topic, Integer categoryId)
    {
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag()) || topic.getProductType() == null
                || topic.getProductType() != ProductType.FREE_EXPLORE) {
            throw new IllegalStateException("仅自由探索主题可开启章节商家承接");
        }
        SysCategory category = categoryId == null ? null
                : sysCategoryService.selectSysCategoryById(categoryId.longValue());
        ChapterRecruitmentRules.assertMerchantCategory(categoryId, category);
    }

    /**
     * 写前快照:该主题是否属于「已发布免费主题」(9-15 拍板禁转商业)。平台自营主题豁免,
     * 判据与 D21 计酬闸同口径(isPlatformOwned 无内容槽 fail-closed 当第三方)。
     */
    private boolean conversionProtectedFor(Long topicId)
    {
        if (topicId == null || productRoleService.isPlatformOwned(topicId)) {
            return false;
        }
        CmsTopic topic = cmsTopicMapper.selectCmsTopicById(topicId);
        return topic != null && commercialTopicPolicy.isConversionProtected(topic);
    }

    /**
     * 批量删除主题章节
     * 
     * @param ids 需要删除的主题章节主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteCmsTopicChapterByIds(Long[] ids)
    {
        List<Long> chapterIds = lockAndAssertChaptersMutable(ids);
        Set<Long> topicIds = topicIdsOf(chapterIds);
        assertRecruitmentRelationsAbsent(chapterIds);
        int rows = cmsTopicChapterMapper.deleteCmsTopicChapterByIds(ids);
        if (rows > 0) {
            for (Long topicId : topicIds) {
                invalidateApprovedCanonical(topicId, "chapter-delete-batch");
            }
        }
        return rows;
    }

    /**
     * 删除主题章节信息
     * 
     * @param id 主题章节主键
     * @return 结果
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteCmsTopicChapterById(Long id)
    {
        List<Long> chapterIds = lockAndAssertChaptersMutable(new Long[]{id});
        Set<Long> topicIds = topicIdsOf(chapterIds);
        assertRecruitmentRelationsAbsent(chapterIds);
        int rows = cmsTopicChapterMapper.deleteCmsTopicChapterById(id);
        if (rows > 0) {
            for (Long topicId : topicIds) {
                invalidateApprovedCanonical(topicId, "chapter-delete");
            }
        }
        return rows;
    }

    /**
     * 章节不是独立行：申请与 offer 都以 chapterId 为外键语义引用它，物理删章会把审核与核销历史断开。
     * 先以同一章节锁串行化 apply/audit/enroll，再查引用，避免“查为空后另一事务刚插入”的悬空窗。
     */
    private void assertRecruitmentRelationsAbsent(List<Long> chapterIds)
    {
        if (chapterIds == null || chapterIds.isEmpty()) {
            return;
        }
        for (Long chapterId : chapterIds) {
            int applicationCount = merchantChapterApplicationMapper.countCurrentByChapter(chapterId);
            int offerCount = merchantChapterOfferMapper.countAllByChapter(chapterId);
            if (applicationCount > 0 || offerCount > 0) {
                throw new IllegalStateException("章节已有" + applicationCount + "条商家申请和" + offerCount
                        + "条供给记录，不能直接删除；请先完成归档或显式一致性迁移");
            }
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void configureSupplyTerms(Long chapterId, ChapterSupplyTermsDTO terms)
    {
        if (terms == null) {
            throw new IllegalStateException("缺少供给条款配置");
        }
        String termsMode = terms.getTermsMode();
        if (!TermsMode.isValid(termsMode)) {
            throw new IllegalStateException("条款档不合法:" + termsMode + "(须为 PERK/REVSHARE/TRAFFIC)");
        }
        assertTermsShape(terms);
        CmsTopicChapter chapter = lockAndAssertChapterMutable(chapterId);
        if (ChapterRecruitmentRules.isEnabled(chapter.getRecruitEnabled())) {
            ChapterRecruitmentRules.assertV1RecruitmentTermsMode(termsMode, directPayer(chapter.getTopicId()) != null);
        }
        applySupplyTerms(chapterId, chapter, terms);
    }

    /**
     * 主办方在工作台改某一站的档位(2026-10-31 业主:招商在发布之后做,逐站一个档位)。
     * <p>与后台 {@link #configureSupplyTerms} 同一套形状 / 改档 / 容量 / 计酬闸({@link #applySupplyTerms}),
     * 只是把后台的数据范围闸换成<b>锁内归属校验</b>:先锁主题内容再 FOR UPDATE 读主题,
     * 归属人不是 memberId 就拒 —— 新写接口的 owner 校验落在锁里,不是先读后写。
     * <p>主办侧一律按开放招商 V1 口径:只有权益 / 引流,俱乐部自己发的自由探索(直付主体 = 俱乐部)才多一档计酬。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void configureSupplyTermsAsOwner(Long chapterId, Long memberId, ChapterSupplyTermsDTO terms)
    {
        if (terms == null) {
            throw new IllegalStateException("缺少供给条款配置");
        }
        if (memberId == null || memberId <= 0) {
            throw new IllegalStateException("请先登录");
        }
        String termsMode = terms.getTermsMode();
        if (!TermsMode.isValid(termsMode)) {
            throw new IllegalStateException("条款档不合法:" + termsMode + "(须为 PERK/REVSHARE/TRAFFIC)");
        }
        assertTermsShape(terms);
        CmsTopicChapter locator = chapterId == null || chapterId <= 0 ? null
                : cmsTopicChapterMapper.selectCmsTopicChapterById(chapterId);
        if (locator == null || locator.getTopicId() == null) throw new IllegalStateException("章节不存在");
        Long topicId = locator.getTopicId();
        contentWriteGuard.lockTopicContent(topicId);
        CmsTopic topic = cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag()) || topic.getLifecycle() == null) {
            throw new IllegalStateException("主题不存在、已删除或生命周期未知，拒绝修改章节");
        }
        if (!memberId.equals(topic.getMemberId())) {
            throw new IllegalStateException("无权操作该主题");
        }
        TopicLifecycleState lifecycle;
        try {
            lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("主题不存在、已删除或生命周期未知，拒绝修改章节");
        }
        if (lifecycle.isSaleStarted()) {
            throw new IllegalStateException("主题已开售，这一站的档位已冻结");
        }
        CmsTopicChapter chapter = cmsTopicChapterMapper.selectContentByIdForUpdate(chapterId);
        if (chapter == null || !java.util.Objects.equals(topicId, chapter.getTopicId())) {
            throw new IllegalStateException("章节归属已变化，请刷新后重试");
        }
        ChapterRecruitmentRules.assertV1RecruitmentTermsMode(termsMode, directPayer(topicId) != null);
        applySupplyTerms(chapterId, chapter, terms);
    }

    /** 两条入口(后台 / 主办)共用的改档主体:调用方已持锁并校验过范围或归属。 */
    private void applySupplyTerms(Long chapterId, CmsTopicChapter chapter, ChapterSupplyTermsDTO terms)
    {
        String termsMode = terms.getTermsMode();

        // ★ 闸:D21 —— 计酬档只对 ③-自营 开放。第三方的计酬 = 第三方间分钱 = 二清,须等收付通(P0-43)。
        //   isPlatformOwned 自己 fail-closed(查不到 CONTENT 槽即返 false)。
        //   ⚠️ 拒了的正确反应是**先用 P0-35 把内容槽改判成 PLATFORM**,不是把这道闸放宽。
        if (TermsMode.REVSHARE.equals(termsMode)
                && directPayer(chapter.getTopicId()) == null
                && !productRoleService.isPlatformOwned(chapter.getTopicId())) {
            throw new IllegalStateException("计酬档只能标在平台自营路线上;请先把该主题的内容槽改判为 PLATFORM");
        }

        // ★ 闸:**改档位**时该章不得有生效供给 —— 这条是**资损闸**,不是洁癖。
        //   容量闸按**章节**档位走(F1),而 offer 的档位是**入驻时的快照**(刻意的:
        //   已谈定的条款不被追溯改写)。把有 PERK 供给的章改成 REVSHARE ⇒ 容量闸对该章当场失效,
        //   可存量 PERK offer 仍按 quota 扣 ⇒ 那些商家承诺的接待上限失守 = 超发,且零告警。
        //   ⚠️ 反方向同样不安全(TRAFFIC/REVSHARE 章改成 PERK ⇒ 存量 offer 的 quota_total=0
        //   会让该章容量恒 0 ⇒ 整条路线卖不出票,正是 F1 那个 bug)。⇒ 两个方向一律先下供给。
        //
        // ⚠️ **只在档位真的变了时拦**:同档位下调上限/门槛不影响存量 offer(它们带的是入驻时的快照),
        //    拦了反而让运营没法给**已经在营**的章补配人头费上限 —— 而那恰恰是最需要补的场景
        //    (max_per_head_fee 是后加的列,存量计酬章全是 NULL = 商家一个都进不来)。
        boolean modeChanged = !termsMode.equals(nvlMode(chapter.getTermsMode()));
        if (modeChanged) {
            List<MerchantChapterOffer> live = merchantChapterOfferMapper.selectByChapter(chapterId);
            if (live != null && !live.isEmpty()) {
                throw new IllegalStateException("该章节还有 " + live.size()
                        + " 个生效商家供给,改档会让它们的条款与章节口径不一致;请先下掉这些供给再改档");
            }
        }

        assertCapDoesNotUndercutReservedMerchants(chapter, terms.getMaxMerchant());

        // ⚠️ 走专用的 updateSupplyTerms,**不能**走通用的 updateCmsTopicChapter:
        //    后者是 `<if test="xx != null">` 逐列拼的 ⇒ **传 null 只会跳过那一列,不会清空它**。
        //    于是「计酬档改成引流档」会把 max_per_head_fee 留在库里 —— 形状校验放行了(DTO 里是 null),
        //    库里却不是;等哪天这章改回计酬档,那个没人记得的旧上限会**静默生效**。
        //    可空条款列必须无条件覆盖，null 就是要写进去的值；max_merchant 则显式传 0 表示不限。
        cmsTopicChapterMapper.updateSupplyTerms(chapterId, termsMode, terms.getPerkMinValue(),
                terms.getMaxPerHeadFee(), terms.getMaxMerchant(), DateUtils.getNowDate());
        invalidateApprovedCanonical(chapter.getTopicId(), "chapter-supply-terms");

        // 改的是「这一章的钱怎么算」。@Log 记「谁改的」,这条记「值从什么变成了什么」—— 两者都要。
        // 与 P0-35 内容槽改判同一条纪律。
        log.warn("[D21] 章节供给条款改判 chapterId={} topicId={} 档位 {} -> {},人头费上限 {} -> {},"
                        + "权益门槛 {} -> {},商家上限 {} -> {}",
                chapterId, chapter.getTopicId(), chapter.getTermsMode(), termsMode,
                chapter.getMaxPerHeadFee(), terms.getMaxPerHeadFee(),
                chapter.getPerkMinValue(), terms.getPerkMinValue(),
                chapter.getMaxMerchant(), terms.getMaxMerchant());
    }

    @Override
    public ChapterServiceCapacityDTO getServiceCapacity(Long chapterId)
    {
        CmsTopicChapter chapter = chapterId == null ? null
                : cmsTopicChapterMapper.selectCmsTopicChapterById(chapterId);
        if (chapter == null || chapter.getTopicId() == null) {
            throw new IllegalStateException("章节不存在或缺少父主题");
        }
        assertTopicScope(chapter.getTopicId());
        CmsTopicChapterTemplateMeta meta = cmsTopicTemplateMetaMapper
                .selectChapterMetaByChapterId(chapterId);
        ChapterServiceCapacityDTO result = new ChapterServiceCapacityDTO();
        if (meta != null) {
            result.setBatchSize(meta.getBatchSize());
            result.setBatchIntervalMinutes(meta.getBatchIntervalMinutes());
            result.setEffortMaxConcurrent(meta.getEffortMaxConcurrent());
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void configureServiceCapacity(Long chapterId, ChapterServiceCapacityDTO capacity)
    {
        if (capacity == null) {
            throw new IllegalStateException("缺少章节服务容量配置");
        }
        assertPositiveCapacity("每批人数", capacity.getBatchSize());
        assertPositiveCapacity("批次间隔(分钟)", capacity.getBatchIntervalMinutes());
        assertPositiveCapacity("并行位", capacity.getEffortMaxConcurrent());
        CmsTopicChapter chapter = lockAndAssertChapterMutable(chapterId);
        cmsTopicTemplateMetaMapper.upsertChapterServiceCapacity(
                chapterId, chapter.getTopicId(), capacity.getBatchSize(),
                capacity.getBatchIntervalMinutes(), capacity.getEffortMaxConcurrent());
        invalidateApprovedCanonical(chapter.getTopicId(), "chapter-service-capacity");
    }

    @Override
    public CmsTopicChapterTemplateMeta getWorkPackage(Long chapterId)
    {
        CmsTopicChapter chapter = chapterId == null ? null
                : cmsTopicChapterMapper.selectCmsTopicChapterById(chapterId);
        if (chapter == null || chapter.getTopicId() == null) {
            throw new IllegalStateException("章节不存在或缺少父主题");
        }
        assertTopicScope(chapter.getTopicId());
        return cmsTopicTemplateMetaMapper.selectChapterMetaByChapterId(chapterId);
    }

    /**
     * F05 开售快照 merchantWorkPackage.workItems 从章节元数据读这六项,缺一项即
     * SALE_SNAPSHOT_MISSING_MERCHANT_WORK_PACKAGE;此前全仓没有任何写口,只能直接补库。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void configureWorkPackage(Long chapterId, CmsTopicChapterTemplateMeta workPackage)
    {
        if (workPackage == null) {
            throw new IllegalStateException("缺少章节商家工作包");
        }
        assertWorkFact("所需角色", workPackage.getRoleNeed());
        assertPositiveCapacity("人力(分钟)", workPackage.getEffortStaffMinutes());
        assertWorkFact("场地", workPackage.getEffortVenue());
        assertWorkFact("材料", workPackage.getEffortMaterials());
        assertWorkFact("可用时段", workPackage.getEffortAvailableSlots());
        assertWorkFact("资质", workPackage.getEffortQualifications());
        CmsTopicChapter chapter = lockAndAssertChapterMutable(chapterId);
        CmsTopicChapterTemplateMeta row = new CmsTopicChapterTemplateMeta();
        row.setChapterId(chapterId);
        row.setTopicId(chapter.getTopicId());
        row.setRoleNeed(workPackage.getRoleNeed().trim());
        row.setEffortStaffMinutes(workPackage.getEffortStaffMinutes());
        row.setEffortVenue(workPackage.getEffortVenue().trim());
        row.setEffortMaterials(workPackage.getEffortMaterials().trim());
        row.setEffortAvailableSlots(workPackage.getEffortAvailableSlots().trim());
        row.setEffortQualifications(workPackage.getEffortQualifications().trim());
        cmsTopicTemplateMetaMapper.upsertChapterWorkPackage(row);
        invalidateApprovedCanonical(chapter.getTopicId(), "chapter-work-package");
    }

    private void assertWorkFact(String field, String value)
    {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException(field + "必须填写");
        }
    }

    private void assertPositiveCapacity(String field, Integer value)
    {
        if (value == null || value <= 0) {
            throw new IllegalStateException(field + "必须填写正整数");
        }
    }

    private Set<Long> topicIdsOf(List<Long> chapterIds)
    {
        Set<Long> topicIds = new TreeSet<>();
        if (chapterIds != null) {
            for (Long chapterId : chapterIds) {
                CmsTopicChapter chapter = cmsTopicChapterMapper.selectCmsTopicChapterById(chapterId);
                if (chapter != null && chapter.getTopicId() != null) {
                    topicIds.add(chapter.getTopicId());
                }
            }
        }
        return topicIds;
    }

    private void invalidateApprovedCanonical(Long topicId, String mutation)
    {
        if (canonicalMutationService != null && topicId != null) {
            canonicalMutationService.invalidateApprovedCanonical(topicId, mutation);
        }
    }

    /** 库里 terms_mode 为 null 的存量行按 PERK 算(与 DDL DEFAULT 'PERK' 同口径) */
    private static String nvlMode(String mode)
    {
        return mode == null ? TermsMode.PERK : mode;
    }

    /**
     * 章节属于售卖目录的一部分。父主题加锁后统一检查 lifecycle，
     * 防止 controller、批量删除或条款专用入口绕过 SELLING 冻结。
     */
    private CmsTopic assertTopicMutable(Long topicId)
    {
        assertTopicScope(topicId);
        contentWriteGuard.lockTopicContent(topicId);
        CmsTopic topic = topicId == null ? null : cmsTopicMapper.selectCmsTopicByIdForUpdate(topicId);
        if (topic == null || Integer.valueOf(2).equals(topic.getDelFlag()) || topic.getLifecycle() == null) {
            throw new IllegalStateException("主题不存在、已删除或生命周期未知，拒绝修改章节");
        }
        TopicLifecycleState lifecycle;
        try {
            lifecycle = TopicLifecycleState.fromCode(topic.getLifecycle());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("主题不存在、已删除或生命周期未知，拒绝修改章节");
        }
        if (lifecycle.isSaleStarted()) {
            throw new IllegalStateException("主题已进入 SELLING，章节新增/编辑/删除/排序/供给条款/人工批次容量均已冻结");
        }
        return topic;
    }

    private Long directPayer(Long topicId) {
        return com.chengyinhub.business.support.ClubDirectMerchantPolicy.payerClubId(
                cmsTopicMapper.selectCmsTopicById(topicId), productRoleService.activeContentSlot(topicId));
    }

    /** Same topic → chapter order as publication; the locator is never accepted as current ownership proof. */
    private CmsTopicChapter lockAndAssertChapterMutable(Long chapterId)
    {
        CmsTopicChapter locator = chapterId == null || chapterId <= 0 ? null
                : cmsTopicChapterMapper.selectCmsTopicChapterById(chapterId);
        if (locator == null) throw new IllegalStateException("章节不存在");
        if (locator.getTopicId() == null) {
            throw new IllegalStateException("章节不存在或缺少父主题，拒绝修改");
        }
        Long expectedTopicId = locator.getTopicId();
        assertTopicMutable(expectedTopicId);
        CmsTopicChapter chapter = cmsTopicChapterMapper.selectContentByIdForUpdate(chapterId);
        if (chapter == null || chapter.getTopicId() == null) {
            throw new IllegalStateException("章节不存在或缺少父主题，拒绝修改");
        }
        if (!java.util.Objects.equals(expectedTopicId, chapter.getTopicId())) {
            throw new IllegalStateException("章节归属已变化，请刷新后重试");
        }
        return chapter;
    }

    /** Lock every parent in id order before any child; recheck each locator under the locks. */
    private List<Long> lockAndAssertChaptersMutable(Long[] ids)
    {
        if (ids == null || ids.length == 0) {
            throw new IllegalStateException("章节ID不能为空");
        }
        Set<Long> topicIds = new TreeSet<>();
        Set<Long> chapterIds = new TreeSet<>();
        for (Long id : ids) {
            if (id != null) {
                chapterIds.add(id);
            }
        }
        if (chapterIds.isEmpty()) {
            throw new IllegalStateException("章节ID不能为空");
        }
        List<Long> orderedChapterIds = new ArrayList<>(chapterIds);
        java.util.Map<Long, Long> expectedTopics = new java.util.TreeMap<>();
        for (Long chapterId : orderedChapterIds) {
            CmsTopicChapter chapter = cmsTopicChapterMapper.selectCmsTopicChapterById(chapterId);
            if (chapter == null || chapter.getTopicId() == null) {
                throw new IllegalStateException("章节不存在或缺少父主题，拒绝删除");
            }
            topicIds.add(chapter.getTopicId());
            expectedTopics.put(chapterId, chapter.getTopicId());
        }
        for (Long topicId : topicIds) {
            assertTopicMutable(topicId);
        }
        for (Long chapterId : orderedChapterIds) {
            CmsTopicChapter locked = cmsTopicChapterMapper.selectContentByIdForUpdate(chapterId);
            if (locked == null || !java.util.Objects.equals(expectedTopics.get(chapterId), locked.getTopicId())) {
                throw new IllegalStateException("章节归属已变化，请刷新后重试");
            }
        }
        return orderedChapterIds;
    }

    private void assertTopicScope(Long topicId)
    {
        if (topicId == null || topicOperationsScopeAuthorizer == null
                || !topicOperationsScopeAuthorizer.canViewTopic(topicId)) {
            throw new IllegalStateException("主题不存在或当前账号无操作范围");
        }
    }

    /**
     * 形状校验 —— <b>与 {@code MerchantOfferServiceImpl} 的三道章节级校验逐字对齐</b>。
     *
     * <p>后台的口径与 enroll 的口径必须是同一份:漂了就是**后台配得进、商家进不来**,
     * 而那时报错的是商家,不是配错的运营。三处出处:
     * <ul>
     *   <li>{@code assertFeeWithinChapterCap}:cap==null ⇒ <b>拒</b>(平台成本上界,留空=敞口无限);</li>
     *   <li>{@code assertPerkMeetsThreshold}:min==null ⇒ <b>本章不设门槛</b>(质量门槛,不设=不挑);</li>
     *   <li>{@code assertPersistableMaxMerchant}:0 ⇒ <b>不限</b>，专用写入口不得传 null，负数与大于127一律拒，且在档位分支**之外** ⇒ 三档通用。</li>
     * </ul>
     */
    private void assertTermsShape(ChapterSupplyTermsDTO t)
    {
        ChapterRecruitmentRules.assertPersistableMaxMerchant(t.getMaxMerchant());
        boolean revshare = TermsMode.REVSHARE.equals(t.getTermsMode());
        boolean perk = TermsMode.PERK.equals(t.getTermsMode());

        ChapterRecruitmentRules.assertPerkMinValueForTerms(t.getTermsMode(), t.getPerkMinValue());

        if (revshare) {
            // ⚠️ 不是「可选的保护」——空 = enroll 的 assertFeeWithinChapterCap 当场拒 ⇒
            //    这一章的计酬档商家**一个都进不来**。允许留空 = 允许运营配出一个死章。
            if (t.getMaxPerHeadFee() == null || t.getMaxPerHeadFee().signum() <= 0) {
                throw new IllegalStateException("计酬档必须配置每人次人头费上限(须大于 0)——"
                        + "留空的话商家入驻会被直接拒,这一章等于配了也没人能进");
            }
        } else if (t.getMaxPerHeadFee() != null) {
            throw new IllegalStateException("只有计酬档谈人头费上限,"
                    + (perk ? "权益档" : "引流档") + "请留空");
        }

    }

    /**
     * 审批已占坑，且入驻中的供给也占坑；把 cap 改小不能把问题拖到商家下一次入驻才暴露。
     * 当前章节行锁与审批/enroll 复用，读统计到写入条款期间不会有新的占位穿插进来。
     */
    private void assertCapDoesNotUndercutReservedMerchants(CmsTopicChapter chapter, Integer newCap)
    {
        if (newCap == null || newCap == 0) {
            return;
        }
        MerchantChapterRecruitmentStats stats = merchantChapterApplicationService
                .getRecruitmentStats(chapter.getId(), newCap);
        int reserved = stats == null || stats.getReservedMerchantCount() == null
                ? 0 : stats.getReservedMerchantCount();
        if (reserved > newCap) {
            throw new IllegalStateException("本章节已有已批准申请或生效供给占用" + reserved
                    + "家商家，不能把名额下调为" + newCap + "家");
        }
    }
}
