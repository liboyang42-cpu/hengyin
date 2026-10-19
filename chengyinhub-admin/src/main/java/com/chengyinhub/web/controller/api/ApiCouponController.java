package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantOperationScope;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.UserPermission;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.domain.dto.CouponPublisDTO;
import com.chengyinhub.business.service.IRoleService;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import com.chengyinhub.business.service.ISmsCouponService;
import com.chengyinhub.business.service.ISettlementEntryService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.annotation.Log;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.core.text.Convert;
import com.chengyinhub.common.enums.BusinessType;
import com.chengyinhub.common.utils.DateUtils;
import com.chengyinhub.common.utils.StringUtils;
import com.chengyinhub.system.domain.SysCategory;
import com.chengyinhub.system.domain.ViewCouponHistory;
import com.chengyinhub.system.service.ISysCategoryService;
import com.chengyinhub.system.service.IViewCouponHistoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.Parameters;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import com.chengyinhub.business.service.impl.CouponCommandService;
import org.springframework.validation.annotation.Validated;

import java.util.Date;
import java.util.List;

import com.chengyinhub.common.utils.OwnershipUtils;
/**
 * 主题、活动等分类
 *
 * @author webxh
 * @date 2025-08-31
 */
@RestController
@Tag(name = "优惠券")
@RequestMapping("/api/coupon")
public class ApiCouponController extends BaseController {

    @Autowired
    private ISmsCouponService smsCouponService;

    @Autowired
    private CouponCommandService couponCommandService;

    @Autowired
    private com.chengyinhub.business.service.IQuotaService quotaService;

    @Autowired
    private ISmsCouponHistoryService smsCouponHistoryService;
    @Autowired
    private ISettlementEntryService settlementEntryService;
    @Autowired
    private IViewCouponHistoryService viewCouponHistoryService;

    @Autowired
    private IMerchantAccessService merchantAccessService;

    @Autowired
    private IUmsMemberService umsMemberService;

    @Autowired
    private IRoleService roleService;

    @Autowired
    private com.chengyinhub.business.service.impl.QrcodeServiceImpl qrcodeService;

    @Autowired
    private com.chengyinhub.common.core.redis.RedisCache redisCache;

    @PostMapping("/mypublishlist")
    @Operation(summary = "我发布的优惠券列表")
    @Parameters({
            @Parameter(name = "keyword", description = "优惠券名称"),
            @Parameter(name = "is_select", description = "发布模板的时候选择优惠券传1")
    })
    public AjaxResult myPublishCouponList(String keyword, String is_select, String scope) {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }
        if (member_id == 0L) {
            return error("请登录");
        }
        CouponOperationContext operation = resolveCouponOperation(
                member_id, MerchantPermission.COUPON_MANAGE, scope);
        Long ownerMemberId = operation.ownerMemberId;

        SmsCoupon smsCoupon = new SmsCoupon();
        smsCoupon.setMemberId(ownerMemberId);
        if (operation.merchantId != null) {
            smsCoupon.setScopeType(1);
            smsCoupon.setMerchantId(operation.merchantId);
        }
        //先更新优惠券过期状态
        smsCouponService.updateExpiredCouponsByMemberId(ownerMemberId);

        int isSelect = Convert.toInt(is_select, 0);
        if (isSelect == 1) {
            //状态：0->未开始；1->进行中；2->已结束；3->手动失效   用户发布优惠券状态就直接显示进行中
            smsCoupon.setStatus(1);
            smsCoupon.getParams().put("issuableOnly", true);
        }
        smsCoupon.setDelFlag(0);

        if (StringUtils.isNotEmpty(keyword)) {
            smsCoupon.setName(keyword);
        }

        List<SmsCoupon> sysCategoryList = smsCouponService.selectSmsCouponList(smsCoupon);

        return success(sysCategoryList);
    }

    public AjaxResult myPublishCouponList(String keyword, String is_select) {
        return myPublishCouponList(keyword, is_select, null);
    }

    @Operation(summary = "发布优惠券")
    @Log(title = "发布优惠券", businessType = BusinessType.INSERT)
    @PostMapping("/publish")
    public AjaxResult publish(@Validated @RequestBody CouponPublisDTO request,
                             @RequestHeader(value = "X-Coupon-Command-Id", required = false) String commandId,
                             @RequestHeader(value = "X-Coupon-Merchant-Id", required = false) String expectedMerchantId) {
        if (commandId != null || expectedMerchantId != null) {
            return couponCommandService.publish(commandActor(), commandId, commandMerchant(expectedMerchantId), request);
        }
        return publish(request);
    }

    public AjaxResult publish(CouponPublisDTO request) {
        // 添加调试日志
        logger.info("接收到请求参数: {}", request);
        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }
        if (member_id == 0L) {
            return error("请先登录");
        }
        CouponOperationContext operation = resolveCouponOperation(
                member_id, MerchantPermission.COUPON_MANAGE, request.getScope());
        Long ownerMemberId = operation.ownerMemberId;
        // RBAC R3：按角色配额校验优惠券「同时存在」数量
        String quotaErr = quotaService.checkCreate(ownerMemberId, com.chengyinhub.business.service.IQuotaService.TYPE_COUPON);
        if (quotaErr != null) {
            return error(quotaErr);
        }
        // M0-4 发券防重：Redis SETNX 业务锁(商家+类型+分钟粒度)，连点/重放同一分钟内只放行 1 次；
        // DB 唯一约束(migration_coupon_dedup.sql)为兜底。
        long minute = System.currentTimeMillis() / 60000L;
        String lockKey = "coupon:publish:" + ownerMemberId + ":" + request.getCouponType() + ":" + minute;
        Boolean locked = redisCache.setIfAbsent(lockKey, "1", 60, java.util.concurrent.TimeUnit.SECONDS);
        if (Boolean.FALSE.equals(locked)) {
            return error("发券太频繁，请稍后再试");
        }
        SmsCoupon smsCoupon = new SmsCoupon();
        smsCoupon.setMemberId(ownerMemberId);
        if (operation.merchantId != null) {
            smsCoupon.setScopeType(1);
            smsCoupon.setMerchantId(operation.merchantId);
        }
        smsCoupon.setStatus(1);
        smsCoupon.setDelFlag(0);
        smsCoupon.setName(request.getName());
        smsCoupon.setCouponType(request.getCouponType());
        smsCoupon.setPlatform(0);

        // 处理开始时间：设置为当天的 00:00:00
        smsCoupon.setStartTime(request.getStartTime());

        // 处理结束时间：设置为当天的 23:59:59
        smsCoupon.setEndTime(request.getEndTime());

        smsCoupon.setPublishCount(request.getPublishCount());
        smsCoupon.setUseCount(0L);
        smsCoupon.setReceiveCount(0L);

        smsCoupon.setCouponType(request.getCouponType());
        smsCoupon.setDescription(request.getDescription());

        // 竞态安全:锁会员 → 持锁复查优惠券配额 → 插入,同事务原子,防并发绕过 maxCoupons。
        //(上方 checkCreate 为提前快速拦截 + 保持 Redis 分钟锁顺序;此处 guard 做权威原子校验。)
        // 超限时 guard 抛 ServiceException,由全局异常处理转 error 文案。
        final Long fMemberId = ownerMemberId;
        Integer rows = quotaService.runWithCreateGuard(fMemberId,
                com.chengyinhub.business.service.IQuotaService.TYPE_COUPON,
                () -> smsCouponService.insertSmsCoupon(smsCoupon));
        // C-31:回传新券 id,发布流里「新建即选中」要靠它挂到节点奖励上(缺 id 会静默挂成 0)。
        return (rows != null && rows > 0)
                ? AjaxResult.success("发布成功", java.util.Collections.singletonMap("id", smsCoupon.getId()))
                : error("发布失败");

    }

    @Operation(summary = "停发自己的优惠券(商家):停止新增发放/领取,已领的照常可用可核销")
    @Log(title = "停发优惠券", businessType = BusinessType.UPDATE)
    @PostMapping("/stop")
    @Parameters({@Parameter(name = "couponId", description = "优惠券ID")})
    public AjaxResult stopIssuing(@RequestParam(value = "couponId", required = false) Long couponId,
                                 @RequestParam(value = "scope", required = false) String scope,
                                 @RequestHeader(value = "X-Coupon-Command-Id", required = false) String commandId,
                                 @RequestHeader(value = "X-Coupon-Merchant-Id", required = false) String expectedMerchantId) {
        if (commandId != null || expectedMerchantId != null) {
            return couponCommandService.stop(commandActor(), commandId, commandMerchant(expectedMerchantId), couponId, scope);
        }
        return stopIssuing(couponId, scope);
    }

    @PostMapping("/command-receipt")
    @Operation(summary = "查询本人商家优惠券命令结果；未找到不表示未执行")
    public AjaxResult commandReceipt(@RequestParam(value = "requestId", required = false) String requestId,
                                     @RequestParam(value = "merchantId", required = false) Long merchantId,
                                     @RequestParam(value = "scope", required = false) String scope) {
        return couponCommandService.read(commandActor(), requestId, merchantId, scope);
    }

    private Long commandMerchant(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return null;
        try { return Long.valueOf(value); } catch (NumberFormatException exception) { return null; }
    }

    private Long commandActor() {
        try { return getAppUserId(); } catch (Exception exception) { return null; }
    }

    public AjaxResult stopIssuing(Long couponId, String scope) {
        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
        }
        if (member_id == null || member_id == 0L) {
            return error("请先登录");
        }
        if (couponId == null || couponId <= 0) {
            return error("参数缺失");
        }
        CouponOperationContext operation = resolveCouponOperation(
                member_id, MerchantPermission.COUPON_MANAGE, scope);
        SmsCoupon coupon = smsCouponService.selectSmsCouponById(couponId);
        if (coupon == null || (coupon.getDelFlag() != null && coupon.getDelFlag() != 0)) {
            return error("优惠券不存在");
        }
        // 归属校验:只能停发自己的券。商家身份必须落在同一 merchant_id;俱乐部/个人按 owner member_id。
        if (OwnershipUtils.notOwner(coupon.getMemberId(), operation.ownerMemberId)) {
            return error("无权停发该优惠券");
        }
        if (operation.merchantId != null) {
            if (!Integer.valueOf(1).equals(coupon.getScopeType())
                    || !operation.merchantId.equals(coupon.getMerchantId())) {
                return error("无权停发该优惠券");
            }
        } else if (Integer.valueOf(1).equals(coupon.getScopeType())) {
            return error("无权停发该优惠券");
        }
        if (Integer.valueOf(3).equals(coupon.getStatus())) {
            return error("该优惠券已被平台手动失效");
        }
        if (Integer.valueOf(4).equals(coupon.getStatus())) {
            return error("该优惠券已停发,无需重复操作");
        }
        if (!Integer.valueOf(0).equals(coupon.getStatus()) && !Integer.valueOf(1).equals(coupon.getStatus())) {
            return error("已结束的优惠券无法停发");
        }
        // CAS(WHERE status in (0,1)):并发下状态已被改走时拿 0 行,回读给出准确原因。
        if (smsCouponService.stopIssuingCoupon(couponId, operation.ownerMemberId) != 1) {
            SmsCoupon current = smsCouponService.selectSmsCouponById(couponId);
            return error(current != null && Integer.valueOf(4).equals(current.getStatus())
                    ? "该优惠券已停发,无需重复操作" : "停发失败,请刷新后重试");
        }
        logger.info("商家停发优惠券 couponId={} ownerMemberId={} actor={}",
                couponId, operation.ownerMemberId, member_id);
        return success("已停发");
    }

    @PostMapping("/myrecvlist")
    @Operation(summary = "我领取优惠券列表")
    @Parameters({
            @Parameter(name = "keyword", description = "优惠券名称")
    })
    public AjaxResult myPublishCouponList(String keyword) {

        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {

        }
        if (member_id == 0L) {
            return error("请登录");
        }

        //先更新优惠券过期状态
        SmsCouponHistory smsCouponHistory = new SmsCouponHistory();
        smsCouponHistory.setMemberId(member_id);
        smsCouponHistoryService.updateExpiredCouponsByMemberId(member_id);


        ViewCouponHistory viewCouponHistory = new ViewCouponHistory();
        viewCouponHistory.setMemberId(member_id);

        viewCouponHistory.setDelFlag(0L);

        if (StringUtils.isNotEmpty(keyword)) {
            viewCouponHistory.setCouponName(keyword);
        }

        List<ViewCouponHistory> sysCategoryList = viewCouponHistoryService.selectViewCouponHistoryList(viewCouponHistory);
        projectManuallyInvalidated(sysCategoryList);

        return success(sysCategoryList);
    }

    /**
     * 钱包读侧展示投影:平台手动失效(status=3)且尚未使用的券,回包按 useStatus=3「已失效」呈现。
     * 只改展示,不改 sms_coupon_history 的既成事实,也不影响核销(核销另有服务端闸门)。
     * 商家停发(status=4)的券已领的照常可用,不做投影。
     */
    private void projectManuallyInvalidated(List<ViewCouponHistory> rows) {
        if (rows == null || rows.isEmpty()) return;
        java.util.Set<Long> couponIds = new java.util.HashSet<>();
        for (ViewCouponHistory row : rows) {
            if (row != null && row.getUseStatus() != null && row.getUseStatus() == 0L
                    && row.getCouponId() != null) {
                couponIds.add(row.getCouponId());
            }
        }
        if (couponIds.isEmpty()) return;
        java.util.Map<Long, Integer> statusById =
                smsCouponService.selectCouponStatuses(new java.util.ArrayList<>(couponIds));
        for (ViewCouponHistory row : rows) {
            if (row != null && row.getUseStatus() != null && row.getUseStatus() == 0L
                    && Integer.valueOf(3).equals(statusById.get(row.getCouponId()))) {
                row.setUseStatus(3L);
            }
        }
    }

    @Operation(summary = "优惠券动态二维码token(M0-2):60s时效,前端刷新")
    @PostMapping("/qr-token")
    @Parameters({@Parameter(name = "couponHistoryId", description = "领券历史ID")})
    public AjaxResult qrToken(Long couponHistoryId) {
        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
        }
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        if (couponHistoryId == null) {
            return error("参数缺失");
        }
        SmsCouponHistory h = smsCouponHistoryService.selectSmsCouponHistoryById(couponHistoryId);
        if (h == null || (h.getDelFlag() != null && h.getDelFlag() != 0)) {
            return AjaxResult.error(410, "优惠券已撤销或不存在");
        }
        // 归属校验:只能出示自己的券
        if (OwnershipUtils.notOwner(h.getMemberId(), member_id)) {
            return error("无权查看该券");
        }
        // 附券展示信息(名称/描述)+ 失效投影,都靠这一次券查询,不多读一遍
        SmsCoupon coupon = smsCouponService.selectSmsCouponById(h.getCouponId());
        Integer useStatus = displayedUseStatus(h, coupon);
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        // C-36:已核销/已过期/已失效不再签发可扫码 token,只回终态(客户端 parseQrReply 对非 0 本就不读码)。
        if (Integer.valueOf(0).equals(useStatus)) {
            String token = smsCouponService.generateCouponQrToken(couponHistoryId);
            data.put("token", token);
            data.put("qrcodeUrl", qrcodeService.generateInlineQrcode(token, 300, 300));
            data.put("expiresIn", 60);
        }
        data.put("couponHistoryId", couponHistoryId);
        data.put("useStatus", useStatus);
        data.put("useTime", h.getUseTime());
        // 使用期承诺:start 前客户端据此显示「可使用日期 + 等待」,不误报已过期;
        // 真正能否核销由服务端 redeemCoupon / claimUnusedCoupon 权威判定。
        data.put("startTime", h.getStartTime());
        data.put("endTime", h.getEndTime());
        if (coupon != null) {
            data.put("couponName", coupon.getName());
            data.put("description", coupon.getDescription());
        }
        return success(data);
    }

    @Operation(summary = "优惠券核销状态轮询(M0-2)")
    @PostMapping("/status")
    @Parameters({@Parameter(name = "couponHistoryId", description = "领券历史ID")})
    public AjaxResult couponStatus(Long couponHistoryId) {
        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
        }
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        if (couponHistoryId == null) {
            return error("参数缺失");
        }
        SmsCouponHistory h = smsCouponHistoryService.selectSmsCouponHistoryById(couponHistoryId);
        if (h == null || (h.getDelFlag() != null && h.getDelFlag() != 0)) {
            return AjaxResult.error(410, "优惠券已撤销或不存在");
        }
        if (OwnershipUtils.notOwner(h.getMemberId(), member_id)) {
            return error("无权查看该券");
        }
        java.util.Map<String, Object> data = new java.util.HashMap<>();
        data.put("useStatus", displayedUseStatus(h, smsCouponService.selectSmsCouponById(h.getCouponId())));
        data.put("useTime", h.getUseTime());
        return success(data);
    }

    /**
     * 读侧展示投影:已核销(1)保持既成事实；平台手动失效(status=3)投影为 3「已失效」;
     * 再按持券原使用期判过期(2)。不依赖先打开钱包,也不改写历史核销事实。
     * 商家停发(status=4)不拦已领券,照常显示待使用,服务端核销也放行。
     */
    private Integer displayedUseStatus(SmsCouponHistory history, SmsCoupon coupon) {
        if (Integer.valueOf(1).equals(history.getUseStatus())) {
            return 1;
        }
        if (coupon != null && Integer.valueOf(3).equals(coupon.getStatus())) {
            return 3;
        }
        return Integer.valueOf(0).equals(history.getUseStatus()) && history.getEndTime() != null
                && history.getEndTime().before(new Date()) ? 2 : history.getUseStatus();
    }

    @PostMapping("/verification")
    @Operation(summary = "优惠券核销")
    @Parameters({
            @Parameter(name = "code", description = "二维码识别出来的code(兼容旧静态码,也兼容动态token)"),
            @Parameter(name = "token", description = "M0-2 动态二维码token(优先)")
    })
    public AjaxResult couponVerification(String code, String token, String scope) {
        Long member_id = 0L;
        try {
            member_id = getAppUserId();
        } catch (Exception e) {
            return error("请先登录");
        }
        if (member_id == null || member_id <= 0) {
            return error("请先登录");
        }
        CouponOperationContext operation = resolveCouponOperation(
                member_id, MerchantPermission.VERIFY, scope);
        if (operation.legacyClub) {
            UmsMember verifier = umsMemberService.selectUmsMemberById(member_id);
            UserPermission permission = roleService.getPermission(verifier);
            if (permission == null || !Boolean.TRUE.equals(permission.getCanRedeemCoupon())) {
                return error("当前身份不支持核销优惠券");
            }
        }
        Long ownerMemberId = operation.ownerMemberId;

        // 解析核销目标:只认服务端签发的动态 token(token 优先,扫码器可能把 token 塞进 code)。
        // F6:不再回落静态 couponCode —— 静态码是永久凭据,截图即可复用,会绕过 60s 时效。
        String credential = StringUtils.isNotBlank(token) ? token : code;
        if (StringUtils.isBlank(credential)) {
            return error("请输入核销码");
        }
        Long hid = smsCouponService.verifyCouponQrToken(credential);
        if (hid == null) {
            return error("二维码已过期或无效，请让顾客刷新券码后重试");
        }
        SmsCouponHistory target = smsCouponHistoryService.selectSmsCouponHistoryById(hid);
        if (target == null) {
            return error("该优惠券不存在");
        }

        SmsCoupon coupon = smsCouponService.selectSmsCouponById(target.getCouponId());
        if (coupon == null) {
            return error("该优惠券不存在");
        }
        if (OwnershipUtils.notOwner(coupon.getMemberId(), ownerMemberId)) {
            return error("无权核销该优惠券");
        }

        ISmsCouponHistoryService.RedemptionResult result =
                smsCouponHistoryService.redeemCoupon(target.getId(), ownerMemberId,
                        operation.merchantId, member_id, operation.actorRoleCode);
        switch (result) {
            case REDEEMED:
                return success("核销成功");
            case ALREADY_USED:
                SmsCouponHistory current = smsCouponHistoryService.selectSmsCouponHistoryById(target.getId());
                java.util.Map<String, Object> data = new java.util.HashMap<>();
                data.put("alreadyUsed", true);
                data.put("useTime", current == null ? null : current.getUseTime());
                return AjaxResult.success("该优惠券已核销", data);
            case FORBIDDEN:
                return error("无权核销该优惠券");
            case EXPIRED:
                return error("该优惠券已过期");
            case DISABLED:
                return error("该优惠券已停用");
            case NOT_STARTED:
                // 使用期承诺:start 前拒核销,并明确告知可用日期(不是「已过期」)。
                String usableFrom = target.getStartTime() == null ? null
                        : DateUtils.parseDateToStr(DateUtils.YYYY_MM_DD_HH_MM_SS, target.getStartTime());
                return error(usableFrom == null ? "该优惠券尚未到可用时间"
                        : "该优惠券于 " + usableFrom + " 起可使用");
            default:
                return error("该优惠券不存在");
        }
    }

    public AjaxResult couponVerification(String code, String token) {
        return couponVerification(code, token, null);
    }

    private CouponOperationContext resolveCouponOperation(Long principalMemberId,
                                                          String merchantPermission,
                                                          String scope) {
        if (MerchantOperationScope.isMerchant(scope)) {
            MerchantAccessContext access = merchantAccessService.require(principalMemberId, merchantPermission);
            return new CouponOperationContext(access.getOwnerMemberId(), access.getMerchantId(),
                    access.getRoleCode(), false);
        }
        UmsMember member = umsMemberService.selectUmsMemberById(principalMemberId);
        if (member != null && IRoleService.ROLE_CLUB.equals(roleService.resolveRole(member))) {
            return new CouponOperationContext(principalMemberId, null, IRoleService.ROLE_CLUB, true);
        }
        MerchantAccessContext access = merchantAccessService.require(principalMemberId, merchantPermission);
        return new CouponOperationContext(access.getOwnerMemberId(), access.getMerchantId(),
                access.getRoleCode(), false);
    }

    private static final class CouponOperationContext {
        private final Long ownerMemberId;
        private final Long merchantId;
        private final String actorRoleCode;
        private final boolean legacyClub;

        private CouponOperationContext(Long ownerMemberId, Long merchantId,
                                       String actorRoleCode, boolean legacyClub) {
            this.ownerMemberId = ownerMemberId;
            this.merchantId = merchantId;
            this.actorRoleCode = actorRoleCode;
            this.legacyClub = legacyClub;
        }
    }

}
