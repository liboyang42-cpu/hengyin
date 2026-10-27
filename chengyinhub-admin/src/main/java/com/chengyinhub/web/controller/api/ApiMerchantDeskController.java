package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.IMerchantDeskService;
import com.chengyinhub.business.service.MerchantDeskException;
import com.chengyinhub.business.service.support.MerchantDeskCodePolicy;
import com.chengyinhub.business.service.support.MerchantDeskSwitch;
import com.chengyinhub.business.service.support.MerchantMetricDictionary;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

/**
 * Doc2 F4 商家工作台(后端;页面由 owner 自建,复用 pages/merchant/index 的扫码路由契约)。
 *
 * <p>总开关 sys_config['merchant.desk.f4.enabled'] 默认 false:关闭时每个端点都返回 404 + errorCode=F4_DISABLED,
 * 不读不写任何新表。每个端点先过登录 → 开关 → 商家权限({@link IMerchantAccessService#require}),
 * 归属只取服务端解析的上下文。核销本身仍走既有在线端点,本控制器只归类码与给表单,不核销、不动钱。
 */
@RestController
@Tag(name = "商家工作台F4")
@RequestMapping("/api/merchant/desk")
public class ApiMerchantDeskController extends BaseController {

    @Autowired private IMerchantAccessService merchantAccessService;
    @Autowired private IMerchantDeskService merchantDeskService;
    @Autowired private MerchantDeskSwitch deskSwitch;

    @Operation(summary = "按请求号读回写操作回执(超时/UNKNOWN 时用)")
    @PostMapping("/receipt")
    public AjaxResult receipt(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.BASIC_READ, access -> merchantDeskService.receipt(access, text(body, "requestId")));
    }

    @Operation(summary = "我的门店(含营业时间解析、锚点是否配置、各项编辑入口)")
    @PostMapping("/stores")
    public AjaxResult stores() {
        return run(MerchantPermission.BASIC_READ, merchantDeskService::stores);
    }

    @Operation(summary = "核销锚点配置(地图点+半径+WiFi)")
    @PostMapping("/anchor")
    public AjaxResult anchor() {
        return run(MerchantPermission.BASIC_READ, merchantDeskService::anchor);
    }

    @Operation(summary = "保存核销锚点配置(幂等,乐观锁)")
    @PostMapping("/anchor/save")
    public AjaxResult saveAnchor(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.PROFILE_WRITE, access -> merchantDeskService.saveAnchor(access, safe(body)));
    }

    @Operation(summary = "核销锚点自检(只判定,不影响任何核销)")
    @PostMapping("/anchor/check")
    public AjaxResult checkAnchor(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.VERIFY, access -> merchantDeskService.checkAnchor(access, safe(body)));
    }

    @Operation(summary = "本门店商品列表")
    @PostMapping("/products")
    public AjaxResult products() {
        return run(MerchantPermission.PROJECT_MANAGE, merchantDeskService::products);
    }

    @Operation(summary = "本门店商品详情(含 SKU 与预约规则)")
    @PostMapping("/products/detail")
    public AjaxResult productDetail(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.PROJECT_MANAGE,
                access -> merchantDeskService.productDetail(access, number(body, "productId")));
    }

    @Operation(summary = "新建/修改商品(幂等;落为待审核,上架中不可改)")
    @PostMapping("/products/save")
    public AjaxResult saveProduct(@RequestBody(required = false) Map<String, Object> body) {
        return runProductWrite(access -> merchantDeskService.saveProduct(access, safe(body)));
    }

    @Operation(summary = "下架商品(幂等)")
    @PostMapping("/products/delist")
    public AjaxResult delistProduct(@RequestBody(required = false) Map<String, Object> body) {
        return runProductWrite(access -> merchantDeskService.delistProduct(access, safe(body)));
    }

    @Operation(summary = "新建/修改 SKU 与预约容量规则(幂等,乐观锁)")
    @PostMapping("/products/sku/save")
    public AjaxResult saveSku(@RequestBody(required = false) Map<String, Object> body) {
        return runProductWrite(access -> merchantDeskService.saveSku(access, safe(body)));
    }

    @Operation(summary = "SKU 时段容量(只读;由 App/后台批量生成)")
    @PostMapping("/products/sku/slots")
    public AjaxResult skuSlots(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.PROJECT_MANAGE, access -> merchantDeskService.capacitySlots(access,
                number(body, "skuId"), text(body, "fromDate"), integer(body, "days")));
    }

    @Operation(summary = "SKU时段游标分页（最多14天，实时容量读回）")
    @PostMapping("/products/sku/slots/page")
    public AjaxResult skuSlotPage(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.PROJECT_MANAGE, access -> merchantDeskService.capacitySlotPage(access,
                number(body, "skuId"), text(body, "fromDate"), integer(body, "days"),
                number(body, "afterStartAt"), number(body, "afterSlotId")));
    }

    @Operation(summary = "今日到店(今日已核销 + 生效供给章节上的未核销权益数)")
    @PostMapping("/arrivals/today")
    public AjaxResult arrivalsToday() {
        return run(MerchantPermission.VERIFY_RECORD_READ, merchantDeskService::arrivalsToday);
    }

    @Operation(summary = "核销台:归类玩家核销凭证并给出提交表单(扫码/手输;不核销)")
    @PostMapping("/redeem/resolve")
    public AjaxResult resolveCode(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.VERIFY, access -> {
            Map<String, Object> view = MerchantDeskCodePolicy.classify(text(body, "code")).toView();
            view.put("inputMode", MerchantDeskCodePolicy.normalizeMode(text(body, "mode")));
            return view;
        });
    }

    @Operation(summary = "核销台:从 OCR 文本里抽候选核销码(排除节点任务码;不核销)")
    @PostMapping("/redeem/ocr-candidates")
    public AjaxResult ocrCandidates(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.VERIFY, access -> MerchantDeskCodePolicy.ocrCandidates(text(body, "text")));
    }

    @Operation(summary = "核销复核单列表(本商家)")
    @PostMapping("/verdicts")
    public AjaxResult verdicts(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.VERIFY_RECORD_READ,
                access -> merchantDeskService.reviews(access, integer(body, "status")));
    }

    @Operation(summary = "核销复核结论详情 + 已补充材料")
    @PostMapping("/verdicts/detail")
    public AjaxResult verdictDetail(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.VERIFY_RECORD_READ,
                access -> merchantDeskService.reviewDetail(access, number(body, "reviewId")));
    }

    @Operation(summary = "给待复核单补充材料(幂等)")
    @PostMapping("/verdicts/evidence")
    public AjaxResult submitEvidence(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.VERIFY, access -> merchantDeskService.submitReviewEvidence(access, safe(body)));
    }

    @Operation(summary = "对账总览:四个对账口径(只读)")
    @PostMapping("/reconciliation")
    public AjaxResult reconciliation() {
        return run(MerchantPermission.FINANCE_READ, merchantDeskService::reconciliationSummary);
    }

    @Operation(summary = "对账明细:按口径分页列结算主张(只读)")
    @PostMapping("/reconciliation/claims")
    public AjaxResult reconciliationClaims(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.FINANCE_READ, access -> merchantDeskService.reconciliationClaims(access,
                text(body, "route"), integer(body, "pageNum"), integer(body, "pageSize")));
    }

    @Operation(summary = "冻结中的结算批次明细(只读)")
    @PostMapping("/frozen")
    public AjaxResult frozen() {
        return run(MerchantPermission.FINANCE_READ, merchantDeskService::frozenBatches);
    }

    @Operation(summary = "当前冻结批次完整历史游标分页（只读）")
    @PostMapping("/frozen/page")
    public AjaxResult frozenPage(@RequestBody(required = false) Map<String, Object> body) {
        return run(MerchantPermission.FINANCE_READ, access -> merchantDeskService.frozenBatchPage(access,
                text(body, "afterPeriodYm"), number(body, "afterBatchId")));
    }

    @Operation(summary = "商家看板指标口径字典")
    @PostMapping("/metrics/dictionary")
    public AjaxResult metricDictionary() {
        return run(MerchantPermission.BASIC_READ, access -> MerchantMetricDictionary.view());
    }

    /** D2:商品与履约写入口在子开关关闭时按工作台标准错误(404 + F4_DISABLED)拒绝,不进服务。 */
    private AjaxResult runProductWrite(Function<MerchantAccessContext, Object> action) {
        return run(MerchantPermission.PROJECT_MANAGE, access -> {
            deskSwitch.requireProductsEnabled();
            return action.apply(access);
        });
    }

    private AjaxResult run(String permission, Function<MerchantAccessContext, Object> action) {
        Long principal;
        try { principal = getAppUserId(); } catch (Exception e) { return error("请先登录"); }
        if (principal == null || principal <= 0) return error("请先登录");
        try {
            deskSwitch.requireEnabled();
            MerchantAccessContext access = merchantAccessService.require(principal, permission);
            return success(MerchantDeskWire.response(action.apply(access)));
        } catch (MerchantDeskException e) {
            AjaxResult result = new AjaxResult(e.getHttpCode(), e.getMessage());
            result.put("errorCode", e.getErrorCode());
            return result;
        } catch (DuplicateKeyException e) {
            AjaxResult result = new AjaxResult(409, "同一请求正在处理，请稍后按请求号读回结果");
            result.put("errorCode", "F4_IN_FLIGHT");
            return result;
        } catch (ServiceException e) {
            return new AjaxResult(e.getCode() == null ? 500 : e.getCode(), e.getMessage());
        }
    }

    private static Map<String, Object> safe(Map<String, Object> body) {
        return body == null ? Collections.emptyMap() : body;
    }

    private static String text(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static Long number(Map<String, Object> body, String key) {
        String value = text(body, key);
        if (value == null || value.trim().isEmpty()) return null;
        try {
            return Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            throw new MerchantDeskException(400, MerchantDeskException.BAD_REQUEST, "参数 " + key + " 需为整数");
        }
    }

    private static Integer integer(Map<String, Object> body, String key) {
        Long value = number(body, key);
        if (value != null && (value > Integer.MAX_VALUE || value < Integer.MIN_VALUE)) {
            throw new MerchantDeskException(400, MerchantDeskException.BAD_REQUEST, "参数 " + key + " 超出范围");
        }
        return value == null ? null : value.intValue();
    }
}
