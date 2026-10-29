package com.chengyinhub.web.controller.api;


import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CmsTemplatePublishRequest;
import com.chengyinhub.business.domain.vo.CategoryTemplatesVO;
import com.chengyinhub.business.domain.vo.MemberTemplateVO;
import com.chengyinhub.business.domain.vo.PublicTemplateListItemVO;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICmsTemplateLibraryService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.support.TemplateLibraryRepublishReview;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.util.JourneyReviewTexts;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.service.ISysCategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.validation.annotation.Validated;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.ArrayList;
import java.util.List;

import com.chengyinhub.common.annotation.RateLimiter;
import com.chengyinhub.common.enums.LimitType;
/**
 * 模版
 *
 * @author webxh
 * @date 2025-09-03
 */
@RestController
@Tag(name = "模版")
@RequestMapping("/api/template")
public class ApiTemplateController extends BaseController {

    /** O5b-5:发布时标题相似度提示(WARN,不拦)。 */
    @Autowired(required = false)
    private com.chengyinhub.business.service.support.TemplateTitleSimilarityService templateTitleSimilarityService;

    @Autowired
    private ITemplatePublishService templatePublishService;

    @Autowired
    private com.chengyinhub.business.service.support.TemplateAdoptionService templateAdoptionService;

    @Autowired
    private ICmsMemberTemplateService memberTemplateService;

    @Autowired
    private ICmsTemplateLibraryService templateLibraryService;

    @Autowired
    private com.chengyinhub.business.service.IAuditTaskService auditTaskService;

    @Autowired
    private ISysCategoryService sysCategoryService;


    @Autowired
    private IWxContentSecurityService wxContentSecurityService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private ICouponRewardAuthorizationService couponRewardAuthorizationService;

    @Autowired
    private IMerchantAccessService merchantAccessService;


    @Operation(summary = "发布模板")
    @Log(title = "发布模板", businessType = BusinessType.INSERT)
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    @PostMapping("/publish")
    public AjaxResult publish(@RequestBody @Validated CmsTemplatePublishRequest request) {
        try {
            // 获取当前登录用户ID
            Long principalMemberId = getAppUserId();
            // 参数校验
            if (principalMemberId == null || principalMemberId <= 0) {
                return error("请先登录");
            }
            Long member_id = resolveTemplateOwner(principalMemberId, request.getScope());
            try {
                couponRewardAuthorizationService.assertOwnedRewardCoupon(request.getCouponId(), member_id);
            } catch (IllegalArgumentException e) {
                return error(e.getMessage());
            }

            String username = getAppUserName();

            CmsMemberTemplate memberTemplate = new CmsMemberTemplate();
            memberTemplate.setMemberId(member_id);
            memberTemplate.setTitle(request.getTitle());
            // 编辑自己那份时把它排除在重名检查之外(归属校验在 service 层做)
            memberTemplate.setId(request.getId());
            int num = memberTemplateService.selectNumByTitle(memberTemplate);
            if (num > 0) {
                return error("模板名称 " + request.getTitle() + " 已存在");
            }

            // 内容安全(X02):模板进公共模板库对所有用户可见,文本必须先过检再入库。
            String secMsg = templateTextSecurity(request, member_id);
            if (secMsg != null) {
                return error(secMsg);
            }

            // 调用Service层发布模板
            Long id = templatePublishService.publishTemplateWithTransaction(request, member_id, username);
            if (id > 0) {
                submitTemplateMediaCheck(id, request, member_id);
                AjaxResult ok = success(authorTemplateId(id));
                // O5b-5(开关默认关):标题与广场在架模板高度相似只提示,不拦;无提示时不带 warnings,响应形状不变。
                java.util.List<com.chengyinhub.business.service.support.TopicPublishCheckService.PublishCheckIssue> warnings =
                        templateTitleSimilarityService == null ? null
                                : templateTitleSimilarityService.check(request.getTitle(), member_id);
                if (warnings != null && !warnings.isEmpty()) ok.put("warnings", warnings);
                return ok;
            } else {
                return error("发布失败");
            }

        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            logger.error("发布模板失败：", e);
            return AjaxResult.error("发布模板失败，请稍后重试");
        }
    }

    @Operation(summary = "保存草稿")
    @Log(title = "保存模板草稿", businessType = BusinessType.INSERT)
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/draft")
    public AjaxResult draft(@RequestBody CmsTemplatePublishRequest request) {
        try {
            Long principalMemberId = getAppUserId();
            if (principalMemberId == null || principalMemberId <= 0) {
                return error("请先登录");
            }
            Long memberId = resolveTemplateOwner(principalMemberId, request.getScope());
            // 采用入口只接收来源身份；标题与优惠券由 service 从可见原单读取并校验。
            // 普通草稿仍校验调用者实际提交的内容和发券权限。
            if (!Boolean.TRUE.equals(request.getAdoptSourceOnly())) {
                if (StringUtils.isBlank(request.getTitle())) {
                    return error("请填写游戏标题");
                }
                try {
                    couponRewardAuthorizationService.assertOwnedRewardCoupon(request.getCouponId(), memberId);
                } catch (IllegalArgumentException e) {
                    return error(e.getMessage());
                }
            }
            String username = getAppUserName();
            // 草稿不做全局重名拦截(覆盖更新自己的草稿不应被拦)
            Long id = templatePublishService.saveOrUpdateDraft(request, memberId, username);
            return success(authorTemplateId(id));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            logger.error("保存草稿失败：", e);
            return error("保存草稿失败，请稍后重试");
        }
    }

    private Object authorTemplateId(Long id) {
        var attributes = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        if (id != null && attributes instanceof org.springframework.web.context.request.ServletRequestAttributes servlet
                && "1".equals(servlet.getRequest().getHeader("X-Author-Exact-Ids"))) return id.toString();
        return id;
    }

    @Operation(summary = "采用来源模板为草稿")
    @Log(title = "采用模板草稿", businessType = BusinessType.INSERT)
    @RateLimiter(time = 60, count = 30, limitType = LimitType.MEMBER)
    @PostMapping("/adopt-draft")
    public AjaxResult adoptDraft(@RequestBody com.chengyinhub.business.domain.dto.TemplateAdoptionCommand request) {
        try {
            if (request == null) return error("请指定来源模板");
            Long actor = getAppUserId();
            if (actor == null || actor <= 0) return error("请先登录");
            Long owner = resolveTemplateOwner(actor, request.getScope());
            return success(templateAdoptionService.adopt(actor, owner, request, getAppUserName()));
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
    }

    @GetMapping("/adoption-source")
    public AjaxResult adoptionSource(@RequestParam String originalTemplateId, @RequestParam(required=false) String scope) {
        try {
            Long actor = getAppUserId();
            if (actor == null || actor <= 0) return error("请先登录");
            return success(templateAdoptionService.source(actor, resolveTemplateOwner(actor, scope), originalTemplateId));
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
    }

    @GetMapping("/adoption-receipt")
    public AjaxResult adoptionReceipt(@RequestParam String requestId, @RequestParam(required=false) String scope) {
        try {
            Long actor = getAppUserId();
            if (actor == null || actor <= 0) return error("请先登录");
            return success(templateAdoptionService.receipt(actor, resolveTemplateOwner(actor, scope), scope, requestId));
        } catch (ServiceException e) { return new AjaxResult(e.getCode(), e.getMessage()); }
    }

    @GetMapping("/adoption-detail")
    public AjaxResult adoptionDetail(@RequestParam String requestId, @RequestParam(required=false) String scope) {
        AjaxResult receipt = adoptionReceipt(requestId, scope);
        if (!Integer.valueOf(200).equals(receipt.get(AjaxResult.CODE_TAG))) return receipt;
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> facts = (java.util.Map<String, Object>) receipt.get(AjaxResult.DATA_TAG);
        if (!"COMPLETED".equals(facts.get("status"))) return new AjaxResult(409, "采用尚未完成");
        AjaxResult detail = myInfo(String.valueOf(facts.get("draftId")), scope);
        if (!Integer.valueOf(200).equals(detail.get(AjaxResult.CODE_TAG))) return detail;
        Object data = detail.get(AjaxResult.DATA_TAG);
        if (!(data instanceof CmsMemberTemplate template)) return new AjaxResult(409, "采用详情尚未确认");
        if (!templateAdoptionService.matchesDraft(facts, template)) return new AjaxResult(409, "采用草稿内容版本已变化，请从我的玩法明确选择当前版本");
        return success(com.chengyinhub.business.service.support.TemplateAdoptionWire.strings(com.chengyinhub.business.service.support.TemplateAdoptionWire.strings(template, "id"), "originalTemplateId"));
    }

    @PostMapping("/author-info")
    public AjaxResult authorInfo(String id) {
        AjaxResult result = producInfo(id);
        if (Integer.valueOf(200).equals(result.get(AjaxResult.CODE_TAG)))
            result.put(AjaxResult.DATA_TAG, com.chengyinhub.business.service.support.TemplateAdoptionWire.strings(com.chengyinhub.business.service.support.TemplateAdoptionWire.strings(result.get(AjaxResult.DATA_TAG), "id"), "originalTemplateId"));
        return result;
    }

    @PostMapping("/author-myinfo")
    public AjaxResult authorMyInfo(String id, String scope) {
        AjaxResult result = myInfo(id, scope);
        if (Integer.valueOf(200).equals(result.get(AjaxResult.CODE_TAG)))
            result.put(AjaxResult.DATA_TAG, com.chengyinhub.business.service.support.TemplateAdoptionWire.strings(com.chengyinhub.business.service.support.TemplateAdoptionWire.strings(result.get(AjaxResult.DATA_TAG), "id"), "originalTemplateId"));
        return result;
    }

    @PostMapping("/adoption-home")
    public AjaxResult adoptionHome() {
        AjaxResult result = getHomeData();
        if (Integer.valueOf(200).equals(result.get(AjaxResult.CODE_TAG)))
            result.put(AjaxResult.DATA_TAG, com.chengyinhub.business.service.support.TemplateAdoptionWire.strings(com.chengyinhub.business.service.support.TemplateAdoptionWire.strings(result.get(AjaxResult.DATA_TAG), "id"), "originalTemplateId"));
        return result;
    }

    @Operation(summary = "提交模板广场审核")
    @Log(title = "提交模板广场审核", businessType = BusinessType.INSERT)
    @RateLimiter(time = 60, count = 10, limitType = LimitType.MEMBER)
    @PostMapping("/{memberTemplateId}/market-submit")
    public AjaxResult submitTemplateToMarket(@PathVariable Long memberTemplateId,
                                             @RequestParam(required = false) String scope) {
        try {
            Long principalMemberId = getAppUserId();
            if (principalMemberId == null || principalMemberId <= 0) {
                return error("请先登录");
            }
            Long memberId = resolveTemplateOwner(principalMemberId, scope);
            Long taskId = templatePublishService.submitTemplateToMarket(
                    memberTemplateId, memberId, getAppUserName());
            return success(taskId);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            logger.error("提交模板广场审核失败：", e);
            return error("提交模板广场审核失败，请稍后重试");
        }
    }

    @Operation(summary = "删除模板")
    @Log(title = "删除模板", businessType = BusinessType.UPDATE)
    @PostMapping("/delete")
    @Parameters({
            @Parameter(name = "template_id", description = "模版ID")
    })
    public AjaxResult deleteTemplate(String template_id, String scope) {
        try {
            // 获取当前登录用户ID
            Long principalMemberId = getAppUserId();
            if (principalMemberId == null || principalMemberId <= 0) {
                return error("请先登录");
            }
            Long memberId = resolveTemplateOwner(principalMemberId, scope);

            Long templateId = Convert.toLong(template_id, 0L);
            // 调用事务方法删除模板
            boolean result = templatePublishService.deleteTemplateWithSync(templateId, memberId);

            if (result) {
                return success("模板删除成功");
            } else {
                return error("模板删除失败");
            }

        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            logger.error("删除模板失败：", e);
            return error("删除模板失败，请稍后重试");
        }
    }

    @Operation(summary = "上下架模板库记录")
    @Log(title = "上下架模板库记录", businessType = BusinessType.UPDATE)
    @RateLimiter(time = 60, count = 20, limitType = LimitType.MEMBER)
    @PostMapping("/updateLibraryStatus")
    @Parameters({
            @Parameter(name = "template_id", description = "模版ID", required = true),
            @Parameter(name = "publish_status", description = "状态：0下架  1上架", required = true)
    })
    public AjaxResult updateTemplateLibraryStatus(String template_id, String publish_status, String scope) {

        try {
            // 获取当前登录用户ID
            Long principalMemberId = getAppUserId();
            if (principalMemberId == null || principalMemberId <= 0) {
                return error("请先登录");
            }
            Long memberId = resolveTemplateOwner(principalMemberId, scope);

            Long memberTemplateId = Convert.toLong(template_id, 0L);
            Integer publishStatus = Convert.toInt(publish_status, 2);
            // 参数验证
            if (publishStatus != 0 && publishStatus != 1) {
                return error("发布状态参数错误");
            }

            // 内容安全(X02):上架 = 重新对所有用户可见,必须复检一次。
            // 下架不检 —— 把违规内容撤下来的动作不该被内容安全拦住。
            TemplateLibraryRepublishReview review = publishStatus == 1
                    ? libraryRecordTextSecurity(memberTemplateId, memberId) : null;

            // 网络复检在锁外完成；事务内绑定完整被检内容后才允许写入。
            boolean result = publishStatus == 1
                    ? templatePublishService.updateTemplateLibraryStatus(memberTemplateId, publishStatus, memberId, review)
                    : templatePublishService.updateTemplateLibraryStatus(memberTemplateId, publishStatus, memberId);

            String statusText = publishStatus == 1 ? "上架" : "下架";
            if (result) {
                return success("模板库记录" + statusText + "成功");
            } else {
                return error("模板库记录" + statusText + "失败");
            }

        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            logger.error("更新模板库状态失败：", e);
            return error("更新模板库状态失败，请稍后重试");
        }
    }


    @Operation(summary = "我的模版列表")
    @PostMapping("/my-list")
    @Parameters({
            @Parameter(name = "is_quote", description = "发布活动/主题引用模版填写1"),
            @Parameter(name = "keyword", description = "搜索关键词"),
            @Parameter(name = "category_id", description = "分类ID"),
            @Parameter(name = "draft_status", description = "0草稿 1已发布,不传全部")
    })
    public AjaxResult myTemplateList(String is_quote, String keyword, String category_id,
                                     String draft_status, String scope) {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }
        if (member_id < 1) {
            return error("请先登录");
        }

        try {
            member_id = resolveTemplateOwner(member_id, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }

        MemberTemplateVO memberTemplateVO = new MemberTemplateVO();
        if (StringUtils.isNotEmpty(keyword)) {
            memberTemplateVO.setTitle(keyword);
        }
        if (StringUtils.isNotEmpty(category_id)) {
            memberTemplateVO.setActivityCategoryids(category_id);
        }
        if (StringUtils.isNotEmpty(is_quote)) {
            //如果是引用模版发布主题或者活动，状态需要时已审核的
            memberTemplateVO.setStatus(1L);
            memberTemplateVO.setDraftStatus(1); // 引用发布只取已发布(非草稿)
        } else if (StringUtils.isNotEmpty(draft_status)) {
            // 我的模板页可按 draft_status 筛:0只看草稿 / 1只看已发布
            memberTemplateVO.setDraftStatus(Convert.toInt(draft_status));
        }
        memberTemplateVO.setMemberId(member_id);

        List<MemberTemplateVO> list;
        startPage();
        list = memberTemplateService.selectMemberTemplateList(memberTemplateVO);

        TableDataInfo data = getDataTable(list);
        return AjaxResult.success(data);
    }


    /**
     * 模板自由文本内容安全(X02)。
     *
     * <p>模板会进「公共模板库」被所有用户看到,与广场帖/俱乐部资料同属公开 UGC,
     * 却是全仓唯一一条没有送检的通路。这里补上,口径与
     * {@code ApiCreativeSquareController.checkTextSecurity} 一致:命中即拒绝入库。
     *
     * @return 违规提示;null = 通过
     */
    private String templateTextSecurity(CmsTemplatePublishRequest request, Long memberId) {
        String content = IWxContentSecurityService.joinText(
                request.getTitle(), request.getDescription(), request.getPlayers(),
                request.getUsageLocation(), request.getRequiredMaterials(),
                request.getRuleInstructions(), request.getQuestionName(),
                request.getQuestionAnswer(), request.getFeedbackText(), request.getMedalName(),
                request.getQuestionA(), request.getQuestionB(),
                request.getQuestionC(), request.getQuestionD());
        String openid = openidOfMember(memberId);
        if (!StringUtils.isEmpty(content)) {
            String secMsg = wxContentSecurityService.checkText(content, openid);
            if (secMsg != null) {
                return secMsg;
            }
        }
        // R12:节点块配置叙事段(enter/outcome/deliver/unlock/revisit/brief/route/check)是创作者可写
        // 文本,与上面各字段同一道内容安全闸;命中时提示里带字段名。
        // F-L5C-13:提示、揭晓、故事、拍照要求与奖励文案同样是玩家可见文字,按字段分批送检。
        java.util.List<JourneyReviewTexts.Entry> texts = JourneyReviewTexts.collectTemplateFields(request);
        texts.addAll(JourneyReviewTexts.collectBlocks(request.getAdvancedConfigJson()));
        return JourneyReviewTexts.check(wxContentSecurityService, openid, texts);
    }

    /** 模板封面与勋章图异步送检(X02):违规经 /api/content-sec/callback 自动下架。 */
    private void submitTemplateMediaCheck(Long templateId, CmsTemplatePublishRequest request, Long memberId) {
        if (templateId == null || templateId <= 0) {
            return;
        }
        StringBuilder imgs = new StringBuilder();
        for (String url : new String[] { request.getImgUrl(), request.getMedalImg() }) {
            if (StringUtils.isNotEmpty(url)) {
                if (imgs.length() > 0) {
                    imgs.append(',');
                }
                imgs.append(url);
            }
        }
        if (imgs.length() == 0) {
            return;
        }
        // best-effort:送检登记失败不该把已经发布成功的模板回滚掉,与俱乐部资料同法。
        try {
            wxContentSecurityService.submitMediaCheck(
                    IWxContentSecurityService.BIZ_TYPE_TEMPLATE, templateId, imgs.toString(), openidOfMember(memberId));
        } catch (Exception ignore) {
        }
    }


    /** 上架前按我的模板与归属复检全部有效玩法库投影；读取失败不允许跳过审核。 */
    private TemplateLibraryRepublishReview libraryRecordTextSecurity(Long memberTemplateId, Long memberId) {
        final String unavailable = "上架复检暂不可用，请刷新后重试";
        if (memberTemplateId == null || memberTemplateId <= 0 || memberId == null || memberId <= 0) {
            throw new ServiceException(unavailable, 500);
        }
        CmsTemplateLibrary query = new CmsTemplateLibrary();
        query.setMemberTemplateId(memberTemplateId);
        query.setMemberId(memberId);
        query.setDelFlag(0);
        List<CmsTemplateLibrary> records;
        try {
            records = templateLibraryService.selectCmsTemplateLibraryList(query);
        } catch (Exception e) {
            logger.warn("上架复检读取模板库投影失败 memberTemplateId={}", memberTemplateId, e);
            throw new ServiceException(unavailable, 500);
        }
        if (records == null || records.isEmpty()) {
            throw new ServiceException(unavailable, 500);
        }
        // 固化完整内容及坐标；送检只读取不可变文本，不能再读取可变领域对象。
        TemplateLibraryRepublishReview review = TemplateLibraryRepublishReview.freeze(
                memberTemplateId, memberId, records);
        String openid = openidOfMember(memberId);
        for (TemplateLibraryRepublishReview.ReviewedText record : review.getReviewedTexts()) {
            String content = IWxContentSecurityService.joinText(
                    record.getFreeTextFields().toArray(new String[0]));
            if (!StringUtils.isEmpty(content)) {
                String secMsg = wxContentSecurityService.checkText(content, openid);
                if (secMsg != null) {
                    throw new ServiceException(secMsg, 500);
                }
            }
            // R12:上架复检同样覆盖块配置叙事段。
            String secMsg = JourneyReviewTexts.check(wxContentSecurityService, openid,
                    JourneyReviewTexts.collectBlocks(record.getAdvancedConfigJson()));
            if (secMsg != null) {
                throw new ServiceException(secMsg, 500);
            }
        }
        return review;
    }

    private String openidOfMember(Long memberId) {
        try {
            if (memberId != null && memberId > 0) {
                UmsMember m = umsMemberService.selectUmsMemberById(memberId);
                if (m != null) {
                    return m.getWxOpenId();
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    private Long resolveTemplateOwner(Long principalMemberId, String scope) {
        if (!MerchantOperationScope.isMerchant(scope)) {
            return principalMemberId;
        }
        return merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE)
                .getOwnerMemberId();
    }


    @Operation(summary = "公共模板库列表")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "keyword", description = "搜索关键词"),
            @Parameter(name = "category_id", description = "分类ID"),
            @Parameter(name = "categoryId", description = "分类ID(驼峰别名，等价于 category_id)"),
            @Parameter(name = "pack_type", description = "模板形态:0单节点玩法 1剧情包 2店铺IP包;不传=全部")
    })
    public AjaxResult commonTemplateList(String keyword, String category_id, String categoryId, String pack_type) {
        CmsTemplateLibrary cmsTemplateLibrary = new CmsTemplateLibrary();
        if (StringUtils.isNotEmpty(keyword)) {
            cmsTemplateLibrary.setTitle(keyword);
        }
        // ★ 蛇形 + 驼峰**都收**：文档口径是 category_id，但两个客户端发的一直是驼峰
        //   categoryId(小程序 chengyinhub-xcx/subpackageTalent/search/index.js；
        //   App 在另一个仓 ~/城瘾app 的 app/lib/data/api/template_api.dart)。
        //   Spring 按形参名绑定，名字对不上就绑不到 —— 分类筛选**静默失效**，
        //   不报错不告警，只是永远返回全部模板。收两种名字才没有版本错配窗口：
        //   已发版的旧客户端改不动，而窗口期里的失效同样是静默的。
        //   两个都传时以文档口径 category_id 为准，别看运气。
        String resolvedCategoryId = StringUtils.isNotEmpty(category_id) ? category_id : categoryId;
        if (StringUtils.isNotEmpty(resolvedCategoryId)) {
            cmsTemplateLibrary.setActivityCategoryids(resolvedCategoryId);
        }
        // ★ 只认 0/1/2 三个已定义的形态。
        //   认不出的值(前端传错、将来加了新形态但后端还没上)**按不过滤**处理,
        //   而不是按 0 —— 按 0 会让人以为「筛选生效了、这个形态下就这么几个」,
        //   实际看到的是另一个形态的列表。宁可多给,不可给错。
        Integer packType = parsePackType(pack_type);
        if (packType != null) {
            cmsTemplateLibrary.setPackType(packType);
        }

        cmsTemplateLibrary.setDelFlag(0);
        cmsTemplateLibrary.setStatus(1L);
        cmsTemplateLibrary.setPublishStatus(1L);


        List<CmsTemplateLibrary> list;
        startPage();
        list = templateLibraryService.selectCmsTemplateLibraryList(cmsTemplateLibrary);
        TableDataInfo data = getDataTable(list);
        List<PublicTemplateListItemVO> publicRows = new ArrayList<>(list.size());
        for (CmsTemplateLibrary row : list) {
            publicRows.add(PublicTemplateListItemVO.from(row));
        }
        data.setRows(publicRows);
        return AjaxResult.success(data);
    }

    /** 解析模板形态入参。认不出的一律返回 null(不过滤),见调用处注释。 */
    static Integer parsePackType(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            int value = Integer.parseInt(trimmed);
            return (value >= 0 && value <= 2) ? Integer.valueOf(value) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Operation(summary = "按分类分组获取模板列表")
    @PostMapping("/groupByCategory")
    public AjaxResult getTemplatesGroupByCategory() {
        try {
            List<CategoryTemplatesVO> result = templatePublishService.getTemplatesGroupByCategory();
            return success(result);
        } catch (Exception e) {
            logger.error("按分类分组获取模板失败：", e);
            return error("按分类分组获取模板失败，请稍后重试");
        }
    }

    @Operation(summary = "模板库首页聚合数据(App Store风格)")
    @PostMapping("/homeData")
    public AjaxResult getHomeData() {
        try {
            return success(templatePublishService.getHomeData());
        } catch (Exception e) {
            logger.error("获取模板库首页数据失败：", e);
            return error("获取模板库首页数据失败，请稍后重试");
        }
    }

    @Operation(summary = "公共模版详情")
    @PostMapping("/info")
    @Parameters({
            @Parameter(name = "id", description = "公共模版id")
    })
    public AjaxResult producInfo(String id) {
        CmsTemplateLibrary cmsTemplateLibrary = new CmsTemplateLibrary();
        Long templateId = Convert.toLong(id, 0L);

        if (templateId > 0) {
            cmsTemplateLibrary.setId(templateId);
        } else {
            return error("参数不合规");
        }

        cmsTemplateLibrary = templateLibraryService.selectCmsTemplateLibraryById(templateId);
        if (cmsTemplateLibrary == null) {
            return error("模版不存在");
        } else {
            if (!Integer.valueOf(0).equals(cmsTemplateLibrary.getDelFlag())
                    || !Long.valueOf(1L).equals(cmsTemplateLibrary.getStatus())
                    || !Long.valueOf(1L).equals(cmsTemplateLibrary.getPublishStatus())) {
                return error("模版不存在");
            } else {
                // 名字收口在 ValidationMethod#label:这串映射原本在本文件里有两份,
                // 加一个新完成方式要改两处,漏一处就是某个入口显示空白。
                cmsTemplateLibrary.setValidationMethodStr(
                        ValidationMethod.label(cmsTemplateLibrary.getValidationMethod()));

                if (Convert.toLong(cmsTemplateLibrary.getCouponId(), 0L) > 0) {
                    cmsTemplateLibrary.setFeedbackMethodStr("优惠券反馈");
                } else {
                    cmsTemplateLibrary.setFeedbackMethodStr("文字反馈");
                }


                String categoryIds = cmsTemplateLibrary.getActivityCategoryids();
                if (!StringUtils.isEmpty(categoryIds)) {
                    String[] ids = categoryIds.split(",");
                    List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                    if (listCategory.size() > 0) {
                        cmsTemplateLibrary.setSysCategoryList(listCategory);
                    }
                }

                UmsMember umsMember = umsMemberService.selectUmsMemberById(cmsTemplateLibrary.getMemberId());
                if (umsMember != null) {
                    cmsTemplateLibrary.setPublisher(umsMember.getName());
                }

                templatePublishService.enrichPublicRewardProjection(cmsTemplateLibrary);

                // [越权/防作弊] 公共库详情非 owner 剥答案与提示。
                // ⚠️ 引用回填其实会消费这些字段(pages/publish/temp fillFormWithTemplateData 回填
                //    questionAnswer 等):非 owner 采用时拿到的是剥离后的空值,v5.1 的
                //    blindTaste.answerKey / dailySign.poems 由发布路径服务端补回
                //    (TemplatePublishServiceImpl#backfillAdoptedSecrets),明文仍不出服务端。
                Long viewerId = 0L;
                try {
                    viewerId = getAppUserId();
                } catch (Exception ignore) {
                }
                if (!viewerId.equals(cmsTemplateLibrary.getMemberId())) {
                    com.chengyinhub.business.util.TemplateSecrets.strip(cmsTemplateLibrary);
                }

                return success(cmsTemplateLibrary);
            }
        }

    }


    @Operation(summary = "举报模板广场记录(进统一审核队列,不立即下架;同一人同一轮只记一次)")
    @PostMapping("/report")
    @Parameters({
            @Parameter(name = "id", description = "公共模版id"),
            @Parameter(name = "reason", description = "举报原因")
    })
    public AjaxResult libraryReport(String id, String reason) {
        Long libraryId = Convert.toLong(id, 0L);
        if (libraryId <= 0) return error("参数不合规");
        Long reporterId = currentReporter();
        if (reporterId == null) return error("请先登录");
        // 与 /info 同一公开谓词:不公开的记录对外「不存在」,也不能借举报建单。
        CmsTemplateLibrary row = templateLibraryService.selectCmsTemplateLibraryById(libraryId);
        if (row == null || !Integer.valueOf(0).equals(row.getDelFlag())
                || !Long.valueOf(1L).equals(row.getStatus()) || !Long.valueOf(1L).equals(row.getPublishStatus())) {
            return error("模版不存在");
        }
        return submitReport(com.chengyinhub.business.domain.AuditTask.BIZ_TEMPLATE_LIBRARY, libraryId, reporterId, reason);
    }

    @Operation(summary = "举报公开的个人玩法(进统一审核队列,不立即下架;同一人同一轮只记一次)")
    @PostMapping("/personal-report")
    @Parameters({
            @Parameter(name = "id", description = "个人玩法id"),
            @Parameter(name = "reason", description = "举报原因")
    })
    public AjaxResult personalTemplateReport(String id, String reason) {
        Long templateId = Convert.toLong(id, 0L);
        if (templateId <= 0) return error("参数不合规");
        Long reporterId = currentReporter();
        if (reporterId == null) return error("请先登录");
        // 与 /myinfo 非本人可读的同一谓词:已审核、已上架、非草稿。
        CmsMemberTemplate template = memberTemplateService.selectCmsMemberTemplateById(templateId);
        if (template == null || !Integer.valueOf(0).equals(template.getDelFlag())
                || !Integer.valueOf(1).equals(template.getDraftStatus())
                || !Long.valueOf(1L).equals(template.getStatus()) || !Long.valueOf(1L).equals(template.getPublishStatus())) {
            return error("模版不存在");
        }
        return submitReport(com.chengyinhub.business.domain.AuditTask.BIZ_TEMPLATE, templateId, reporterId, reason);
    }

    private Long currentReporter() {
        try {
            Long memberId = getAppUserId();
            return memberId != null && memberId > 0 ? memberId : null;
        } catch (Exception e) {
            return null;
        }
    }

    private AjaxResult submitReport(String bizType, Long bizId, Long reporterId, String reason) {
        try {
            boolean recorded = auditTaskService.reportContentOnce(bizType, bizId, reporterId, reason);
            return success(recorded ? "举报已提交，内容将进入审核" : "你已举报过该模版，我们会尽快处理");
        } catch (IllegalArgumentException | ServiceException e) {
            return error(e.getMessage());
        } catch (Exception e) {
            logger.error("模版举报失败 bizType={} bizId={}", bizType, bizId, e);
            return error("举报失败");
        }
    }

    @Operation(summary = "我的模版详情")
    @PostMapping("/myinfo")
    @Parameters({
            @Parameter(name = "id", description = "公共模版id")
    })
    public AjaxResult myInfo(@RequestParam(name = "id", required = false) String id,
                             @RequestParam(name = "scope", required = false) String scope) {
        CmsMemberTemplate cmsMemberTemplate = new CmsMemberTemplate();
        Long templateId = Convert.toLong(id, 0L);

        if (templateId > 0) {
            cmsMemberTemplate.setId(templateId);
        } else {
            return error("参数不合规");
        }

        Long viewerId = getAppUserId();
        if (viewerId == null || viewerId <= 0) return error("请先登录");
        Long ownerId;
        try {
            ownerId = resolveTemplateOwner(viewerId, scope);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        cmsMemberTemplate = memberTemplateService.selectCmsMemberTemplateById(templateId);
        if (cmsMemberTemplate == null) {
            return error("模版不存在");
        } else {
            boolean isOwner = ownerId != null && ownerId.equals(cmsMemberTemplate.getMemberId());
            cmsMemberTemplate.setCanEdit(isOwner);
            // 草稿(draftStatus=0)允许本人回填编辑,跳过"审核中/已下架"拦截
            boolean isDraft = cmsMemberTemplate.getDraftStatus() != null && cmsMemberTemplate.getDraftStatus() == 0;
            boolean isOwnerPending = isOwner
                    && Integer.valueOf(0).equals(cmsMemberTemplate.getDelFlag())
                    && Integer.valueOf(1).equals(cmsMemberTemplate.getDraftStatus())
                    && Long.valueOf(0L).equals(cmsMemberTemplate.getStatus());
            boolean isPublicEligible = Integer.valueOf(0).equals(cmsMemberTemplate.getDelFlag())
                    && !isDraft
                    && Long.valueOf(1L).equals(cmsMemberTemplate.getStatus())
                    && Long.valueOf(1L).equals(cmsMemberTemplate.getPublishStatus());
            if (!isOwner && !isPublicEligible) {
                // 非本人只能读取已审核、已上架实体；所有私有状态与不存在保持同一外观，避免枚举。
                return error("模版不存在");
            } else if (Integer.valueOf(2).equals(cmsMemberTemplate.getDelFlag())) {
                return error("模版已删除");
            } else if (!isDraft && !isOwnerPending && !Long.valueOf(1L).equals(cmsMemberTemplate.getStatus())) {
                return error(Long.valueOf(2L).equals(cmsMemberTemplate.getStatus()) ? "模版审核未通过" : "模版审核中");
            } else if (!isDraft && !isOwnerPending && cmsMemberTemplate.getPublishStatus() == 0) {
                return error("模版已下架");
            } else {
                cmsMemberTemplate.setValidationMethodStr(
                        ValidationMethod.label(cmsMemberTemplate.getValidationMethod()));

                if (Convert.toLong(cmsMemberTemplate.getCouponId(), 0L) > 0) {
                    cmsMemberTemplate.setFeedbackMethodStr("优惠券反馈");
                } else {
                    cmsMemberTemplate.setFeedbackMethodStr("文字反馈");
                }


                String categoryIds = cmsMemberTemplate.getActivityCategoryids();
                if (!StringUtils.isEmpty(categoryIds)) {
                    String[] ids = categoryIds.split(",");
                    List<SysCategory> listCategory = sysCategoryService.selectSysCategoryListByIds(ids);
                    if (listCategory.size() > 0) {
                        cmsMemberTemplate.setSysCategoryList(listCategory);
                    }
                }

                UmsMember umsMember = umsMemberService.selectUmsMemberById(cmsMemberTemplate.getMemberId());
                if (umsMember != null) {
                    cmsMemberTemplate.setPublisher(umsMember.getName());
                }

                if (!isOwner) {
                    com.chengyinhub.business.util.TemplateSecrets.strip(cmsMemberTemplate);
                }

                return success(cmsMemberTemplate);
            }
        }

    }
}
