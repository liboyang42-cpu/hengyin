package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.service.support.TemplateAuditSnapshot;
import com.chengyinhub.business.service.support.TemplateLibraryRepublishReview;
import com.chengyinhub.business.mapper.CmsTemplateLibraryMapper;
import com.chengyinhub.business.mapper.AuditTaskMapper;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.business.domain.CmsTemplateLibrary;
import com.chengyinhub.business.domain.AuditTask;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.mapper.CmsMemberTemplateMapper;
import com.chengyinhub.business.util.QuestionOptionMedia;
import com.chengyinhub.business.util.CategoryPublicProjection;
import com.chengyinhub.business.util.TemplateSecrets;
import com.chengyinhub.business.domain.vo.CategoryTemplatesVO;
import com.chengyinhub.business.domain.vo.TemplateHomeVO;
import com.chengyinhub.business.domain.vo.TemplateLibraryVO;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.IAuditTaskService;
import com.chengyinhub.business.service.support.SensorChallengePolicy;
import com.chengyinhub.business.service.support.PreferenceResultEvaluator;
import com.chengyinhub.business.service.ICapabilityGuard;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.support.AdvancedGameConfigValidator;
import com.chengyinhub.business.service.support.RewardRulesValidator;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import io.swagger.v3.oas.annotations.media.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

// TemplatePublishServiceImpl.java
@Service
public class TemplatePublishServiceImpl implements ITemplatePublishService {

    private static final Logger logger = LoggerFactory.getLogger(TemplatePublishServiceImpl.class);

    private final PreferenceResultEvaluator preferenceEvaluator = new PreferenceResultEvaluator();
    private final AdvancedGameConfigValidator advancedGameConfigValidator = new AdvancedGameConfigValidator();
    private final RewardRulesValidator rewardRulesValidator = new RewardRulesValidator();

    /** X03:身份能力位的服务端强制点。闸放在 service 而不是 controller —— memberId 是入参、可 mock,
     *  留在 controller 就写不出行为测试(与 ICmsTopicService 那条注释同一理由)。 */
    @Autowired
    private ICapabilityGuard capabilityGuard;

    @Autowired
    private ICouponRewardAuthorizationService couponRewardAuthorizationService;

    @Autowired
    private ICmsMemberTemplateService memberTemplateService;

    @Autowired
    private CmsMemberTemplateMapper memberTemplateMapper;

    @Autowired
    private ICmsTemplateLibraryService templateLibraryService;

    @Autowired
    private ISysCategoryService categoryService;

    @Autowired
    private IAuditTaskService auditTaskService;
    @Autowired
    private CmsTemplateLibraryMapper libraryMapper;
    @Autowired
    private AuditTaskMapper auditTaskMapper;


    @Autowired
    private com.chengyinhub.business.service.support.AppMediaPublicationGuard mediaPublicationGuard;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long publishTemplateWithTransaction(CmsTemplatePublishRequest request, Long memberId, String username) {
        Date now = new Date();

        // 编辑既有模板:request 带 id 即为改写自己已发布的那一份。
        // 没有这条,商家承接玩法包之后只能"换掉"不能"改" —— 副本模板的归属已经是他的
        // (CmsTopicServiceImpl#copyTemplateForOwner 深拷贝过),却没有任何入口能改题面与答案。
        Long editingId = request.getId();
        boolean editing = editingId != null && editingId > 0;
        CmsMemberTemplate existing = null;
        if (editing) {
            existing = lockTemplate(editingId);
            if (existing == null
                    || (existing.getDelFlag() != null && existing.getDelFlag() != 0)
                    || !memberId.equals(existing.getMemberId())) {
                // 不区分"不存在"与"不是你的":区分了就等于给出一个模板 id 探测器
                throw new RuntimeException("模板不存在或无权修改");
            }
        }

        preserveAdoptionIdentity(request, existing);
        prepareClearFields(request, existing);
        validateSensorFields(request);
        normalizeAdvancedConfig(request, existing, true);
        normalizeAdoptedReward(request, existing);
        assertCapabilities(request, memberId);

        // 1. 保存到我的模板表
        CmsMemberTemplate memberTemplate = new CmsMemberTemplate();
        copyPropertiesToMemberTemplate(request, memberTemplate);
        assertTemplateRewardOwnership(memberId, memberTemplate, existing);
        mediaPublicationGuard.requireTemplate(memberId, memberTemplate, true);
        validatePreferenceForReview(memberTemplate);

        // 设置基本信息
        memberTemplate.setMemberId(memberId);
        if (editing) {
            // 出身信息由服务端保留:创建者与创建时间不接受客户端改写
            memberTemplate.setId(editingId);
            memberTemplate.setCreateBy(existing.getCreateBy());
            memberTemplate.setCreateTime(existing.getCreateTime());
        } else {
            memberTemplate.setCreateBy(username);
            memberTemplate.setCreateTime(now);
        }
        memberTemplate.setUpdateBy(username);
        memberTemplate.setUpdateTime(now);
        memberTemplate.setDelFlag(0);
        // 发布只表示“提交审核”，不能把内容审核和上架事实一并伪造。
        memberTemplate.setStatus(0L);
        memberTemplate.setCheckStatus(0);
        memberTemplate.setPublishStatus(0L);
        memberTemplate.setDraftStatus(1); // 已发布(非草稿)

        String activityCategoryids = memberTemplate.getActivityCategoryids();
        if (StringUtils.isNotBlank(activityCategoryids)) {
            memberTemplate.setCategoryId(Long.valueOf(activityCategoryids.split(",")[0].trim()));
        }

        // 如果是引用别人的模板，设置originalTemplateId
        if (request.getOriginalTemplateId() != null && request.getOriginalTemplateId() > 0) {
            memberTemplate.setOriginalTemplateId(request.getOriginalTemplateId());
        } else {
            memberTemplate.setOriginalTemplateId(0L);
        }



        if (editing) withdrawProjections(editingId, false, username);
        int memberResult = editing
                ? memberTemplateService.updateCmsMemberTemplate(memberTemplate)
                : memberTemplateService.insertCmsMemberTemplate(memberTemplate);

        if (memberResult <= 0) {
            throw new RuntimeException(editing ? "更新模板失败" : "保存到我的模板失败");
        }

        // 模板市场热度:从库模板起草而来的发布(originalTemplateId=库模板 id,publish/temp 链路)
        // 给来源库模板 use_num +1。历史数据里该 id 若不指向库行,UPDATE 命中 0 行,无副作用。
        // ⚠️ 只在新建时计数:同一份模板改一次加一次,热度就成了"谁改得勤"而不是"谁被采用得多"。
        // AUDIT-G2-5:且按会员首次采用幂等 —— 同一会员从同一库模板反复新建(含删了再建)只算一次。
        if (!editing && memberTemplate.getOriginalTemplateId() != null
                && memberTemplate.getOriginalTemplateId() > 0) {
            templateLibraryService.incrementUseNumOnFirstAdoption(
                    memberTemplate.getOriginalTemplateId(), memberId, memberTemplate.getId());
        }

        // 个人玩法先独立审核；模板广场只能在个人过审后走 market-submit。
        submitTemplateReview(requireActiveTemplate(memberTemplate.getId()));

        return memberTemplate.getId();
    }

    /**
     * 找这个会员模板在货架上尚未删除的那条投影;没有则返回 null。
     * 查法与 deleteTemplateWithSync 一致(按 memberTemplateId 列出后筛 delFlag=0)。
     */
    private CmsTemplateLibrary findLiveProjection(Long memberTemplateId) {
        for (CmsTemplateLibrary row : libraryMapper.selectByMemberTemplateForUpdate(memberTemplateId)) {
            if (Integer.valueOf(0).equals(row.getDelFlag())) return row;
        }
        return null;
    }

    private String templateAuditSnapshot(CmsMemberTemplate template) {
        return TemplateAuditSnapshot.personal(template);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int createAdminTemplate(CmsMemberTemplate template, String username) {
        if (template == null) {
            throw new RuntimeException("模板不能为空");
        }
        normalizeAndValidateSensorFields(template);
        normalizeAdvancedConfig(template);
        assertTemplateRewardOwnership(template.getMemberId(), template, null);
        mediaPublicationGuard.requireTemplate(template.getMemberId(), template, true);
        validatePreferenceForReview(template);
        Date now = new Date();
        template.setCreateBy(username);
        template.setCreateTime(now);
        template.setDraftStatus(1);
        prepareForReview(template, username, now, true);
        int rows = memberTemplateService.insertCmsMemberTemplate(template);
        if (rows <= 0) {
            throw new RuntimeException("保存模板失败");
        }
        submitTemplateReview(requireActiveTemplate(template.getId()));
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateAdminTemplate(CmsMemberTemplate template, String username) {
        if (template == null || template.getId() == null) {
            throw new RuntimeException("缺少模板 id");
        }
        normalizeAndValidateSensorFields(template);
        normalizeAdvancedConfig(template);
        CmsMemberTemplate existing = requireActiveTemplate(template.getId());
        template.setMemberId(existing.getMemberId());
        assertTemplateRewardOwnership(existing.getMemberId(), template, existing);
        mediaPublicationGuard.requireTemplate(existing.getMemberId(), template, true);
        template.setOriginalTemplateId(existing.getOriginalTemplateId());
        if (Integer.valueOf(ValidationMethod.PREFERENCE_GROUP).equals(template.getValidationMethod())
                && template.getPreferenceJson() == null) {
            template.setPreferenceJson(existing.getPreferenceJson());
        }
        validatePreferenceForReview(template);
        Date now = new Date();
        template.setDraftStatus(1);
        prepareForReview(template, username, now, false);
        withdrawProjections(template.getId(), false, username);
        int rows = memberTemplateService.updateCmsMemberTemplate(template);
        if (rows <= 0) {
            throw new RuntimeException("更新模板失败");
        }
        submitTemplateReview(requireActiveTemplate(template.getId()));
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitTemplateForReview(Long templateId) {
        CmsMemberTemplate template = requireActiveTemplate(templateId);
        validatePreferenceForReview(template);
        if (!Integer.valueOf(1).equals(template.getDraftStatus())) throw new ServiceException("草稿请先发布");
        assertTemplateRewardOwnership(template.getMemberId(), template, null);
        mediaPublicationGuard.requireTemplate(template.getMemberId(), template, true);
        withdrawProjections(templateId, false, template.getUpdateBy());
        prepareForReview(template, template.getUpdateBy(), new Date(), false);
        if (memberTemplateService.updateCmsMemberTemplate(template) != 1) throw new ServiceException("提交审核失败");
        return submitTemplateReview(requireActiveTemplate(templateId));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long submitTemplateToMarket(Long templateId, Long memberId, String username) {
        List<CmsMemberTemplate> locked = memberTemplateMapper.selectCmsMemberTemplateByIdsForUpdate(
                Collections.singletonList(templateId));
        CmsMemberTemplate template = locked == null || locked.isEmpty() ? null : locked.get(0);
        // 业务拒绝用 ServiceException:controller 只透传它,RuntimeException 会被吞成「请稍后重试」(M-06)
        if (template == null || Integer.valueOf(2).equals(template.getDelFlag())) {
            throw new ServiceException("模板不存在或无权提交", HttpStatus.BAD_REQUEST);
        }
        if (memberId == null || !memberId.equals(template.getMemberId())) {
            throw new ServiceException("模板不存在或无权提交", HttpStatus.BAD_REQUEST);
        }
        if (!Integer.valueOf(1).equals(template.getDraftStatus())
                || !Long.valueOf(1L).equals(template.getStatus())
                || !Integer.valueOf(1).equals(template.getCheckStatus())) {
            throw new ServiceException("玩法个人审核通过后才能提交模板广场", HttpStatus.BAD_REQUEST);
        }
        validatePreferenceForReview(template);
        rejectSameTitleByAuthor(template, memberId);
        assertTemplateRewardOwnership(memberId, template, null);
        Date now = new Date();
        CmsTemplateLibrary projection = findLiveProjection(templateId);
        boolean creating = projection == null;
        if (creating) projection = new CmsTemplateLibrary();
        mediaPublicationGuard.requireTemplate(memberId, template, true);
        copyCanonicalToProjection(template, projection, username, now, creating);
        int rows = creating
                ? templateLibraryService.insertCmsTemplateLibrary(projection)
                : templateLibraryService.updateCmsTemplateLibrary(projection);
        if (rows <= 0 || projection.getId() == null) {
            throw new ServiceException("模板广场投影保存失败", HttpStatus.BAD_REQUEST);
        }
        return auditTaskService.submitOrRefreshTemplateLibrary(
                projection.getId(), memberId, marketAuditSnapshot(lockedProjection(templateId, projection.getId())));
    }

    /** owner 2026-10-23 D5(U-036):同一作者不许在模板广场放两份标题(trim 后逐字)相同的未删除模板。 */
    private void rejectSameTitleByAuthor(CmsMemberTemplate template, Long memberId) {
        String title = template.getTitle() == null ? "" : template.getTitle().trim();
        if (title.isEmpty()) return;
        for (String other : libraryMapper.selectAuthorLiveTitles(memberId, template.getId())) {
            if (other != null && title.equals(other.trim())) {
                throw new ServiceException("你已经有同名模板了", HttpStatus.BAD_REQUEST);
            }
        }
    }

    private String marketAuditSnapshot(CmsTemplateLibrary projection) {
        return TemplateAuditSnapshot.market(projection);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int updateTemplateFromLibraryProjection(CmsTemplateLibrary projection, String username) {
        if (projection == null || projection.getId() == null) {
            throw new RuntimeException("缺少模板库 id");
        }
        validateSensorFields(projection);
        normalizeAdvancedConfig(projection);
        CmsTemplateLibrary stored = templateLibraryService.selectCmsTemplateLibraryById(projection.getId());
        if (stored == null || Integer.valueOf(2).equals(stored.getDelFlag()) || stored.getMemberTemplateId() == null) {
            throw new RuntimeException("模板库记录缺少可审核的模板真源");
        }
        CmsMemberTemplate canonical = requireActiveTemplate(stored.getMemberTemplateId());
        stored = lockedProjection(canonical.getId(), projection.getId());
        if (stored == null || !Integer.valueOf(0).equals(stored.getDelFlag())) throw new ServiceException("模板库记录已删除");
        if (!Integer.valueOf(1).equals(canonical.getDraftStatus())
                || !Long.valueOf(1L).equals(canonical.getStatus())
                || !Integer.valueOf(1).equals(canonical.getCheckStatus())) {
            throw new RuntimeException("玩法个人审核通过后才能编辑模板广场投影");
        }
        assertRewardCouponsOwned(canonical.getMemberId(),
                projection.getCouponId() == null ? stored.getCouponId() : projection.getCouponId(),
                projection.getRewardRulesJson() == null ? stored.getRewardRulesJson() : projection.getRewardRulesJson());
        projection.setId(stored.getId());
        projection.setMemberTemplateId(stored.getMemberTemplateId());
        projection.setMemberId(stored.getMemberId());
        projection.setStatus(0L);
        projection.setPublishStatus(0L);
        projection.setReason("");
        projection.setDelFlag(stored.getDelFlag());
        projection.setCreateBy(stored.getCreateBy());
        projection.setCreateTime(stored.getCreateTime());
        // 采用数由首次采用自增维护(incrementUseNumOnFirstAdoption)，投影编辑不得用表单旧值覆盖。
        projection.setUseNum(null);
        projection.setUpdateBy(username);
        projection.setUpdateTime(new Date());
        int rows = templateLibraryService.updateCmsTemplateLibrary(projection);
        if (rows <= 0) throw new RuntimeException("更新模板库投影失败");
        auditTaskService.submitOrRefreshTemplateLibrary(
                projection.getId(), canonical.getMemberId(), marketAuditSnapshot(lockedProjection(canonical.getId(), projection.getId())));
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteAdminTemplates(Long[] templateIds) {
        if (templateIds == null || templateIds.length == 0) {
            throw new RuntimeException("缺少模板 id");
        }
        java.util.Set<Long> orderedIds = new java.util.TreeSet<Long>(java.util.Arrays.asList(templateIds));
        for (Long templateId : orderedIds) {
            CmsMemberTemplate template = requireActiveTemplate(templateId);
            if (Long.valueOf(1L).equals(template.getStatus()) || Long.valueOf(1L).equals(template.getPublishStatus())) {
                throw new RuntimeException("已审核或已上架模板不可直接删除，请先下架/驳回");
            }
            if (template.getMemberId() == null || !deleteTemplateWithSync(templateId, template.getMemberId())) {
                throw new RuntimeException("删除模板失败");
            }
        }
        return templateIds.length;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteTemplateFromLibraryProjection(Long projectionId, String username) {
        CmsTemplateLibrary projection = templateLibraryService.selectCmsTemplateLibraryById(projectionId);
        if (projection == null || Integer.valueOf(2).equals(projection.getDelFlag())) {
            throw new RuntimeException("模板库记录不存在或已删除");
        }
        requireActiveTemplate(projection.getMemberTemplateId());
        projection = lockedProjection(projection.getMemberTemplateId(), projectionId);
        if (projection == null || !Integer.valueOf(0).equals(projection.getDelFlag())) throw new ServiceException("模板库记录已删除");
        cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, projectionId);
        projection.setDelFlag(2);
        projection.setPublishStatus(0L);
        projection.setUpdateBy(username);
        projection.setUpdateTime(new Date());
        return templateLibraryService.updateCmsTemplateLibrary(projection);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteTemplateLibraryProjections(Long[] projectionIds, String username) {
        if (projectionIds == null || projectionIds.length == 0) {
            throw new RuntimeException("缺少模板库 id");
        }
        java.util.Set<Long> canonicalIds = new java.util.TreeSet<Long>();
        for (Long id : projectionIds) {
            CmsTemplateLibrary projection = templateLibraryService.selectCmsTemplateLibraryById(id);
            if (projection == null || projection.getMemberTemplateId() == null) throw new ServiceException("模板库记录不存在");
            canonicalIds.add(projection.getMemberTemplateId());
        }
        memberTemplateMapper.selectCmsMemberTemplateByIdsForUpdate(new java.util.ArrayList<Long>(canonicalIds));
        int rows = 0;
        for (Long projectionId : new java.util.TreeSet<Long>(java.util.Arrays.asList(projectionIds))) {
            rows += deleteTemplateFromLibraryProjection(projectionId, username);
        }
        return rows;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveMerchantNodeTemplate(CmsMemberTemplate input, Long memberId, String username) {
        CmsMemberTemplate existing = null;
        if (input.getId() != null && input.getId() > 0) {
            existing = lockTemplate(input.getId());
            if (existing == null || !Integer.valueOf(0).equals(existing.getDelFlag())
                    || !memberId.equals(existing.getMemberId())
                    || !Integer.valueOf(1).equals(existing.getMerchantNodeEnabled())) {
                throw new ServiceException("据点模板不存在或无权修改");
            }
        }
        // 内容字段沿发布 DTO 白名单复制，客户端不能恢复删除状态或伪造审核状态。
        CmsTemplatePublishRequest request = new CmsTemplatePublishRequest();
        BeanUtils.copyProperties(input, request);
        // BeanUtils 不做 Long→Integer 转换，duration 会被静默跳过并落成 0；这里精确转换，溢出直接拒绝。
        if (input.getDuration() != null) {
            try {
                request.setDuration(Math.toIntExact(input.getDuration()));
            } catch (ArithmeticException overflow) {
                throw new ServiceException("时长超出支持范围");
            }
        }
        validateSensorFields(request);
        normalizeAdvancedConfig(request, existing, true);
        CmsMemberTemplate saved = new CmsMemberTemplate();
        copyPropertiesToMemberTemplate(request, saved);
        assertTemplateRewardOwnership(memberId, saved, existing);
        mediaPublicationGuard.requireTemplate(memberId, saved, true);
        saved.setInteractionType(input.getInteractionType());
        saved.setId(existing == null ? null : existing.getId());
        saved.setMemberId(memberId); saved.setDelFlag(0); saved.setDraftStatus(1);
        saved.setStatus(1L); saved.setCheckStatus(1); saved.setPublishStatus(1L);
        saved.setMerchantNodeEnabled(1); saved.setNodeSubmitStatus(2); saved.setIsSync(0);
        saved.setOriginalTemplateId(existing == null || existing.getOriginalTemplateId() == null ? 0L : existing.getOriginalTemplateId());
        saved.setUpdateBy(username); saved.setUpdateTime(new Date());
        if (existing == null) {
            saved.setCreateBy(username); saved.setCreateTime(new Date());
            if (memberTemplateService.insertCmsMemberTemplate(saved) != 1) throw new ServiceException("保存据点模板失败");
        } else {
            withdrawProjections(existing.getId(), false, username);
            cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE, existing.getId());
            if (memberTemplateService.updateCmsMemberTemplate(saved) != 1) throw new ServiceException("保存据点模板失败");
        }
        return saved.getId();
    }

    private CmsMemberTemplate requireActiveTemplate(Long templateId) {
        CmsMemberTemplate template = lockTemplate(templateId);
        if (template == null || !Integer.valueOf(0).equals(template.getDelFlag())) {
            throw new RuntimeException("模板不存在或已删除");
        }
        return template;
    }

    /** 所有模板写路径统一顺序：个人模板 → 广场投影（id升序）→ 审核任务。 */
    private CmsMemberTemplate lockTemplate(Long templateId) {
        List<CmsMemberTemplate> rows = memberTemplateMapper.selectCmsMemberTemplateByIdsForUpdate(
                Collections.singletonList(templateId));
        return rows == null || rows.isEmpty() ? null : rows.get(0);
    }

    private CmsTemplateLibrary lockedProjection(Long templateId, Long projectionId) {
        for (CmsTemplateLibrary row : libraryMapper.selectByMemberTemplateForUpdate(templateId)) {
            if (projectionId.equals(row.getId())) return row;
        }
        throw new ServiceException("模板广场投影已变化");
    }

    private void withdrawProjections(Long templateId, boolean deleting, String username) {
        for (CmsTemplateLibrary row : libraryMapper.selectByMemberTemplateForUpdate(templateId)) {
            if (!Integer.valueOf(0).equals(row.getDelFlag())) continue;
            row.setPublishStatus(0L); row.setStatus(2L);
            row.setReason("个人玩法已变更，请重新提交广场审核");
            if (deleting) row.setDelFlag(2);
            row.setUpdateBy(username); row.setUpdateTime(new Date());
            if (templateLibraryService.updateCmsTemplateLibrary(row) != 1) throw new ServiceException("撤下广场投影失败");
            cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, row.getId());
        }
    }

    private void prepareForReview(CmsMemberTemplate template, String username, Date now, boolean creating) {
        template.setStatus(0L);
        template.setCheckStatus(0);
        template.setPublishStatus(0L);
        template.setReason("");
        template.setDelFlag(creating ? 0 : null);
        template.setUpdateBy(username);
        template.setUpdateTime(now);
    }

    private void validatePreferenceForReview(CmsMemberTemplate template) {
        if (!Integer.valueOf(ValidationMethod.PREFERENCE_GROUP).equals(template.getValidationMethod())) return;
        try {
            preferenceEvaluator.validate(template.getPreferenceJson());
        } catch (IllegalArgumentException e) {
            throw new RuntimeException(e.getMessage());
        }
    }

    private Long submitTemplateReview(CmsMemberTemplate template) {
        return auditTaskService.submitOrRefreshTemplate(template.getId(), template.getMemberId(),
                templateAuditSnapshot(template));
    }

    private void copyCanonicalToProjection(CmsMemberTemplate template, CmsTemplateLibrary projection,
            String username, Date now, boolean creating) {
        Long projectionId = projection.getId();
        Integer projectionDelFlag = projection.getDelFlag();
        BeanUtils.copyProperties(template, projection);
        projection.setId(creating ? null : projectionId);
        projection.setMemberTemplateId(template.getId());
        projection.setStatus(0L);
        projection.setPublishStatus(0L);
        projection.setReason("");
        projection.setDelFlag(creating ? 0 : projectionDelFlag);
        projection.setUpdateBy(username);
        projection.setUpdateTime(now);
        if (creating) {
            projection.setCreateBy(username);
            projection.setCreateTime(now);
        }
    }

    /**
     * 复制属性到我的模板表
     */
    private void copyPropertiesToMemberTemplate(CmsTemplatePublishRequest request, CmsMemberTemplate template) {
        if (request.getClearFields() != null) template.setClearFields(new HashSet<>(request.getClearFields()));
        template.setCategoryId(request.getCategoryId());
        template.setTitle(request.getTitle());
        template.setDescription(request.getDescription());
        template.setImgUrl(request.getImgUrl());
        template.setPlayers(request.getPlayers());
        template.setUsageLocation(request.getUsageLocation());
        template.setRequiredMaterials(request.getRequiredMaterials());
        template.setDuration(Convert.toLong(request.getDuration(),0L));
        template.setDifficulty(request.getDifficulty());
        template.setIsSync(request.getIsSync());
        template.setRuleInstructions(request.getRuleInstructions());
        template.setMerchantGuide(request.getMerchantGuide());
        template.setRewardRulesJson(request.getRewardRulesJson());
        template.setValidationMethod(request.getValidationMethod());
        template.setInteractionType(request.getInteractionType());
        template.setSensorType(request.getSensorType());
        template.setSensorConfig(request.getSensorConfig());
        template.setQuestionName(request.getQuestionName());
        template.setQuestionAnswer(request.getQuestionAnswer());
        template.setFeedbackText(request.getFeedbackText());
        template.setCouponId(request.getCouponId());
        template.setMedalImg(request.getMedalImg());
        template.setMedalName(request.getMedalName());
        template.setMedalStyle(normalizeMedalStyle(request.getMedalStyle()));
        template.setQuestionA(request.getQuestionA());
        template.setQuestionB(request.getQuestionB());
        template.setQuestionC(request.getQuestionC());
        template.setQuestionD(request.getQuestionD());
        template.setCorrectAnswer(request.getCorrectAnswer());
        // ⚠️ 题面媒体两处 copy 都要有：只加「我的模板」不加「模板库」，
        //    编辑器填好的题干图会在发布那一刻被静默丢掉，没有任何报错。
        template.setQuestionImg(request.getQuestionImg());
        template.setQuestionAudio(request.getQuestionAudio());
        template.setQuestionOptionMediaJson(
                QuestionOptionMedia.sanitize(request.getQuestionOptionMediaJson()));
        template.setPreferenceJson(preferenceJson(request));
        template.setAdvancedConfigJson(request.getAdvancedConfigJson());
        template.setActivityCategoryids(request.getActivityCategoryids());
        template.setStoryText(request.getStoryText());
        template.setStoryImg(request.getStoryImg());
        template.setStoryJson(request.getStoryJson());
        template.setHint1(request.getHint1());
        template.setHint2(request.getHint2());
        template.setAnswerReveal(request.getAnswerReveal());
        template.setPhotoRequireDesc(request.getPhotoRequireDesc());
        template.setPhotoReview(request.getPhotoReview());
        template.setAudioUrl(request.getAudioUrl());
        template.setAudioDuration(request.getAudioDuration());
    }

    /** 出身只能在创建采用副本时确定；编辑不能抹除或换成另一公共库 ID。 */
    private void preserveAdoptionIdentity(CmsTemplatePublishRequest request, CmsMemberTemplate existing) {
        if (existing == null) return;
        Long sourceId = existing.getOriginalTemplateId() == null ? 0L : existing.getOriginalTemplateId();
        if (request.getOriginalTemplateId() != null && !sourceId.equals(request.getOriginalTemplateId())) {
            throw new IllegalArgumentException("不能修改已有模板的来源，请从目标来源重新采用");
        }
        request.setOriginalTemplateId(sourceId);
    }

    /** 库表未存储的互动语义和奖章沿现有 canonical 关联读取，不猜 library/member ID。 */
    private void copyAdoptedCanonicalFields(CmsTemplateLibrary source, CmsTemplatePublishRequest request) {
        if (source.getMemberTemplateId() == null) return; // 历史直接入库内容没有个人原单关联。
        CmsMemberTemplate canonical = memberTemplateService.selectCmsMemberTemplateById(source.getMemberTemplateId());
        if (canonical == null || !source.getMemberTemplateId().equals(canonical.getId())
                || source.getMemberId() == null || !source.getMemberId().equals(canonical.getMemberId())
                || !Integer.valueOf(0).equals(canonical.getDelFlag())
                || !Long.valueOf(1L).equals(canonical.getStatus())) {
            throw new IllegalArgumentException("源模板内容关联缺失或不一致，无法完整采用");
        }
        // 公共库已经审核且在架；个人列表是否上架不改变这份内容的已审核身份。
        request.setInteractionType(canonical.getInteractionType());
        request.setMedalImg(canonical.getMedalImg());
        request.setMedalName(canonical.getMedalName());
        request.setMedalStyle(canonical.getMedalStyle());
    }

    private String normalizeMedalStyle(String medalStyle) {
        if (StringUtils.isBlank(medalStyle)) {
            return CmsMemberTemplate.MEDAL_STYLE_GLOW;
        }
        String normalized = medalStyle.trim();
        if (!CmsMemberTemplate.MEDAL_STYLE_GLOW.equals(normalized)
                && !CmsMemberTemplate.MEDAL_STYLE_ENAMEL.equals(normalized)) {
            throw new IllegalArgumentException("勋章样式参数错误");
        }
        return normalized;
    }

    private String preferenceJson(CmsTemplatePublishRequest request) {
        return Integer.valueOf(ValidationMethod.PREFERENCE_GROUP).equals(request.getValidationMethod())
                ? request.getPreferenceJson() : null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveOrUpdateDraft(CmsTemplatePublishRequest request, Long memberId, String username) {
        if (request != null && Boolean.TRUE.equals(request.getAdoptSourceOnly())) {
            if (request.getId() != null || request.getOriginalTemplateId() == null
                    || request.getOriginalTemplateId() <= 0) {
                throw new IllegalArgumentException("采用模板必须指定来源，不能覆盖已有草稿");
            }
            Long sourceId = request.getOriginalTemplateId();
            String scope = request.getScope();
            CmsTemplateLibrary source = templateLibraryService.selectCmsTemplateLibraryById(sourceId);
            if (source == null || !Integer.valueOf(0).equals(source.getDelFlag())
                    || !Long.valueOf(1L).equals(source.getStatus())
                    || !Long.valueOf(1L).equals(source.getPublishStatus())) {
                throw new IllegalArgumentException("源模板不存在或已下架");
            }
            // 作者配置从服务端复制；公开详情仍不下发答案、卡池和分支内容。
            BeanUtils.copyProperties(source, request, "id", "scope", "originalTemplateId", "duration");
            request.setDuration(source.getDuration() == null ? null : Math.toIntExact(source.getDuration()));
            request.setScope(scope);
            request.setOriginalTemplateId(sourceId);
            request.setIsSync(0);
            // 采用是完整服务端复制，不应用客户端旧表单的清空标记或未在库中存储的字段。
            request.setClearFields(null);
            request.setInteractionType(null);
            request.setMedalImg(null);
            request.setMedalName(null);
            request.setMedalStyle(null);
            copyAdoptedCanonicalFields(source, request);
            // 优惠券是原商家的资产，采用内容不能顺带复制发券权限。
            if (source.getCouponId() != null && source.getCouponId() > 0) {
                throw new IllegalArgumentException("该模板含商家专属优惠券，请先由原作者提供不含优惠券的可采用版本");
            }
            String sourceRewards = rewardRulesValidator.validateAndNormalize(source.getRewardRulesJson());
            if (sourceRewards != null) {
                try {
                    for (com.fasterxml.jackson.databind.JsonNode rule : new com.fasterxml.jackson.databind.ObjectMapper().readTree(sourceRewards)) {
                        if (rule.path("couponId").asLong(0) > 0) {
                            throw new IllegalArgumentException("该模板含商家专属条件优惠券，不能跨商家采用");
                        }
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                    throw new IllegalArgumentException("源模板奖励配置格式不正确", e);
                }
            }
        }
        CmsMemberTemplate existingDraft = null;
        if (request.getId() != null && request.getId() > 0) {
            existingDraft = lockTemplate(request.getId());
            if (existingDraft == null || !Integer.valueOf(0).equals(existingDraft.getDelFlag())
                    || !memberId.equals(existingDraft.getMemberId())) {
                throw new RuntimeException("草稿不存在或无权修改");
            }
        }
        preserveAdoptionIdentity(request, existingDraft);
        prepareClearFields(request, existingDraft);
        validateSensorFields(request);
        normalizeAdvancedConfig(request, existingDraft, !Boolean.TRUE.equals(request.getAdoptSourceOnly()));
        if (!Boolean.TRUE.equals(request.getAdoptSourceOnly())) {
            normalizeAdoptedReward(request, existingDraft);
        }
        assertCapabilities(request, memberId);
        if (existingDraft != null && StringUtils.isBlank(request.getMedalStyle())) {
            request.setMedalStyle(existingDraft.getMedalStyle());
        }
        Date now = new Date();
        CmsMemberTemplate t = new CmsMemberTemplate();
        copyPropertiesToMemberTemplate(request, t);
        assertTemplateRewardOwnership(memberId, t, existingDraft);
        mediaPublicationGuard.requireTemplate(memberId, t, false);
        t.setMemberId(memberId);
        t.setUpdateBy(username);
        t.setUpdateTime(now);
        t.setDraftStatus(0);   // 草稿
        t.setStatus(0L);       // 审核态:草稿按待审/未提审处理(发布时再置1)
        t.setPublishStatus(0L);// 未上架
        t.setDelFlag(0);

        String cats = t.getActivityCategoryids();
        if (StringUtils.isNotBlank(cats)) {
            t.setCategoryId(Long.valueOf(cats.split(",")[0].trim()));
        }
        t.setOriginalTemplateId(
            (request.getOriginalTemplateId() != null && request.getOriginalTemplateId() > 0)
                ? request.getOriginalTemplateId() : 0L);

        if (request.getId() != null && request.getId() > 0) {
            t.setId(request.getId());
            t.setCheckStatus(0);
            withdrawProjections(t.getId(), false, username);
            cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE, t.getId());
            if (memberTemplateService.updateCmsMemberTemplate(t) != 1) throw new ServiceException("保存草稿失败");
            return request.getId();
        } else {
            t.setCreateBy(username);
            t.setCreateTime(now);
            if (memberTemplateService.insertCmsMemberTemplate(t) != 1 || t.getId() == null) {
                throw new ServiceException("保存草稿失败");
            }
            return t.getId();
        }
    }

    private void assertTemplateRewardOwnership(Long owner, CmsMemberTemplate patch, CmsMemberTemplate existing) {
        Long coupon = patch.getCouponId() == null && existing != null ? existing.getCouponId() : patch.getCouponId();
        String rules = patch.getRewardRulesJson();
        boolean clearsRules = patch.getClearFields() != null && patch.getClearFields().contains("rewardRulesJson");
        if (rules == null && existing != null && !clearsRules) rules = existing.getRewardRulesJson();
        assertRewardCouponsOwned(owner, coupon, rules);
    }

    private void assertRewardCouponsOwned(Long owner, Long coupon, String rules) {
        Set<Long> ids = new LinkedHashSet<>();
        if (coupon != null && coupon > 0L) ids.add(coupon);
        ids.addAll(rewardRulesValidator.couponIds(rules));
        for (Long id : ids) couponRewardAuthorizationService.assertOwnedRewardCoupon(id, owner);
    }

    /**
     * X03:身份能力位闸。界面的「权益清单」已经告诉用户哪些解锁了哪些没有,
     * 这里让服务端和那句承诺保持一致 —— 此前它只是一句话,后端照收。
     *
     * <p>草稿也拦:草稿的去向就是发布,只在发布拦会让用户把一整套配置做完
     * 才在最后一步被拒,那比一开始就说不能用更糟。
     */
    private void assertCapabilities(CmsTemplatePublishRequest request, Long memberId) {
        if (request == null) return;
        String mechanicErr = capabilityGuard.checkAdvancedMechanics(memberId, request.getAdvancedConfigJson());
        if (mechanicErr != null) {
            throw new IllegalArgumentException(mechanicErr);
        }
        // glow 是默认样式(留空也会被 normalizeMedalStyle 补成 glow),小程序新建/回填一律带着它 ——
        // 把它算成「要设计勋章」会让没有勋章权益的商家/玩家任何模板都存不了(2026-09-24 生产实跑 C-06)。
        boolean wantsMedal = StringUtils.isNotBlank(request.getMedalName())
                || StringUtils.isNotBlank(request.getMedalImg())
                || (StringUtils.isNotBlank(request.getMedalStyle())
                        && !CmsMemberTemplate.MEDAL_STYLE_GLOW.equals(request.getMedalStyle().trim()));
        boolean adoptingPublicTemplate = request.getOriginalTemplateId() != null
                && request.getOriginalTemplateId() > 0;
        if (wantsMedal && !adoptingPublicTemplate) {
            String medalErr = capabilityGuard.checkDesignMedal(memberId);
            if (medalErr != null) {
                throw new IllegalArgumentException(medalErr);
            }
        }
    }

    /**
     * 采用公共模板时奖励来自服务端真源，客户端既不能篡改，也不应被当作重新设计勋章。
     */
    private void normalizeAdoptedReward(CmsTemplatePublishRequest request, CmsMemberTemplate existing) {
        if (request == null || request.getOriginalTemplateId() == null
                || request.getOriginalTemplateId() <= 0) {
            return;
        }
        if (existing != null) {
            // 采用后已成为自己的内容副本。后续编辑不再追随来源改动或来源下架。
            request.setMedalImg(existing.getMedalImg());
            request.setMedalName(existing.getMedalName());
            request.setMedalStyle(existing.getMedalStyle());
            return;
        }
        request.setMedalImg(null);
        request.setMedalName(null);
        request.setMedalStyle(null);
        CmsTemplateLibrary source = templateLibraryService.selectCmsTemplateLibraryById(
                request.getOriginalTemplateId());
        if (source == null || source.getMemberTemplateId() == null
                || !Integer.valueOf(0).equals(source.getDelFlag())
                || !Long.valueOf(1L).equals(source.getStatus())
                || !Long.valueOf(1L).equals(source.getPublishStatus())) {
            return;
        }
        CmsMemberTemplate canonical = memberTemplateService.selectCmsMemberTemplateById(
                source.getMemberTemplateId());
        if (!isPublishedCanonical(canonical)) {
            return;
        }
        request.setMedalImg(canonical.getMedalImg());
        request.setMedalName(canonical.getMedalName());
        request.setMedalStyle(canonical.getMedalStyle());
    }

    @Override
    public void enrichPublicRewardProjection(CmsTemplateLibrary projection) {
        if (projection == null || projection.getMemberTemplateId() == null) {
            return;
        }
        CmsMemberTemplate canonical = memberTemplateService.selectCmsMemberTemplateById(
                projection.getMemberTemplateId());
        if (!isPublishedCanonical(canonical)) {
            return;
        }
        projection.setMedalImg(canonical.getMedalImg());
        projection.setMedalName(canonical.getMedalName());
        projection.setMedalStyle(canonical.getMedalStyle());
    }

    private boolean isPublishedCanonical(CmsMemberTemplate canonical) {
        return canonical != null
                && Integer.valueOf(0).equals(canonical.getDelFlag())
                && Long.valueOf(1L).equals(canonical.getStatus())
                && Long.valueOf(1L).equals(canonical.getPublishStatus());
    }

    private void prepareClearFields(CmsTemplatePublishRequest request, CmsMemberTemplate existing) {
        Set<String> fields = request.getClearFields();
        if (fields == null || fields.isEmpty()) return;
        Set<String> allowed = new HashSet<>(Arrays.asList("rewardRulesJson", "merchantGuide", "sensorType", "sensorConfig", "storyImg"));
        for (String field : fields) if (!allowed.contains(field)) throw new IllegalArgumentException("不支持清空字段：" + field);
        Integer method = request.getValidationMethod() != null ? request.getValidationMethod()
                : existing == null ? null : existing.getValidationMethod();
        String sensorType = request.getSensorType() != null ? request.getSensorType()
                : existing == null ? null : existing.getSensorType();
        if ((fields.contains("sensorType") || fields.contains("sensorConfig"))
                && (isAppSensor(method) || isPhotoFilter(method, sensorType)
                || (existing != null && isPhotoFilter(method, existing.getSensorType())))) {
            throw new IllegalArgumentException("当前验证方式需要传感器配置，请先切换为非传感器验证方式再清空");
        }
        if (fields.contains("rewardRulesJson")) request.setRewardRulesJson(null);
        if (fields.contains("merchantGuide")) request.setMerchantGuide(null);
        if (fields.contains("sensorType")) request.setSensorType(null);
        if (fields.contains("sensorConfig")) request.setSensorConfig(null);
        if (fields.contains("storyImg")) request.setStoryImg(null);
    }

    private void validateSensorFields(CmsTemplatePublishRequest request) {
        if (request == null) throw new IllegalArgumentException("模板不能为空");
        if (isAppSensor(request.getValidationMethod())) {
            validateSensorFields(request.getSensorType(), request.getSensorConfig());
            return;
        }
        if (isPhotoFilter(request.getValidationMethod(), request.getSensorType())) {
            SensorChallengePolicy.validatePhotoFilterConfig(request.getSensorConfig());
            return;
        }
        if (request.getValidationMethod() == null || request.getValidationMethod() != ValidationMethod.PHOTO
                || (StringUtils.isBlank(request.getSensorType()) && StringUtils.isBlank(request.getSensorConfig()))) {
            request.setSensorType(null);
            request.setSensorConfig(null);
            return;
        }
        throw new IllegalArgumentException("拍照滤镜类型无效");
    }

    private void normalizeAdvancedConfig(CmsTemplatePublishRequest request, CmsMemberTemplate existing,
                                        boolean backfillSecrets) {
        if (backfillSecrets) backfillAdoptedSecrets(request, existing);
        request.setAdvancedConfigJson(normalizeAdvancedConfigValue(request.getAdvancedConfigJson()));
        // 条件奖励与玩法配置同期规范化:两者都是「商家写、服务端认」的 JSON,
        // 校验放在同一处,免得一个走校验另一个裸奔。
        request.setRewardRulesJson(rewardRulesValidator.validateAndNormalize(request.getRewardRulesJson()));
    }

    /**
     * 采用公共库模板(originalTemplateId>0)时,/api/template/info 对非 owner 剥掉了
     * blindTaste.answerKey 与 dailySign.poems(见 AdvancedGamePublicProjection),
     * 客户端回填出来的配置里这两个字段是空的,直接进校验器必报「必填缺失」——
     * 「闭眼味觉师」「今日城市签」这两款模板就采用不了。
     * 服务端在这里从源库模板补回:秘密字段全程只在服务端流转,绝不下发给非 owner 客户端。
     * 补不回来(源模板没了/它也没配)就原样交给校验器报错,不静默吞。
     */
    private void backfillAdoptedSecrets(CmsTemplatePublishRequest request, CmsMemberTemplate existing) {
        Long sourceId = existing == null ? request.getOriginalTemplateId() : existing.getOriginalTemplateId();
        String raw = request.getAdvancedConfigJson();
        if (sourceId == null || sourceId <= 0 || StringUtils.isBlank(raw)) return;
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        final com.fasterxml.jackson.databind.JsonNode root;
        try {
            // R22-SEC S5:try 只包 JSON 解析 —— 下面的模板查询是 DB 调用,故障必须原样抛,
            // 不能被「JSON 不合法就不动它」的注释吞成静默。
            root = mapper.readTree(raw);
        } catch (Exception unusableRaw) {
            logger.warn("采用公共库模板 {} 的配置不是合法 JSON,跳过秘密字段回填:{}",
                    sourceId, unusableRaw.toString());
            return;
        }
        if (!(root instanceof com.fasterxml.jackson.databind.node.ObjectNode)) return;
        boolean needAnswerKey = sectionEnabled(root.get("blindTaste"))
                && root.path("blindTaste").path("answerKey").asText("").trim().isEmpty();
        com.fasterxml.jackson.databind.JsonNode poems = root.path("dailySign").path("poems");
        boolean needPoems = sectionEnabled(root.get("dailySign"))
                && (!poems.isArray() || poems.size() == 0);
        // 自由探索三玩法的秘密字段同理被投影剥掉:估数的答案与容差、比价的价格、
        // 找东西的坐标半径。少了这一段,商家从货架采用这三款模板会直接吃校验报错。
        boolean needEstimate = sectionEnabled(root.get("estimate"))
                && (!root.path("estimate").path("answer").isNumber()
                    || !root.path("estimate").path("tolerance").isNumber());
        boolean needPrices = sectionEnabled(root.get("pricePair"))
                && missingField(root.path("pricePair").path("items"), "price");
        boolean needSpots = sectionEnabled(root.get("hiddenObject"))
                && (missingField(root.path("hiddenObject").path("hotspots"), "x")
                    || missingField(root.path("hiddenObject").path("hotspots"), "y")
                    || missingField(root.path("hiddenObject").path("hotspots"), "r"));
        // R3 三新玩法与问答选项题同理:投影把 answerOrder / pairs / answer / correct
        // 全部剥掉,采用公共库模板时从源模板按 id 补回,否则保存即撞校验必填项。
        boolean needSortOrder = sectionEnabled(root.get("sort"))
                && emptyArray(root.path("sort").path("answerOrder"));
        boolean needPairs = sectionEnabled(root.get("match"))
                && emptyArray(root.path("match").path("pairs"));
        boolean needClassifyAnswer = sectionEnabled(root.get("classify"))
                && emptyObject(root.path("classify").path("answer"));
        // R16 对照题的 answer 同样被投影剥掉(整段数组),采用时从源模板补回。
        boolean needCompareAnswer = sectionEnabled(root.get("compare"))
                && emptyArray(root.path("compare").path("answer"));
        boolean needQaCorrect = sectionEnabled(root.get("qa"))
                && "PICK".equals(root.path("qa").path("mode").asText(""))
                && missingBooleanField(root.path("qa").path("options"), "correct");
        com.fasterxml.jackson.databind.JsonNode dice = root.path("diceRoll");
        boolean needD20Text = sectionEnabled(dice) && "d20".equals(dice.path("mode").asText())
                && (dice.path("successText").asText("").trim().isEmpty()
                    || dice.path("failText").asText("").trim().isEmpty());
        if (!needAnswerKey && !needPoems && !needEstimate && !needPrices && !needSpots
                && !needSortOrder && !needPairs && !needClassifyAnswer && !needQaCorrect
                && !needCompareAnswer && !needD20Text) return;
        final String sourceConfig;
        if (existing != null) {
            // 该行已由当前 owner 锁定；副本缺失字段只可从自己的原内容补回。
            sourceConfig = existing.getAdvancedConfigJson();
        } else {
            CmsTemplateLibrary source = templateLibraryService.selectCmsTemplateLibraryById(sourceId);
            if (source == null) return;
            // 新建兼容路径仍须核对公共来源可见性，不能借来源 ID 读取私有答案。
            if (source.getDelFlag() == null || source.getDelFlag() == 2
                    || source.getStatus() == null || source.getStatus() != 1
                    || source.getPublishStatus() == null || source.getPublishStatus() != 1) {
                return;
            }
            sourceConfig = source.getAdvancedConfigJson();
        }
        if (StringUtils.isBlank(sourceConfig)) return;
        final com.fasterxml.jackson.databind.JsonNode sourceRoot;
        try {
            sourceRoot = mapper.readTree(sourceConfig);
        } catch (Exception unusableSource) {
            logger.warn("公共库模板 {} 的配置不是合法 JSON,跳过秘密字段回填:{}",
                    sourceId, unusableSource.toString());
            return;
        }
        if (needD20Text && sectionEnabled(sourceRoot.path("diceRoll"))
                && "d20".equals(sourceRoot.path("diceRoll").path("mode").asText())) {
            com.fasterxml.jackson.databind.node.ObjectNode target =
                    (com.fasterxml.jackson.databind.node.ObjectNode) dice;
            for (String field : new String[] { "successText", "failText" }) {
                if (target.path(field).asText("").trim().isEmpty()) {
                    target.put(field, sourceRoot.path("diceRoll").path(field).asText(""));
                }
            }
        }
        if (needAnswerKey) {
            String answerKey = sourceRoot.path("blindTaste").path("answerKey").asText("").trim();
            if (!answerKey.isEmpty()) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("blindTaste"))
                        .put("answerKey", answerKey);
            }
        }
        if (needPoems) {
            com.fasterxml.jackson.databind.JsonNode sourcePoems = sourceRoot.path("dailySign").path("poems");
            if (sourcePoems.isArray() && sourcePoems.size() > 0) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("dailySign"))
                        .set("poems", sourcePoems.deepCopy());
            }
        }
        if (needEstimate) {
            com.fasterxml.jackson.databind.JsonNode src = sourceRoot.path("estimate");
            if (src.path("answer").isNumber() && src.path("tolerance").isNumber()) {
                com.fasterxml.jackson.databind.node.ObjectNode target =
                        (com.fasterxml.jackson.databind.node.ObjectNode) root.get("estimate");
                target.set("answer", src.get("answer").deepCopy());
                target.set("tolerance", src.get("tolerance").deepCopy());
            }
        }
        if (needPrices) {
            copyRowField(root.path("pricePair").path("items"),
                    sourceRoot.path("pricePair").path("items"), "id", "price");
        }
        if (needSpots) {
            com.fasterxml.jackson.databind.JsonNode spots = root.path("hiddenObject").path("hotspots");
            com.fasterxml.jackson.databind.JsonNode sourceSpots =
                    sourceRoot.path("hiddenObject").path("hotspots");
            copyRowField(spots, sourceSpots, "id", "x");
            copyRowField(spots, sourceSpots, "id", "y");
            copyRowField(spots, sourceSpots, "id", "r");
        }
        if (needSortOrder) {
            com.fasterxml.jackson.databind.JsonNode order = sourceRoot.path("sort").path("answerOrder");
            if (order.isArray() && order.size() > 0) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("sort"))
                        .set("answerOrder", order.deepCopy());
            }
        }
        if (needPairs) {
            com.fasterxml.jackson.databind.JsonNode pairs = sourceRoot.path("match").path("pairs");
            if (pairs.isArray() && pairs.size() > 0) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("match"))
                        .set("pairs", pairs.deepCopy());
            }
        }
        if (needClassifyAnswer) {
            com.fasterxml.jackson.databind.JsonNode answer = sourceRoot.path("classify").path("answer");
            if (answer.isObject() && answer.size() > 0) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("classify"))
                        .set("answer", answer.deepCopy());
            }
        }
        if (needQaCorrect) {
            copyRowFieldBoolean(root.path("qa").path("options"),
                    sourceRoot.path("qa").path("options"), "id", "correct");
        }
        if (needCompareAnswer) {
            com.fasterxml.jackson.databind.JsonNode answer = sourceRoot.path("compare").path("answer");
            if (answer.isArray() && answer.size() > 0) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) root.get("compare"))
                        .set("answer", answer.deepCopy());
            }
        }
        try {
            request.setAdvancedConfigJson(mapper.writeValueAsString(root));
        } catch (Exception unserializable) {
            // 序列化失败(理论不可达):照旧交给紧随其后的 validateAndNormalize 报,但留一条 warn。
            logger.warn("采用公共库模板 {} 的秘密字段回填序列化失败:{}",
                    sourceId, unserializable.toString());
        }
    }

    private boolean sectionEnabled(com.fasterxml.jackson.databind.JsonNode node) {
        return node != null && node.isObject() && node.path("enabled").asBoolean(false);
    }

    /** 数组里有任何一行缺这个数字字段,就说明这一段的秘密被投影剥过 */
    private boolean missingField(com.fasterxml.jackson.databind.JsonNode rows, String field) {
        if (rows == null || !rows.isArray() || rows.size() == 0) return false;
        for (com.fasterxml.jackson.databind.JsonNode row : rows) {
            if (!row.path(field).isNumber()) return true;
        }
        return false;
    }

    /** 布尔字段版的 missingField:问答选项的 correct 被投影剥掉,缺一个就整段补 */
    private boolean missingBooleanField(com.fasterxml.jackson.databind.JsonNode rows, String field) {
        if (rows == null || !rows.isArray() || rows.size() == 0) return false;
        for (com.fasterxml.jackson.databind.JsonNode row : rows) {
            if (!row.path(field).isBoolean()) return true;
        }
        return false;
    }

    /** 投影剥掉整个数组字段(answerOrder / pairs)后,键不存在或为空都算缺 */
    private boolean emptyArray(com.fasterxml.jackson.databind.JsonNode node) {
        return !node.isArray() || node.size() == 0;
    }

    /** 投影剥掉整个对象字段(classify.answer)后,键不存在或为空都算缺 */
    private boolean emptyObject(com.fasterxml.jackson.databind.JsonNode node) {
        return !node.isObject() || node.size() == 0;
    }

    /**
     * 按 keyField 逐行把 valueField 从源模板补回来。
     *
     * <p>★ 按 id 配对而不是按下标:采用之后商家可以增删、重排这些行,按下标补会把
     * A 的价格贴到 B 身上 —— 那是**静默的**错答案,比报错难查得多。
     * 源里找不到同 id 的行就不补,让紧随其后的校验器报缺失,不猜。
     */
    private void copyRowField(com.fasterxml.jackson.databind.JsonNode rows,
                              com.fasterxml.jackson.databind.JsonNode sourceRows,
                              String keyField, String valueField) {
        if (rows == null || !rows.isArray() || sourceRows == null || !sourceRows.isArray()) return;
        for (com.fasterxml.jackson.databind.JsonNode row : rows) {
            if (!(row instanceof com.fasterxml.jackson.databind.node.ObjectNode)) continue;
            if (row.path(valueField).isNumber()) continue;
            String id = row.path(keyField).asText("").trim();
            if (id.isEmpty()) continue;
            for (com.fasterxml.jackson.databind.JsonNode sourceRow : sourceRows) {
                if (!id.equals(sourceRow.path(keyField).asText("").trim())) continue;
                if (sourceRow.path(valueField).isNumber()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) row)
                            .set(valueField, sourceRow.get(valueField).deepCopy());
                }
                break;
            }
        }
    }

    /** copyRowField 的布尔版:问答选项的 correct 按 id 补回,客户端手里没有这个标记 */
    private void copyRowFieldBoolean(com.fasterxml.jackson.databind.JsonNode rows,
                                     com.fasterxml.jackson.databind.JsonNode sourceRows,
                                     String keyField, String valueField) {
        if (rows == null || !rows.isArray() || sourceRows == null || !sourceRows.isArray()) return;
        for (com.fasterxml.jackson.databind.JsonNode row : rows) {
            if (!(row instanceof com.fasterxml.jackson.databind.node.ObjectNode)) continue;
            if (row.path(valueField).isBoolean()) continue;
            String id = row.path(keyField).asText("").trim();
            if (id.isEmpty()) continue;
            for (com.fasterxml.jackson.databind.JsonNode sourceRow : sourceRows) {
                if (!id.equals(sourceRow.path(keyField).asText("").trim())) continue;
                if (sourceRow.path(valueField).isBoolean()) {
                    ((com.fasterxml.jackson.databind.node.ObjectNode) row)
                            .set(valueField, sourceRow.get(valueField).deepCopy());
                }
                break;
            }
        }
    }

    private void normalizeAdvancedConfig(CmsMemberTemplate template) {
        template.setAdvancedConfigJson(normalizeAdvancedConfigValue(template.getAdvancedConfigJson()));
    }

    private void normalizeAdvancedConfig(CmsTemplateLibrary template) {
        template.setAdvancedConfigJson(normalizeAdvancedConfigValue(template.getAdvancedConfigJson()));
    }

    /** null=调用方未提供，不改旧值；空串=显式关闭，Mapper 负责写 SQL NULL。 */
    private String normalizeAdvancedConfigValue(String raw) {
        if (raw == null) return null;
        String normalized = advancedGameConfigValidator.validateAndNormalize(raw);
        return normalized == null ? "" : normalized;
    }

    @Override
    public void normalizeAndValidateSensorFields(CmsMemberTemplate template) {
        if (template == null) throw new IllegalArgumentException("模板不能为空");
        if (isAppSensor(template.getValidationMethod())) {
            validateSensorFields(template.getSensorType(), template.getSensorConfig());
            return;
        }
        if (isPhotoFilter(template.getValidationMethod(), template.getSensorType())) {
            SensorChallengePolicy.validatePhotoFilterConfig(template.getSensorConfig());
            return;
        }
        if (template.getValidationMethod() == null || template.getValidationMethod() != ValidationMethod.PHOTO
                || (StringUtils.isBlank(template.getSensorType()) && StringUtils.isBlank(template.getSensorConfig()))) {
            template.setSensorType(null);
            template.setSensorConfig(null);
            return;
        }
        throw new IllegalArgumentException("拍照滤镜类型无效");
    }

    private void validateSensorFields(CmsTemplateLibrary template) {
        if (isAppSensor(template.getValidationMethod())) {
            validateSensorFields(template.getSensorType(), template.getSensorConfig());
            return;
        }
        if (isPhotoFilter(template.getValidationMethod(), template.getSensorType())) {
            SensorChallengePolicy.validatePhotoFilterConfig(template.getSensorConfig());
            return;
        }
        if (template.getValidationMethod() == null || template.getValidationMethod() != ValidationMethod.PHOTO
                || (StringUtils.isBlank(template.getSensorType()) && StringUtils.isBlank(template.getSensorConfig()))) {
            template.setSensorType(null);
            template.setSensorConfig(null);
            return;
        }
        throw new IllegalArgumentException("拍照滤镜类型无效");
    }

    private boolean isAppSensor(Integer validationMethod) {
        return SensorChallengePolicy.isAppSensor(validationMethod);
    }

    private boolean isPhotoFilter(Integer validationMethod, String sensorType) {
        return SensorChallengePolicy.isPhotoFilter(validationMethod, sensorType);
    }

    private void validateSensorFields(String sensorType, String sensorConfig) {
        SensorChallengePolicy.validateTemplateConfig(sensorType, sensorConfig);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTemplateWithSync(Long templateId, Long memberId) {
        CmsMemberTemplate memberTemplate = requireActiveTemplate(templateId);
        if (memberId == null || !memberId.equals(memberTemplate.getMemberId())) {
            throw new ServiceException("模板不存在或无权删除");
        }
        withdrawProjections(templateId, true, memberTemplate.getUpdateBy());
        cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE, templateId);
        memberTemplate.setDelFlag(2);
        memberTemplate.setPublishStatus(0L);
        memberTemplate.setUpdateTime(new Date());
        if (memberTemplateService.updateCmsMemberTemplate(memberTemplate) != 1) {
            throw new ServiceException("删除我的模板失败");
        }

        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateTemplateLibraryStatus(Long memberTemplateId, Integer publishStatus, Long memberId) {
        return updateTemplateLibraryStatus(memberTemplateId, publishStatus, memberId, null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateTemplateLibraryStatus(Long memberTemplateId, Integer publishStatus, Long memberId,
                                               TemplateLibraryRepublishReview review) {
        if (!Integer.valueOf(0).equals(publishStatus) && !Integer.valueOf(1).equals(publishStatus)) {
            throw new RuntimeException("发布状态仅支持 0 或 1");
        }
        CmsMemberTemplate memberTemplate = requireActiveTemplate(memberTemplateId);
        if (!memberTemplate.getMemberId().equals(memberId)) {
            throw new ServiceException("无权操作此模板", HttpStatus.FORBIDDEN);
        }
        // 不再看 isSync:本支(专业主题组合审核闭环)起,玩法库记录由「提交到模板广场」显式生成,
        // 有没有玩法库记录才是上架的真前提 —— 见下方 libraryTemplates 为空时的拒绝(M-06:原因透传)。
        if (Integer.valueOf(1).equals(publishStatus)
                && (!Long.valueOf(1L).equals(memberTemplate.getStatus())
                || !Integer.valueOf(1).equals(memberTemplate.getCheckStatus()))) {
            throw new ServiceException("模板审核通过后才能上架到玩法库", HttpStatus.BAD_REQUEST);
        }

        List<CmsTemplateLibrary> libraryTemplates = libraryMapper.selectByMemberTemplateForUpdate(memberTemplateId);
        if (libraryTemplates == null || libraryTemplates.isEmpty()) {
            // M-06:业务拒绝用 ServiceException,controller 才透传原因,不会被吞成「请稍后重试」
            throw new ServiceException("该模板还没有提交到模板广场，审核通过后才能上架", HttpStatus.BAD_REQUEST);
        }
        // 上架必须带锁外复检快照，且与锁内投影批次完全一致；缺失或漂移一律拒绝(fail closed)。
        if (Integer.valueOf(1).equals(publishStatus)) {
            for (CmsTemplateLibrary projection : libraryTemplates) {
                if (projection != null && Integer.valueOf(0).equals(projection.getDelFlag())
                        && !Long.valueOf(1L).equals(projection.getStatus())) {
                    throw new ServiceException("模板广场审核通过后才能上架", HttpStatus.BAD_REQUEST);
                }
            }
            if (review == null || !review.matches(memberTemplateId, memberId, libraryTemplates)) {
                throw new ServiceException("上架复检暂不可用，请刷新后重试", HttpStatus.BAD_REQUEST);
            }
            assertTemplateRewardOwnership(memberId, memberTemplate, null);
            for (CmsTemplateLibrary projection : libraryTemplates) {
                if (projection != null && Integer.valueOf(0).equals(projection.getDelFlag()))
                    assertRewardCouponsOwned(memberId, projection.getCouponId(), projection.getRewardRulesJson());
            }
        }
        boolean hasActiveProjection = false;
        for (CmsTemplateLibrary libraryTemplate : libraryTemplates) {
            if (Integer.valueOf(0).equals(libraryTemplate.getDelFlag())) {
                hasActiveProjection = true;
                if (Integer.valueOf(1).equals(publishStatus)
                        && !Long.valueOf(1L).equals(libraryTemplate.getStatus())) {
                    throw new ServiceException("模板广场审核通过后才能上架", HttpStatus.BAD_REQUEST);
                }
                if (Integer.valueOf(0).equals(publishStatus)) {
                    cancelOpenTemplateTasks(AuditTask.BIZ_TEMPLATE_LIBRARY, libraryTemplate.getId());
                    if (!Long.valueOf(1L).equals(libraryTemplate.getStatus())) {
                        libraryTemplate.setStatus(2L);
                        libraryTemplate.setReason("作者已撤回，请重新提交广场审核");
                    }
                }
                libraryTemplate.setPublishStatus(Convert.toLong(publishStatus));
                libraryTemplate.setUpdateTime(new Date());
                if (templateLibraryService.updateCmsTemplateLibrary(libraryTemplate) <= 0) {
                    throw new ServiceException("更新模板库投影失败", HttpStatus.BAD_REQUEST);
                }
            }
        }
        if (!hasActiveProjection) {
            throw new ServiceException("更新模板库状态失败", HttpStatus.BAD_REQUEST);
        }

        return true;
    }


    /**
     * 根据分类分组查询模版
     * @return
     */
    @Override
    public List<CategoryTemplatesVO> getTemplatesGroupByCategory() {
        SysCategory sysCategory = new SysCategory();
        sysCategory.setDelFlag(0);
        sysCategory.setStatus(1);
        sysCategory.setType(4L);
        sysCategory.setParentId(0L);

        List<SysCategory> categories = categoryService.selectSysCategoryList(sysCategory);

        if (categories == null || categories.isEmpty()) {
            return Collections.emptyList();
        }

        // 2. 收集分类ID
        List<Long> categoryIds = categories.stream()
                .map(SysCategory::getId)
                .collect(Collectors.toList());

        // 3. 批量查询这些分类下的模板
        List<TemplateLibraryVO> templates = templateLibraryService.selectTemplatesByCategoryIds(categoryIds);

        // 4. 按分类ID分组
        Map<Long, List<TemplateLibraryVO>> templatesByCategory = templates.stream()
                .collect(Collectors.groupingBy(TemplateLibraryVO::getCategoryId));

        // 5. 组装结果
        List<CategoryTemplatesVO> result = new ArrayList<>();

        for (SysCategory category : categories) {
            List<TemplateLibraryVO> categoryTemplates = templatesByCategory.get(category.getId());

            if (categoryTemplates != null && !categoryTemplates.isEmpty()) {
                CategoryTemplatesVO categoryVO = new CategoryTemplatesVO();
                categoryVO.setCategoryId(category.getId());
                categoryVO.setCategoryName(category.getCategoryName());
                categoryVO.setLevel(Convert.toInt(category.getLevel(),0));
                categoryVO.setIcon(category.getIcon());
                categoryVO.setParentId(category.getParentId());
                categoryVO.setSortId(Convert.toInt(category.getSortId(),0));
                categoryVO.setTemplates(categoryTemplates);

                result.add(categoryVO);
            }
        }
        // 6. 按sort_id排序
        result.sort(Comparator.comparingInt(CategoryTemplatesVO::getSortId));

        return result;
    }

    /**
     * 模板库首页聚合数据（App Store 风格）
     * 精选/必玩/热门榜均按 useNum 倒序自动取前 N，分类来自 SysCategory
     */
    @Override
    public TemplateHomeVO getHomeData() {
        TemplateHomeVO vo = new TemplateHomeVO();

        // 1. 全量公共模板（已上架、已审核、未删除）
        CmsTemplateLibrary query = new CmsTemplateLibrary();
        query.setDelFlag(0);
        query.setStatus(1L);
        query.setPublishStatus(1L);
        List<CmsTemplateLibrary> all = templateLibraryService.selectCmsTemplateLibraryList(query);
        if (all == null) {
            all = new ArrayList<>();
        }
        // 该聚合端点支持游客首屏，所有板块共用同一批公开投影。
        // 排序前统一剥离，避免新增板块时遗漏答案、付费提示或未公开玩法配置。
        all.forEach(TemplateSecrets::strip);

        // 2. 按 useNum 倒序（null 视为 0）
        all.sort(Comparator.comparingInt((CmsTemplateLibrary t) -> Convert.toInt(t.getUseNum(), 0)).reversed());

        // 3. 各板块自动取前 N
        vo.setTotal(all.size());
        vo.setBannerList(topN(all, 5));
        vo.setRecommendList(topN(all, 6));
        vo.setMustPlayList(topN(all, 3));
        vo.setHotList(topN(all, 8));

        // 4. 世纪一刻：按 createTime 倒序取最新
        List<CmsTemplateLibrary> latest = new ArrayList<>(all);
        latest.sort(Comparator.comparing(CmsTemplateLibrary::getCreateTime,
                Comparator.nullsLast(Comparator.reverseOrder())));
        vo.setLatestList(topN(latest, 6));

        // 5. 分类 Tab
        SysCategory cq = new SysCategory();
        cq.setDelFlag(0);
        cq.setStatus(1);
        cq.setType(4L);
        cq.setParentId(0L);
        List<SysCategory> categories = categoryService.selectSysCategoryList(cq);
        vo.setCategoryList(CategoryPublicProjection.strip(
                categories != null ? categories : Collections.emptyList()));

        return vo;
    }

    /** 取列表前 N 条（不足则全取） */
    private List<CmsTemplateLibrary> topN(List<CmsTemplateLibrary> list, int n) {
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(list.subList(0, Math.min(n, list.size())));
    }

    /** 作者修改/撤下/删除模板时给作者的申诉撤回说明(AUDIT-INT-9)。 */
    private static final String APPEAL_WITHDRAWN_BY_AUTHOR = "你修改、撤下或删除了被申诉的玩法，原申诉已自动撤回；如需上架请重新提交审核";

    /**
     * 撤销该条目在途的模板审核单。AUDIT-INT-9:先把未结申诉以 WITHDRAWN 结案并通知作者(与活动/主题编辑撤回申诉同口径),
     * 再撤其余在途审核单 —— 申诉单不再被静默置 4、留下永远 SUBMITTED 的申诉记录。
     */
    private void cancelOpenTemplateTasks(String bizType, Long bizId) {
        auditTaskService.withdrawOpenAppeal(bizType, bizId, APPEAL_WITHDRAWN_BY_AUTHOR);
        auditTaskMapper.cancelOpenTemplateTasks(bizType, bizId);
    }
}
