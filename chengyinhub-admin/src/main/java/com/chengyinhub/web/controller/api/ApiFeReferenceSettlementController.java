package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.service.IFeReferenceSettlementService;
import com.chengyinhub.common.core.controller.BaseController;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * F11 自由探索店铺参考结算(参考金额,不入余额;店铺与场次收款人线下结款)。
 *
 * <ul>
 *   <li>店铺读 / 提异议:商家身份由 {@link IMerchantAccessService} 解析到店铺 member_id,只看本店;</li>
 *   <li>收款人读 / 标记已线下结清:当前登录人必须是该场次的收款人(与结算入账同一规则)。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/fe/reference-settlement")
public class ApiFeReferenceSettlementController extends BaseController {

    private final IFeReferenceSettlementService service;
    private final IMerchantAccessService merchantAccess;

    public ApiFeReferenceSettlementController(IFeReferenceSettlementService service,
                                              IMerchantAccessService merchantAccess) {
        this.service = service;
        this.merchantAccess = merchantAccess;
    }

    @GetMapping("/merchant")
    public AjaxResult merchant(@RequestParam(required = false) String status) {
        try {
            Long owner = merchantAccess.require(getAppUserId(), MerchantPermission.FINANCE_READ).getOwnerMemberId();
            return success(service.listForMerchant(owner, status));
        } catch (ServiceException e) {
            return failure(e);
        }
    }

    @GetMapping("/payer")
    public AjaxResult payer(@RequestParam(required = false) Long activityId) {
        try {
            return success(service.listForPayer(getAppUserId(), activityId));
        } catch (ServiceException e) {
            return failure(e);
        }
    }

    /** 收款人标记已线下结清 {ids[], voucherNo?, note?}。 */
    @PostMapping("/settle")
    public AjaxResult settle(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> request = body == null ? Collections.emptyMap() : body;
            int changed = service.settle(getAppUserId(), ids(request.get("ids")),
                    text(request.get("voucherNo")), text(request.get("note")));
            return success(Collections.singletonMap("settledCount", changed));
        } catch (ServiceException e) {
            return failure(e);
        }
    }

    /** 店铺对一行提出异议 {id, note}。 */
    @PostMapping("/dispute")
    public AjaxResult dispute(@RequestBody(required = false) Map<String, Object> body) {
        try {
            Map<String, Object> request = body == null ? Collections.emptyMap() : body;
            Long owner = merchantAccess.require(getAppUserId(), MerchantPermission.FINANCE_READ).getOwnerMemberId();
            service.dispute(owner, id(request.get("id")), text(request.get("note")));
            return success();
        } catch (ServiceException e) {
            return failure(e);
        }
    }

    /** 权益卡表单里的线下结算说明(服务端下发,含主办方名);非自由探索章节返回 text=null。 */
    @GetMapping("/note")
    public AjaxResult note(@RequestParam Long chapterId) {
        try {
            getAppUserId();
            return success(Collections.singletonMap("text", service.offlineSettlementNoteForChapter(chapterId)));
        } catch (ServiceException e) {
            return failure(e);
        }
    }

    private AjaxResult failure(ServiceException e) {
        return e.getCode() == null ? error(e.getMessage()) : new AjaxResult(e.getCode(), e.getMessage());
    }

    private static List<Long> ids(Object raw) {
        if (!(raw instanceof List)) throw new ServiceException("请选择要标记的账目");
        List<Long> ids = new ArrayList<>();
        for (Object value : (List<?>) raw) ids.add(id(value));
        return ids;
    }

    private static Long id(Object value) {
        if (value instanceof Number && ((Number) value).longValue() == ((Number) value).doubleValue()) {
            return ((Number) value).longValue();
        }
        if (value instanceof String && ((String) value).matches("\\d{1,18}")) return Long.valueOf((String) value);
        throw new ServiceException("账目编号无效");
    }

    private static String text(Object value) {
        if (value == null) return null;
        if (!(value instanceof String)) throw new ServiceException("参数格式不正确");
        return (String) value;
    }
}
