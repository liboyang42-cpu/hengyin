package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.util.JourneyReviewTexts;
import com.chengyinhub.business.util.QuestionOptionMedia;
import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.domain.RoamPoi;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.domain.dto.RoamPoiApplicationRequest;
import com.chengyinhub.business.domain.vo.CityNodeVO;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ICouponRewardAuthorizationService;
import com.chengyinhub.business.service.IMmsMerchantService;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.IRoamPoiApplicationService;
import com.chengyinhub.business.service.ITemplatePublishService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.business.service.IQrcodeService;
import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.constant.ValidationMethod;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.utils.VerifyDynCodeUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 漫游 · 商家自助「成为节点」。
 * 提交互动模板(免人工审 + msgSecCheck)、据点投放/认领申请、我的据点、上/下线。
 * 门禁复用 user_permission.can_open_as_node(仅商家);配额 user_permission.max_city_nodes;
 * 据点申请与资产都落 roam_poi；status 只表示可见性，auditStatus 独立表示审核态。
 */
@RestController
@Tag(name = "商家成为节点")
@RequestMapping("/api/merchant/city-node")
public class ApiMerchantNodeController extends BaseController {

    @Autowired private RoamMapper roamMapper;
    @Autowired private IQrcodeService qrcodeService;

    /** 张贴码密钥:与核销动态码同一把,口径见 ApiVerifyController#dynSecret */
    @Value("${verify.dyncode-secret:${token.secret}}")
    private String dynSecret;
    @Autowired private IMmsMerchantService mmsMerchantService;
    @Autowired private ICmsMemberTemplateService templateService;
    @Autowired private ITemplatePublishService templatePublishService;
    @Autowired private IWxContentSecurityService wxSec;
    @Autowired private IRoleService roleService;
    @Autowired private IUmsMemberService umsMemberService;
    @Autowired private ICouponRewardAuthorizationService couponRewardAuthorizationService;
    @Autowired private com.chengyinhub.business.service.impl.MerchantCommerceService merchantCommerceService;
    @Autowired private IRoamPoiApplicationService roamPoiApplicationService;
    @Autowired private IMerchantAccessService merchantAccessService;

    private Long uid() {
        try { return getAppUserId(); } catch (Exception e) { return 0L; }
    }

    /** 登录会员的微信小程序 openid：msgSecCheck v2 必填，传空会被微信判 40003 并 fail-close 拦下发布。 */
    private String openidOf(Long memberId) {
        if (memberId == null || memberId <= 0) return null;
        UmsMember m = umsMemberService.selectUmsMemberById(memberId);
        return m == null ? null : m.getWxOpenId();
    }

    private int maxCityNodes(Long memberId) {
        UmsMember m = umsMemberService.selectUmsMemberById(memberId);
        UserPermission perm = (m != null) ? roleService.getPermission(m) : null;
        String role = m != null && m.getRole() != null ? m.getRole() : "merchant";
        return merchantCommerceService.entitlement(memberId, role,
                com.chengyinhub.business.service.impl.MerchantCommerceService.ENTITLEMENT_CITY_NODE, 0).getLimit();
    }

    /** 已解析商家主体 + 有 can_open_as_node 权限;失败把提示写入 err[0] 并返回 null。 */
    private MmsMerchant requireMerchantNodePerm(MerchantAccessContext access, String[] err) {
        MmsMerchant merchant = access == null ? null
                : mmsMerchantService.selectMmsMerchantById(access.getMerchantId());
        if (merchant == null) { err[0] = "请先完成商家入驻"; return null; }
        if (!access.getMerchantId().equals(merchant.getId())
                || !access.getOwnerMemberId().equals(merchant.getMemberId())) {
            err[0] = "商家身份无效或权限不足";
            return null;
        }
        // ★ 只查「有没有档案」不够:建了档但还没过审(status=0)、或审核被驳回(2)、
        //   或账号已禁用(accountStatus=2)的商家,照样能提交据点投放申请 ——
        //   平台等于给一个还没认过的主体开了漫游地图上的位置。口径与商家公开主页一致。
        if (merchant.getStatus() == null || merchant.getStatus() != 1L) {
            err[0] = "商家入驻还没通过审核,通过后才能报名成为节点";
            return null;
        }
        if (!Long.valueOf(1L).equals(merchant.getAccountStatus())
                || !Integer.valueOf(0).equals(merchant.getDelFlag())) {
            err[0] = "商家账号已停用,不能报名成为节点";
            return null;
        }
        UmsMember m = umsMemberService.selectUmsMemberById(access.getOwnerMemberId());
        UserPermission perm = (m != null) ? roleService.getPermission(m) : null;
        if (perm == null || !Boolean.TRUE.equals(perm.getCanOpenAsNode())) {
            err[0] = "当前身份不可开放为城市节点";
            return null;
        }
        return merchant;
    }

    private static boolean blank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /**
     * 该验证方式跑得起来吗?跑不起来就别让它落库。
     *
     * 判据是「玩家侧 {@code ApiCityNodeController#complete} 拿这份配置能不能判出结果」:
     *   vm=1 比 questionAnswer,缺了就没有可比的期望值;
     *   vm=3 比 correctAnswer,且正确项得真有内容 —— 缺了后端拿 null 去比,玩家怎么答都错;
     *   vm=2/4/5 不需要答案(分别靠照片、张贴码、GPS 判)。
     * @return 错误文案;通过返回 null
     */
    private String validationMethodError(CmsMemberTemplate t) {
        Integer vm = t.getValidationMethod();
        if (vm == null) return null;                 // 不传按老默认走,不在这道闸上收紧
        if (vm == 1 && blank(t.getQuestionAnswer())) {
            return "文字暗号要填答案";
        }
        if (vm == 3) {
            if (blank(t.getQuestionName())) return "选项问答要填题目";
            String[] opts = { t.getQuestionA(), t.getQuestionB(), t.getQuestionC(), t.getQuestionD() };
            int filled = 0;
            for (String o : opts) if (!blank(o)) filled++;
            if (filled < 2) return "选项问答至少要两个选项";
            String key = t.getCorrectAnswer() == null ? "" : t.getCorrectAnswer().trim().toUpperCase();
            int idx = "ABCD".indexOf(key);
            // 正确项必须是 A-D 之一,且那一项真有内容 —— 指向空选项等于没答案
            if (key.length() != 1 || idx < 0 || blank(opts[idx])) {
                return "请把正确答案指到一个填了内容的选项上";
            }
        }
        if (vm == ValidationMethod.PREFERENCE_GROUP) {
            try {
                new com.chengyinhub.business.service.support.PreferenceResultEvaluator().validate(t.getPreferenceJson());
            } catch (IllegalArgumentException e) {
                return e.getMessage();
            }
        }
        return null;
    }

    /** 据点模板图片(imgUrl)异步内容安全送检(BIZ_TYPE_NODE_TEMPLATE,违规经回调告警人工下架) */
    private void submitTemplateMedia(Long tplId, String imgUrl, String openid) {
        if (tplId == null || imgUrl == null || imgUrl.trim().isEmpty()) return;
        try { wxSec.submitMediaCheck(IWxContentSecurityService.BIZ_TYPE_NODE_TEMPLATE, tplId, imgUrl, openid); } catch (Exception ignore) {}
    }

    @Operation(summary = "提交/更新据点互动模板(免人工审 + msgSecCheck)")
    @PostMapping("/template/submit")
    public AjaxResult templateSubmit(@RequestBody CmsMemberTemplate body) {
        Long principalMemberId = uid();
        if (principalMemberId <= 0) return error("请先登录");
        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        String[] err = new String[1];
        MmsMerchant merchant = requireMerchantNodePerm(access, err);
        if (merchant == null) return error(err[0]);
        Long ownerMemberId = access.getOwnerMemberId();
        if (body == null || body.getTitle() == null || body.getTitle().trim().isEmpty()) return error("请填写标题");
        // 按验证方式校验必填项。小程序表单也拦一道,但那只是体验 ——
        // 闸放在这里才算数:配不全就落库的话,玩家侧只会表现成「怎么答都不对」,
        // 商家还看不出哪里错了(vm=3 缺 correctAnswer 正是 2026-08-06 修的那个 bug)。
        String vmErr = validationMethodError(body);
        if (vmErr != null) return error(vmErr);
        try {
            templatePublishService.normalizeAndValidateSensorFields(body);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        // 选项媒体是第二条写入路径:本入口收整实体后直接 insert/update,不经模板发布链路,
        // 所以 QuestionOptionMedia.sanitize 必须在这里也过一道 —— 只在发布链路上设闸,
        // 商家节点这条路会把畸形 JSON 直接写进 varchar(2000),读出端再静默丢。
        // ⚠️ 抛异常会变成 500,本入口的口径是 error(msg),按口径回话。
        try {
            body.setQuestionOptionMediaJson(
                    QuestionOptionMedia.sanitize(body.getQuestionOptionMediaJson()));
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }
        try {
            couponRewardAuthorizationService.assertOwnedRewardCoupon(body.getCouponId(), ownerMemberId);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        // 内容安全(免人工审的替代闸;未启用则放行)
        String secText = IWxContentSecurityService.joinText(body.getTitle(), body.getDescription(),
                body.getQuestionName(), body.getQuestionAnswer(), body.getFeedbackText(), body.getPreferenceJson(),
                body.getRuleInstructions());
        String openid = openidOf(principalMemberId);
        String secMsg = wxSec.checkText(secText, openid);
        if (secMsg != null) return error(secMsg);
        // R12:块配置叙事段(enter/outcome/deliver/... )同样免人工审直发,必须一并送检;命中提示带字段名。
        // F-L5C-02/03:商家节点模板自动通过、可被简易版主题直接引用,选项/提示/故事/勋章等字段同样给玩家看。
        java.util.List<JourneyReviewTexts.Entry> templateTexts = JourneyReviewTexts.collectTemplateFields(body);
        templateTexts.addAll(JourneyReviewTexts.collectBlocks(body.getAdvancedConfigJson()));
        String narrativeSecMsg = JourneyReviewTexts.check(wxSec, openid, templateTexts);
        if (narrativeSecMsg != null) return error(narrativeSecMsg);

        try {
            Long tplId = templatePublishService.saveMerchantNodeTemplate(body, ownerMemberId, getAppUserName());
            submitTemplateMedia(tplId, body.getImgUrl(), openid);
            return success(tplId);
        } catch (ServiceException | IllegalArgumentException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "提交据点投放申请(唯一写入路径:roam_poi pending)")
    @PostMapping("/save")
    public AjaxResult save(Long poiId, Long templateId, Double lat, Double lng, Integer radius,
                           String name, String address, String tags, String coverImg, String cityCode) {
        Long principalMemberId = uid();
        if (principalMemberId <= 0) return error("请先登录");
        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        String[] err = new String[1];
        MmsMerchant merchant = requireMerchantNodePerm(access, err);
        if (merchant == null) return error(err[0]);
        Long ownerMemberId = access.getOwnerMemberId();

        if (templateId == null || templateId <= 0) return error("请先选择互动模板");
        CmsMemberTemplate tpl = templateService.selectCmsMemberTemplateById(templateId);
        if (tpl == null || OwnershipUtils.notOwner(tpl.getMemberId(), ownerMemberId)) {
            return error("模板不存在或无权使用");
        }
        try {
            couponRewardAuthorizationService.assertOwnedRewardCoupon(tpl.getCouponId(), ownerMemberId);
        } catch (IllegalArgumentException e) {
            return error(e.getMessage());
        }

        if (poiId != null && poiId > 0) return error("已上线据点不能复用投放申请入口");
        // 没传坐标就回落到商家档案里的店址 —— 报名成为节点只要求「配个玩法」,
        // 店址平台本来就有。档案也没有坐标时才真的没法定位,那时候才报错。
        if (lat == null || lng == null) {
            Double shopLat = merchant.getLocationLat();
            Double shopLng = merchant.getLocationLng();
            if (shopLat == null || shopLng == null) return error("你的店铺资料里还没有坐标,请先在地图上标注店址");
            lat = shopLat;
            lng = shopLng;
            if (address == null || address.trim().isEmpty()) address = merchant.getAddress();
        }
        int max = maxCityNodes(ownerMemberId);
        int used = roamMapper.countActiveNodesByMerchant(access.getMerchantId());
        if (used >= max) return error("在架据点已达上限(" + max + "),请先下线其他据点");

        RoamPoiApplicationRequest request = new RoamPoiApplicationRequest();
        request.setName((name != null && !name.trim().isEmpty()) ? name.trim() : merchant.getName());
        request.setAddress(address);
        request.setTemplateId(templateId);
        request.setCouponId(tpl.getCouponId());
        request.setTags(tags);
        request.setCoverImg(coverImg);
        request.setDescription(tpl.getDescription());
        if (lat != null) request.setLatitude(BigDecimal.valueOf(lat));
        if (lng != null) request.setLongitude(BigDecimal.valueOf(lng));
        request.setRadiusM((radius != null && radius > 0) ? radius : 80);
        request.setCityCode(cityCode);
        try {
            return success(roamPoiApplicationService.submitPlacement(ownerMemberId, request));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "我的据点列表(含配额 used/max)")
    @PostMapping("/list")
    public AjaxResult list() {
        Long principalMemberId = uid();
        if (principalMemberId <= 0) return error("请先登录");
        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        List<CityNodeVO> nodes = roamMapper.selectMyNodes(access.getMerchantId());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("nodes", nodes);
        data.put("applications", roamPoiApplicationService.listMine(access.getOwnerMemberId()));
        data.put("used", roamMapper.countActiveNodesByMerchant(access.getMerchantId()));
        data.put("max", maxCityNodes(access.getOwnerMemberId()));
        return success(data);
    }

    @Operation(summary = "可认领的 roam 节点")
    @PostMapping("/claimable")
    public AjaxResult claimable(String keyword) {
        Long principalMemberId = uid();
        if (principalMemberId <= 0) return error("请先登录");
        try {
            merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        return success(roamPoiApplicationService.listClaimable(keyword));
    }

    @Operation(summary = "提交 roam 节点认领申请")
    @PostMapping("/claim")
    public AjaxResult claim(Long poiId) {
        Long principalMemberId = uid();
        if (principalMemberId <= 0) return error("请先登录");
        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        String[] err = new String[1];
        if (requireMerchantNodePerm(access, err) == null) return error(err[0]);
        try {
            return success(roamPoiApplicationService.submitClaim(access.getOwnerMemberId(), poiId));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    @Operation(summary = "撤回待审的节点认领申请(申请人;被平台接手审核后不可撤回)")
    @PostMapping("/claim/cancel")
    public AjaxResult cancelClaim(Long poiId) {
        Long principalMemberId = uid();
        if (principalMemberId <= 0) return error("请先登录");
        if (poiId == null || poiId <= 0) return error("参数不合规");
        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        try {
            return roamPoiApplicationService.cancelClaim(access.getOwnerMemberId(), poiId)
                    ? success("已撤回认领申请") : error("该认领申请已不可撤回，请刷新后重试");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }

    /**
     * 到店打卡码(vm=4 用)。商家打印张贴,玩家扫。
     *
     * 跟核销动态码方向相反:那个是玩家掏出来给商家扫、靠 TTL 防转赠;
     * 这个长期有效,所以**不能**下发给玩家 —— 只有本店店主取得到。
     *
     * ⚠️ 两条已知限制,别当它是强证据:
     * ① **拿到码是能远程打卡的**。`complete` 那层半径校验用的 lat/lng 是客户端传的,可伪造;
     *    码被拍照转发 + 伪造坐标即可完成。真正的钱闸在后面 —— 完成只写 status=0 待核销,
     *    券仍要商家扫玩家的动态码才发。
     * ② **没有单点作废**。码不存库,唯一作废手段是换 `verify.dyncode-secret`,
     *    而它默认复用 `token.secret`(JWT 密钥)—— 换一次全体登出、所有在途动态码失效。
     *    要支持「重印这一家的码」,得给它单独配密钥或在 payload 里加一个每节点版本号。
     */
    @Operation(summary = "取本店到店打卡码(商家打印张贴,玩家扫)")
    @PostMapping("/poster-code")
    public AjaxResult posterCode(Long poiId) {
        Long principalMemberId = uid();
        if (principalMemberId <= 0) return error("请先登录");
        if (poiId == null || poiId <= 0) return error("参数不合规");
        MerchantAccessContext access;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.VERIFY);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
        String[] err = new String[1];
        if (requireMerchantNodePerm(access, err) == null) return error(err[0]);
        CityNodeVO node = roamMapper.selectMerchantNodeDetail(poiId);
        // 归属闸:只能取自己店的码。否则任何商家都能把别家的码印出来贴自己店里截流。
        if (node == null || node.getMerchantId() == null || !node.getMerchantId().equals(access.getMerchantId())) {
            return error("据点不存在或无权操作");
        }
        String code = VerifyDynCodeUtil.issuePoiPoster(poiId, dynSecret);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("qrcodeUrl", qrcodeService.generateQrcode(code, 300, 300));
        data.put("nodeName", node.getName());
        return success(data);
    }

    @Operation(summary = "上/下线自有据点(status 0下线/1上架;上架再校验配额)")
    @PostMapping("/offline")
    public AjaxResult offline(Long poiId, Integer status) {
        Long principalMemberId = uid();
        if (principalMemberId <= 0) return error("请先登录");
        if (poiId == null || poiId <= 0) return error("参数不合规");
        int st = (status != null && status == 1) ? 1 : 0;   // 默认下线;传 1 重新上架
        MerchantAccessContext access = null;
        Long ownerMemberId = principalMemberId;
        try {
            access = merchantAccessService.require(principalMemberId, MerchantPermission.PROJECT_MANAGE);
            ownerMemberId = access.getOwnerMemberId();
        } catch (ServiceException e) {
            // #826 的应急下线合同：停用的 Owner 仍能把自己的据点下线；
            // 但重新上架必须通过当前 active 主体与 PROJECT_MANAGE 权限。
            if (st == 1) return new AjaxResult(e.getCode(), e.getMessage());
        }
        if (st == 1) {
            String[] err = new String[1];
            if (requireMerchantNodePerm(access, err) == null) return error(err[0]);
        }
        try {
            return roamPoiApplicationService.setOwnedNodeStatus(ownerMemberId, poiId, st)
                    ? success(st == 1 ? "已上架" : "已下线") : error("据点不存在或无权操作");
        } catch (IllegalArgumentException | IllegalStateException e) {
            return error(e.getMessage());
        }
    }
}
