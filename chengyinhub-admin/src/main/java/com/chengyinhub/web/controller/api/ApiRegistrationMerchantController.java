package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.RegistrationMerchantDTO;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.ViewRegistrationMerchantMapper;
import com.chengyinhub.business.service.*;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.page.TableDataInfo;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.common.constant.ProductType;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.validation.annotation.Validated;

import java.util.Date;
import java.util.List;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 商家主题报名
 *
 * @author webxh
 * @date 2025-09-14
 */
@RestController
@Tag(name = "活动/主题报名")
@RequestMapping("/api/registration/merchant")
public class ApiRegistrationMerchantController extends BaseController {

    @Autowired
    private com.chengyinhub.business.service.ICouponRewardAuthorizationService couponRewardAuthorizationService;


    @Autowired
    private ICmsRegistrationMerchantService cmsRegistrationMerchantService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private ICmsTopicService cmsTopicService;

    @Autowired
    private ICmsTopicService topicService;

    @Autowired
    private ICmsTopicNodeService cmsTopicNodeService;

    @Autowired
    private IViewRegistrationMerchantService viewRegistrationMerchantService;

    @Autowired
    private IMerchantRegistrationDetailReadService merchantRegistrationDetailReadService;

    @Autowired
    private IMerchantRegistrationEditService merchantRegistrationEditService;

    @Autowired
    private IMerchantAccessService merchantAccessService;


    /**
     * 取消报名
     */
    @Operation(summary = "取消报名")
    @PostMapping("/cancel")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult cancelRegistration(String id) {
        try {
            // 获取当前登录用户ID
            Long memberId = 0L;
            try {
                memberId = getAppUserId();
            } catch (Exception e) {

            }
            if (memberId <= 0) {
                return error("请先登录");
            }
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);

            String userName = getAppUserName();

            Long registrationId = Convert.toLong(id, 0L);

            // [C5] 归属校验 + 已中标不得取消,一并下沉到 service —— 闸留在 controller 就只有这一个入口
            // 挡得住,且 getAppUserId() 不可 mock 导致它没法被测试真的调进去。
            boolean result = cmsRegistrationMerchantService.cancelByMerchant(
                    registrationId, access.getOwnerMemberId(), userName);

            return result ? AjaxResult.success("报名已取消") : AjaxResult.error("取消失败");
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Operation(summary = "我的报名")
    @PostMapping("/list")
    @Parameters({
            @Parameter(name = "status", description = "状态    0全部 1进行中 2审核中 3已驳回 4已结束"),
    })
    public AjaxResult myRegistrationList(String status) {
        try {
            Long principalMemberId;
            try { principalMemberId = getAppUserId(); }
            catch (Exception e) { return error("请先登录"); }
            if (principalMemberId == null || principalMemberId <= 0) return error("请先登录");
            MerchantAccessContext access = merchantAccessService.require(
                    principalMemberId, MerchantPermission.PROJECT_MANAGE);

            ViewRegistrationMerchant registration = new ViewRegistrationMerchant();
            registration.setMemberId(access.getOwnerMemberId());
            registration.setSearchStatus(Convert.toLong(status, 0L));
            List<ViewRegistrationMerchant> list =
                    viewRegistrationMerchantService.selectViewRegistrationMerchantList(registration);
            TableDataInfo data = getDataTable(list);
            return AjaxResult.success(data);
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        }
    }

    /**
     * 查询报名详情
     */
    /**
     * 改自己那条报名。
     *
     * 原来只有 create/cancel:被驳回的商家想补一张现场图,只能取消重报,记录丢失、重新排队。
     * 闸(归属 / 主题未开始 / 状态∈{审核中,已驳回})与白名单一并下沉到 service ——
     * getAppUserId() 在 controller 里不可 mock,闸留在这层就测不到。
     */
    @Operation(summary = "修改报名(仅审核中/已驳回且主题未开始)")
    @PostMapping("/update")
    public AjaxResult updateRegistration(@RequestBody RegistrationMerchantDTO param) {
        try {
            Long memberId = 0L;
            try {
                memberId = getAppUserId();
            } catch (Exception e) {
            }
            if (memberId <= 0) {
                return error("请先登录");
            }
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);
            boolean ok = merchantRegistrationEditService.updateByMerchant(
                    param == null ? null : param.getId(), access.getOwnerMemberId(),
                    param, getAppUserName());
            return ok ? AjaxResult.success("已保存") : AjaxResult.error("保存失败");
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Operation(summary = "查询报名详情")
    @PostMapping("/info")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult getRegistrationDetail(@RequestParam("id") String id) {
        try {
            Long memberId = 0L;
            try {
                memberId = getAppUserId();
            } catch (Exception e) {
            }
            if (memberId <= 0) {
                return error("请先登录");
            }
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);

            Long registrationId = Convert.toLong(id, 0L);
            return AjaxResult.success(merchantRegistrationDetailReadService
                    .getRegistrationDetail(access.getOwnerMemberId(), registrationId));
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    @Operation(summary = "查询是否报名")
    @PostMapping("/select")
    @Parameters({
            @Parameter(name = "id", description = "报名id")
    })
    public AjaxResult select(String id) {
        try {
            // 获取当前登录用户ID
            Long memberId = 0L;
            try {
                memberId = getAppUserId();
            } catch (Exception e) {

            }
            if (memberId <= 0) {
                return error("请先登录");
            }
            MerchantAccessContext access = merchantAccessService.require(
                    memberId, MerchantPermission.PROJECT_MANAGE);

            CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
            cmsRegistrationMerchant.setMemberId(access.getOwnerMemberId());
            cmsRegistrationMerchant.setTopicId(Long.valueOf(id));
            List<CmsRegistrationMerchant> list = cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(cmsRegistrationMerchant);
            if (list.size() > 0) {
                return AjaxResult.error("您已报名此主题，请勿重复报名");
            } else {
                return AjaxResult.success("未报名");
            }
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }


    /**
     * 创建商家主题报名
     */
    @Operation(summary = "创建商家主题报名")
    @PostMapping("/create")
    public AjaxResult createRegistration(@Validated @RequestBody RegistrationMerchantDTO param) {
        try {
            // 获取当前登录用户ID
            Long member_id = 0L;
            try {
                member_id = getAppUserId();
            } catch (Exception e) {

            }
            if (member_id <= 0) {
                return error("请先登录");
            }
            MerchantAccessContext access = merchantAccessService.require(
                    member_id, MerchantPermission.PROJECT_MANAGE);
            member_id = access.getOwnerMemberId();

            UmsMember umsMember = umsMemberService.selectUmsMemberById(member_id);
            if (umsMember == null) {
                return error("用户不存在");
            }
            if (!com.chengyinhub.business.service.IRoleService.ROLE_MERCHANT.equals(umsMember.getRole())) {
                return error("商家才能报名");
            }

            //查询主题信息
            CmsTopic cmsTopic = cmsTopicService.selectCmsTopicById(param.getTopicId());
            if (cmsTopic == null) {
                return error("主题不存在");
            }
            if (cmsTopic.getStatus() != 1) {
                return error("主题审核中");
            }
            if (cmsTopic.getMerchantStatus() != 1) {
                return error("主题未上架，暂不支持报名");
            }
            // 不能承接自己发布的主题。另外三条承接路径(俱乐部合作池 CoopClubApplyServiceImpl、
            // 商家章节申请 MerchantChapterApplicationServiceImpl、发邀约 ApiCoopController#invite)
            // 都有这道闸,只有这条报名路径漏了 —— 不是"有意允许自接",是漏。
            // 自接的实害:审的人就是被审的人(候选池确认由主题 owner 操作),自审必过,
            // 等于绕过审核给自己中标 + 占掉一个站点名额。
            // ⚠️ member_id 为空(存量脏数据)不拦:判不出归属就别拿它当"是我"。
            if (OwnershipUtils.isOwner(cmsTopic.getMemberId(), member_id)) {
                return error("不能报名自己发布的主题");
            }
            // 自由探索的商家承接以章节为唯一标的：旧报名表没有 chapterId/category/capacity
            // 语义，继续放行会绕过申请审核与容量闸。
            if (Integer.valueOf(ProductType.FREE_EXPLORE).equals(cmsTopic.getProductType())) {
                return error("自由探索主题请按章节申请承接");
            }
            // 旧入口只保留给经典定向，且节点与模板均从服务端主题结构反查，不能信任客户端直传。
            if (!Integer.valueOf(ProductType.ORIENTEERING).equals(param.getMode())) {
                return error("经典定向主题必须选择节点报名");
            }
            Long nodeId = normalizeNodeId(param.getNodeId());
            if (nodeId == null) {
                return error("请选择主题节点");
            }
            CmsTopicNode topicNode = cmsTopicNodeService.selectCmsTopicNodeById(nodeId);
            if (topicNode == null || topicNode.getTopicId() == null
                    || !topicNode.getTopicId().equals(cmsTopic.getId())) {
                return error("节点不属于该主题");
            }

            Date now = new Date();
            if (cmsTopic.getMerchantSignUpEndDate() != null
                    && now.after(DateUtils.convertToEndOfDay(cmsTopic.getMerchantSignUpEndDate()))) {
                return error("主题报名已结束");
            }

            if (cmsTopic.getMerchantSignUpStartDate() != null
                    && now.before(DateUtils.convertToStartOfDay(cmsTopic.getMerchantSignUpStartDate()))) {
                return error("主题报名未开始");
            }

            // 查重：同一商家对同一主题的同一节点已有未取消报名时，禁止重复提交
            CmsRegistrationMerchant existQuery = new CmsRegistrationMerchant();
            existQuery.setMemberId(member_id);
            existQuery.setTopicId(param.getTopicId());
            existQuery.setNodeId(nodeId);
            existQuery.setDelFlag(0);
            if (cmsRegistrationMerchantService.selectCmsRegistrationMerchantList(existQuery).size() > 0) {
                return error("您已报名此节点，请勿重复报名");
            }

            // 【§3.6】报名 = 表达意向(我是谁 / 我在哪 / 我能提供什么),【不含条款】。
            // 条款随 type0 邀约走(发布者选中候选后带条款发出、商家接受即冻结成 coop_order)。
            // 为什么不在这儿收条款:报名那一刻,定价所需的信息还不存在 —— 商家不知道这主题能卖多少、
            // 有几家在争,逼他此刻报价 = 逼他瞎猜。这正是 sharing_rate 至今没人填、
            // 最后被结算兜底污染成 100 的根因:那个字段索取的信息,在它被索取的时点并不存在。
            CmsRegistrationMerchant cmsRegistrationMerchant = new CmsRegistrationMerchant();
            cmsRegistrationMerchant.setMemberId(member_id);
            cmsRegistrationMerchant.setTopicId(param.getTopicId());
            cmsRegistrationMerchant.setMode(ProductType.ORIENTEERING);
            cmsRegistrationMerchant.setNodeId(nodeId);
            cmsRegistrationMerchant.setAddress(param.getAddress());
            cmsRegistrationMerchant.setAddressName(param.getAddressName());
            cmsRegistrationMerchant.setLatitude(param.getLatitude());
            cmsRegistrationMerchant.setLongitude(param.getLongitude());
            cmsRegistrationMerchant.setPicUrl(param.getPicUrl());
            cmsRegistrationMerchant.setTemplateId(topicNode.getTemplateId());
            cmsRegistrationMerchant.setActivityDesc(param.getActivityDesc());
            cmsRegistrationMerchant.setLimitNum(param.getLimitNum());
            //cmsRegistrationMerchant.setEmail(param.getEmail());
            if (param.getStartDate() != null) {
                cmsRegistrationMerchant.setStartDate(param.getStartDate());
            }
            if (param.getEndDate() != null) {
                cmsRegistrationMerchant.setEndDate(param.getEndDate());
            }
            // 处理开始时间：设置为当天的 00:00:00
            //cmsRegistrationMerchant.setStartDate(DateUtils.convertToStartOfDay(param.getStartDate().toString()));

            // 处理结束时间：设置为当天的 23:59:59
            //cmsRegistrationMerchant.setEndDate(DateUtils.convertToEndOfDay(param.getEndDate().toString()));

            if (StringUtils.isNotBlank(param.getCooperateDate())) {
                cmsRegistrationMerchant.setCooperateDate(param.getCooperateDate());
            }

            //cmsRegistrationMerchant.setLimitNum(0L);

            // 承接方现场层:规则怎么讲、答错说什么、到店给什么券 —— 这三样归商家,
            // 玩家侧读取见 INodeMerchantOverrideService(商家没配才回落主办方模板)。
            cmsRegistrationMerchant.setRuleInstructions(param.getRuleInstructions());
            cmsRegistrationMerchant.setFeedbackText(param.getFeedbackText());
            // 券会真发到玩家券包,必须先确认这张券是他自己的 ——
            // 模板提交与据点那两条路径早有这道闸,只有报名这条漏了(接通前不发券所以没暴露)。
            if (param.getCouponId() != null && param.getCouponId() > 0) {
                try {
                    couponRewardAuthorizationService.assertOwnedRewardCoupon(param.getCouponId(), member_id);
                } catch (IllegalArgumentException e) {
                    return error(e.getMessage());
                }
                cmsRegistrationMerchant.setCouponId(param.getCouponId());
            }
            // ⚠️ 玩法本身(题目/答案/选项/验证方式)归主办方,取节点模板 ——
            // 客户端即使传了也一律不落库。2026-08-06 拍板:商家改现场,不改玩法。

            cmsRegistrationMerchant.setStatus(0L);
            cmsRegistrationMerchant.setAuditStatus(0L); // 0=待调配（进商家池后等后台择优选定）
            cmsRegistrationMerchant.setDelFlag(0);

            cmsRegistrationMerchantService.insertCmsRegistrationMerchant(cmsRegistrationMerchant);

            // 调配审核改造：报名只建报名记录，不再立即生成活动。
            // 后台在「调配审核」页按竞争位 (topic, node) 择优选定中标商家时，才生成并发布该商家的活动
            // （见 CmsRegistrationMerchantController#select）。无节点（自由探索）不构成竞争位、可多家承接：
            // 新数据经 normalizeNodeId 落库为 NULL，存量仍是哨兵 0，下游 hasNodeSlot 两者都认。
            return success(cmsRegistrationMerchant.getId());

        } catch (ServiceException e) {
            return new AjaxResult(e.getCode(), e.getMessage());
        } catch (Exception e) {
            return AjaxResult.error(e.getMessage());
        }
    }

    /**
     * nodeId 入口归一：前端哨兵 0 → NULL。
     *
     * 「没有节点」在库里就该是 NULL；哨兵 0 靠【每个下游各自认得它】来防守，多一个下游忘了判 0，
     * 就会把 0 当成真实节点 ID 去 join。归一后新数据不再产生哨兵。
     *
     * ⚠️ 存量（生产库 6 行，全 mode=1）仍是 0，故下游 hasNodeSlot 的 `!= 0` 判断与 win_key 的
     *    `node_id IS NOT NULL AND node_id <> 0` 不可拆——它们是存量的保险。
     */
    private static Long normalizeNodeId(Long nodeId) {
        return (nodeId == null || nodeId == 0L) ? null : nodeId;
    }

}
