package com.chengyinhub.business.commerceconsole;

import com.chengyinhub.business.commerceconsole.mapper.CommerceConsoleMapper;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * T5 MON-16:营销商品(含城市包)、探索套票、俱乐部会费的后台页。奖励承诺已有 R10 控制台,这里不重复。
 *
 * <p>只读为主;两项安全写:营销商品「停售」(在售 → 停售)与俱乐部会费方案「停用」(ACTIVE → INACTIVE)。
 * 两者都只减少售卖,不开售、不改价、不退款、不碰资金开关;已下的单和已付的权益期按各自快照继续。
 * 每次写都要原因,并与业务写同一事务追加平台审计。</p>
 */
@Service
public class CommerceConsoleService {
    static final int MAX_LIMIT = 200;
    static final Set<String> PLAN_STATUSES = Set.of("ACTIVE", "INACTIVE");
    static final Set<String> INVOICE_STATUSES = Set.of("CREATED", "PAYING", "PAID", "CLOSED");

    private final CommerceConsoleMapper mapper;
    private final OpsConsoleAudit audit;

    public CommerceConsoleService(CommerceConsoleMapper mapper, OpsConsoleAudit audit) {
        this.mapper = mapper;
        this.audit = audit;
    }

    public List<Map<String, Object>> products(Integer status, Integer kind, Integer limit) {
        if (status != null && (status < 0 || status > 2)) throw new ServiceException("商品状态只能是 0 / 1 / 2");
        if (kind != null && (kind < 1 || kind > 8)) throw new ServiceException("商品类型只能是 1 到 8");
        return mapper.selectProducts(status, kind, limit(limit));
    }

    public List<Map<String, Object>> productOrders(Long productId, Integer status, Integer limit) {
        if (status != null && (status < 0 || status > 3)) throw new ServiceException("订单状态只能是 0 到 3");
        return mapper.selectProductOrders(productId, status, limit(limit));
    }

    public List<Map<String, Object>> bundleOrders(Long topicId, Integer limit) {
        return mapper.selectBundleOrders(topicId, limit(limit));
    }

    public List<Map<String, Object>> duesPlans(Long clubId, String status, Integer limit) {
        return mapper.selectDuesPlans(clubId, upper(status, PLAN_STATUSES, "会费方案状态"), limit(limit));
    }

    public List<Map<String, Object>> duesInvoices(Long clubId, String status, Integer limit) {
        return mapper.selectDuesInvoices(clubId, upper(status, INVOICE_STATUSES, "账单状态"), limit(limit));
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> stopProductSale(Long sysUserId, long productId, String reason) {
        String why = reason(reason);
        Map<String, Object> before = mapper.selectProductForUpdate(productId);
        if (before == null || before.isEmpty()) throw new ServiceException("商品不存在");
        if (mapper.stopProductSale(productId) != 1) throw new ServiceException("只有在售的商品可以停售");
        Map<String, Object> after = mapper.selectProductForUpdate(productId);
        audit.record(sysUserId, "MARKETING_PRODUCT_STOP_SALE", "MARKETING_PRODUCT", productId, before, after, why);
        return result(productId, after);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deactivateDuesPlan(Long sysUserId, String operator, long planId, String reason) {
        String why = reason(reason);
        Map<String, Object> before = mapper.selectDuesPlanForUpdate(planId);
        if (before == null || before.isEmpty()) throw new ServiceException("会费方案不存在");
        if (mapper.deactivateDuesPlan(planId, operator) != 1) throw new ServiceException("只有启用中的会费方案可以停用");
        Map<String, Object> after = mapper.selectDuesPlanForUpdate(planId);
        audit.record(sysUserId, "CLUB_DUES_PLAN_DEACTIVATE", "CLUB_MEMBERSHIP_PLAN", planId, before, after, why);
        return result(planId, after);
    }

    static int limit(Integer limit) {
        return limit == null || limit <= 0 ? 50 : Math.min(limit, MAX_LIMIT);
    }

    static String reason(String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) throw new ServiceException("请填写原因");
        if (why.length() > 500) throw new ServiceException("原因最多 500 字");
        return why;
    }

    private static String upper(String value, Set<String> allowed, String label) {
        if (value == null || value.isBlank()) return null;
        String v = value.trim().toUpperCase();
        if (!allowed.contains(v)) throw new ServiceException(label + "不正确");
        return v;
    }

    private static Map<String, Object> result(long id, Map<String, Object> after) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", after == null ? null : after.get("status"));
        return out;
    }
}
