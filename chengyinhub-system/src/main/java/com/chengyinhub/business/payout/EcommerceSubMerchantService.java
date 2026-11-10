package com.chengyinhub.business.payout;

import com.chengyinhub.business.domain.EcomSubMerchant;
import com.chengyinhub.business.mapper.EcomPayoutMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 二级商户进件：登记（DRAFT）→ 平台运营提交进件资料 → 按微信查询结果推进状态 → FINISH 后得到 sub_mchid。
 * 资料只经由后台管理端提交（敏感字段服务端加密，不落库）；商家/俱乐部端只读状态。
 */
@Service
public class EcommerceSubMerchantService {
    private static final Logger log = LoggerFactory.getLogger(EcommerceSubMerchantService.class);
    public static final String OWNER_MERCHANT = "MERCHANT";
    public static final String OWNER_CLUB = "CLUB";

    private final EcomPayoutMapper mapper;
    private final EcommercePayGateway gateway;
    private final PayoutSwitches switches;

    public EcommerceSubMerchantService(EcomPayoutMapper mapper, EcommercePayGateway gateway, PayoutSwitches switches) {
        this.mapper = mapper;
        this.gateway = gateway;
        this.switches = switches;
    }

    static String outRequestNo(String ownerType, long ownerId) {
        return "CYAP" + (OWNER_MERCHANT.equals(ownerType) ? "M" : "C") + ownerId;
    }

    private static void requireOwner(String ownerType, Long ownerId) {
        if (!OWNER_MERCHANT.equals(ownerType) && !OWNER_CLUB.equals(ownerType)) throw new ServiceException("收款主体类型无效", 400);
        if (ownerId == null || ownerId <= 0) throw new ServiceException("收款主体无效", 400);
    }

    /** 登记进件（幂等）：同一商家/俱乐部只有一条。 */
    public EcomSubMerchant register(String ownerType, Long ownerId, String operator) {
        requireOwner(ownerType, ownerId);
        if (!switches.ecommerceEnabled()) throw new ServiceException("电商收付通未开启", 409);
        EcomSubMerchant existing = mapper.selectSubMerchant(ownerType, ownerId);
        if (existing != null) return existing;
        if (mapper.selectSellerMemberId(ownerType, ownerId) == null) throw new ServiceException("收款主体不存在", 404);
        EcomSubMerchant row = new EcomSubMerchant();
        row.setOwnerType(ownerType);
        row.setOwnerId(ownerId);
        row.setOutRequestNo(outRequestNo(ownerType, ownerId));
        row.setCreatedBy(operator);
        try {
            mapper.insertSubMerchant(row);
        } catch (DuplicateKeyException concurrent) {
            // 并发登记：以已存在的那条为准。
        }
        return mapper.selectSubMerchant(ownerType, ownerId);
    }

    /** 提交进件资料。只有 DRAFT 可提交；微信按 out_request_no 幂等，网络异常后用 refresh 查证，不重复建单。 */
    public EcomSubMerchant submit(Long id, Map<String, Object> applymentBody) {
        if (!switches.ecommerceEnabled()) throw new ServiceException("电商收付通未开启", 409);
        EcomSubMerchant row = mapper.selectSubMerchantById(id);
        if (row == null) throw new ServiceException("进件记录不存在", 404);
        if (!ApplymentState.DRAFT.name().equals(row.getApplymentState())) throw new ServiceException("进件已提交，请刷新状态", 409);
        EcommercePayGateway.ApplymentSubmitted submitted = gateway.submitApplyment(row.getOutRequestNo(), applymentBody);
        mapper.markApplymentSubmitted(id, submitted == null ? null : submitted.applymentId());
        return mapper.selectSubMerchantById(id);
    }

    /** 按微信查询结果推进状态；状态只能按 {@link ApplymentState#canMoveTo} 前进，FINISH 必须带 sub_mchid。 */
    public EcomSubMerchant refresh(Long id) {
        if (!switches.ecommerceEnabled()) throw new ServiceException("电商收付通未开启", 409);
        EcomSubMerchant row = mapper.selectSubMerchantById(id);
        if (row == null) throw new ServiceException("进件记录不存在", 404);
        EcommercePayGateway.ApplymentStatus status = gateway.queryApplyment(row.getOutRequestNo());
        apply(row, status);
        return mapper.selectSubMerchantById(id);
    }

    void apply(EcomSubMerchant row, EcommercePayGateway.ApplymentStatus status) {
        if (status == null) return;
        ApplymentState current = ApplymentState.valueOf(row.getApplymentState());
        ApplymentState next = ApplymentState.fromWechat(status.state());
        if (next == null) {
            log.warn("ecom applyment unknown state id={}", row.getId());
            return;
        }
        String subMchid = status.subMchid() == null || status.subMchid().isBlank() ? null : status.subMchid().trim();
        if (next == ApplymentState.FINISH && (subMchid == null || !subMchid.matches("[0-9]{6,32}"))) {
            log.warn("ecom applyment FINISH without sub_mchid id={}", row.getId());
            return;
        }
        if (next == current) {
            mapper.touchApplymentQueried(row.getId(), status.stateDesc(), status.signUrl());
            return;
        }
        if (!current.canMoveTo(next)) return;
        mapper.updateApplymentStatus(row.getId(), current.name(), next.name(), status.stateDesc(), status.signUrl(),
                subMchid, status.applymentId());
    }

    /** 定时/手动批量刷新进行中的进件。单条失败不影响其它条。 */
    public int refreshInProgress(int limit) {
        if (!switches.ecommerceEnabled()) return 0;
        int n = 0;
        for (EcomSubMerchant row : mapper.selectSubMerchantsInProgress(Math.max(1, Math.min(limit, 200)))) {
            try {
                apply(row, gateway.queryApplyment(row.getOutRequestNo()));
                n++;
            } catch (RuntimeException e) {
                log.warn("ecom applyment refresh failed id={} type={}", row.getId(), e.getClass().getSimpleName());
            }
        }
        return n;
    }

    /** 商家/俱乐部端只读视图：只给服务端事实，未开通时只给不可用原因。 */
    public Map<String, Object> ownerView(String ownerType, Long ownerId) {
        requireOwner(ownerType, ownerId);
        Map<String, Object> view = new LinkedHashMap<>();
        if (!switches.ecommerceEnabled()) {
            view.put("available", false);
            view.put("unavailableReason", "PLATFORM_NOT_OPEN");
            return view;
        }
        view.put("available", true);
        EcomSubMerchant row = mapper.selectSubMerchant(ownerType, ownerId);
        if (row == null) {
            view.put("state", "NONE");
            return view;
        }
        ApplymentState state = ApplymentState.valueOf(row.getApplymentState());
        view.put("state", state.name());
        if (row.getApplymentStateDesc() != null && !row.getApplymentStateDesc().isBlank())
            view.put("stateDesc", row.getApplymentStateDesc());
        // 签约/验证链接只在需要本人操作的状态下给出。
        if ((state == ApplymentState.NEED_SIGN || state == ApplymentState.ACCOUNT_NEED_VERIFY)
                && row.getSignUrl() != null && row.getSignUrl().startsWith("https://"))
            view.put("signUrl", row.getSignUrl());
        if (state == ApplymentState.FINISH || state == ApplymentState.FROZEN) view.put("subMchidMasked", mask(row.getSubMchid()));
        if (row.getLastQueriedAt() != null) view.put("checkedAt", row.getLastQueriedAt());
        view.put("sellerRouting", switches.sellerRoutingEnabled() && state == ApplymentState.FINISH);
        return view;
    }

    static String mask(String subMchid) {
        if (subMchid == null || subMchid.length() < 6) return null;
        return subMchid.substring(0, 2) + "****" + subMchid.substring(subMchid.length() - 4);
    }
}
