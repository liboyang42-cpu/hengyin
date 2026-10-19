package com.chengyinhub.business.service.impl;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.domain.SmsCouponRuleVersion;
import com.chengyinhub.business.domain.dto.CouponRuleChangeDTO;
import com.chengyinhub.business.mapper.SmsCouponHistoryMapper;
import com.chengyinhub.business.mapper.SmsCouponMapper;
import com.chengyinhub.business.mapper.SmsCouponRuleVersionMapper;
import com.chengyinhub.business.service.ICouponRuleGovernanceService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;

/**
 * F0572 平台优惠券模板治理。
 *
 * <p>规则来源:pretix vouchers —— 发放总量(max_usages)永不低于已领取(redeemed);
 * 改规则只影响之后的领取,已领取的券保持领取时冻结的规则(有效期在 history.start/end_time,
 * 面额/门槛在 history.rule_amount / rule_min_point)。
 *
 * <p>并发:所有写操作先 {@code SELECT ... FOR UPDATE} 锁券行再比对 rule_version;发券
 * ({@link SmsCouponHistoryServiceImpl#grantCouponWithReceipt})同样先锁券行再判可发、冻结规则,
 * 所以「改规则 / 停发」与「领取」在同一把行锁上串行:领取要么完整落在旧版本,要么完整落在新版本。
 */
@Service
public class CouponRuleGovernanceServiceImpl implements ICouponRuleGovernanceService
{
    /** 面额/折扣上限(元)。超过即视为录入错误,不是合法的业务配置。 */
    static final BigDecimal MAX_AMOUNT = new BigDecimal("100000.00");
    /** 使用门槛上限(元) */
    static final BigDecimal MAX_MIN_POINT = new BigDecimal("1000000.00");
    static final int REASON_MAX = 255;
    static final int HOLDER_LIST_LIMIT = 500;

    private static final Integer STATUS_NOT_STARTED = 0;
    private static final Integer STATUS_ACTIVE = 1;
    private static final Integer STATUS_ENDED = 2;
    private static final Integer STATUS_INVALIDATED = 3;
    private static final Integer STATUS_STOPPED = 4;

    @Autowired
    private SmsCouponMapper smsCouponMapper;

    @Autowired
    private SmsCouponHistoryMapper smsCouponHistoryMapper;

    @Autowired
    private SmsCouponRuleVersionMapper ruleVersionMapper;

    @Override
    public SmsCoupon getWithRuleVersion(Long couponId)
    {
        return couponId == null ? null : smsCouponMapper.selectCouponRuleState(couponId);
    }

    @Override
    public Map<String, Object> previewChange(CouponRuleChangeDTO change)
    {
        if (change == null || change.getId() == null)
        {
            throw new ServiceException("优惠券不存在", HttpStatus.BAD_REQUEST);
        }
        SmsCoupon current = smsCouponMapper.selectCouponRuleState(change.getId());
        if (current == null || isDeleted(current))
        {
            throw new ServiceException("优惠券不存在", HttpStatus.BAD_REQUEST);
        }
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("couponId", current.getId());
        preview.put("currentVersion", current.getRuleVersion());
        preview.put("expectedVersion", change.getExpectedVersion());
        preview.put("versionConflict", change.getExpectedVersion() == null
                || !change.getExpectedVersion().equals(current.getRuleVersion()));
        preview.put("validationError", validate(current, change));
        List<Map<String, Object>> changes = diff(current, change);
        preview.put("changes", changes);

        Map<String, Object> newClaims = new LinkedHashMap<>();
        newClaims.put("ruleVersion", current.getRuleVersion() == null ? null : current.getRuleVersion() + 1);
        newClaims.put("issuable", isIssuable(current));
        Long publish = change.getPublishCount() != null ? change.getPublishCount() : current.getPublishCount();
        long received = current.getReceiveCount() == null ? 0L : current.getReceiveCount();
        newClaims.put("publishCount", publish);
        newClaims.put("remaining", publish == null || publish <= 0 ? null : Math.max(0L, publish - received));
        preview.put("newClaims", newClaims);

        preview.put("claimed", claimedSummary(current.getId()));
        return preview;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SmsCouponRuleVersion applyChange(CouponRuleChangeDTO change, Long operatorId)
    {
        if (change == null || change.getId() == null)
        {
            throw new ServiceException("优惠券不存在", HttpStatus.BAD_REQUEST);
        }
        if (!change.getRejectedFields().isEmpty())
        {
            throw new ServiceException("以下字段不允许在规则变更中修改：" + String.join("、", change.getRejectedFields()),
                    HttpStatus.BAD_REQUEST);
        }
        SmsCoupon current = lockCurrent(change.getId(), change.getExpectedVersion());
        String error = validate(current, change);
        if (error != null)
        {
            throw new ServiceException(error, HttpStatus.BAD_REQUEST);
        }
        List<Map<String, Object>> changes = diff(current, change);
        if (changes.isEmpty())
        {
            throw new ServiceException("没有需要保存的规则变更", HttpStatus.BAD_REQUEST);
        }
        String reason = optionalReason(change.getReason());
        ensureBaseline(current, operatorId);

        SmsCoupon update = new SmsCoupon();
        update.setId(current.getId());
        List<String> fields = new ArrayList<>();
        for (Map<String, Object> item : changes)
        {
            String field = (String) item.get("field");
            fields.add(field);
            applyField(update, change, field);
        }
        if (smsCouponMapper.updateCouponRuleCas(update, current.getRuleVersion()) != 1)
        {
            // 行锁内只有总量守卫可能拦下(并发领取已把 receive_count 推过新总量 —— 行锁已排除,此处兜底)
            throw new ServiceException("发放总量不能低于已领取数量，请刷新后重试", HttpStatus.CONFLICT);
        }
        SmsCoupon after = smsCouponMapper.selectCouponRuleStateForUpdate(current.getId());
        SmsCouponRuleVersion row = SmsCouponRuleVersion.of(after, after.getRuleVersion(),
                SmsCouponRuleVersion.RULE_UPDATE);
        row.setChangedFields(String.join(",", fields));
        row.setReason(reason);
        row.setOperatorId(operatorId);
        insertVersion(row);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public SmsCouponRuleVersion stopIssue(Long couponId, Integer expectedVersion, String reason, Long operatorId)
    {
        SmsCoupon current = lockCurrent(couponId, expectedVersion);
        if (!STATUS_NOT_STARTED.equals(current.getStatus()) && !STATUS_ACTIVE.equals(current.getStatus()))
        {
            throw new ServiceException("只有未开始或进行中的券可以停发", HttpStatus.BAD_REQUEST);
        }
        String normalized = optionalReason(reason);
        ensureBaseline(current, operatorId);
        if (smsCouponMapper.updateCouponStatusCas(couponId, Arrays.asList(STATUS_NOT_STARTED, STATUS_ACTIVE),
                STATUS_STOPPED, current.getRuleVersion()) != 1)
        {
            throw conflict(current.getRuleVersion());
        }
        SmsCoupon after = smsCouponMapper.selectCouponRuleStateForUpdate(couponId);
        SmsCouponRuleVersion row = SmsCouponRuleVersion.of(after, after.getRuleVersion(),
                SmsCouponRuleVersion.STOP_ISSUE);
        row.setChangedFields("status");
        row.setReason(normalized);
        row.setOperatorId(operatorId);
        insertVersion(row);
        return row;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> invalidate(Long couponId, Integer expectedVersion, String reason, Long operatorId)
    {
        String normalized = reason == null ? "" : reason.trim();
        if (normalized.isEmpty())
        {
            throw new ServiceException("平台失效必须填写失效原因", HttpStatus.BAD_REQUEST);
        }
        if (normalized.length() > REASON_MAX)
        {
            throw new ServiceException("失效原因不能超过 " + REASON_MAX + " 字", HttpStatus.BAD_REQUEST);
        }
        SmsCoupon current = lockCurrent(couponId, expectedVersion);
        if (STATUS_INVALIDATED.equals(current.getStatus()))
        {
            throw new ServiceException("该券已是平台失效状态", HttpStatus.BAD_REQUEST);
        }
        ensureBaseline(current, operatorId);
        if (smsCouponMapper.updateCouponStatusCas(couponId,
                Arrays.asList(STATUS_NOT_STARTED, STATUS_ACTIVE, STATUS_ENDED, STATUS_STOPPED),
                STATUS_INVALIDATED, current.getRuleVersion()) != 1)
        {
            throw conflict(current.getRuleVersion());
        }
        Date now = new Date();
        // 券行锁已持有:新领取被挡在锁外,此刻读到的未使用持券就是本次失效的完整影响面。
        int affected = smsCouponHistoryMapper.countUnusedHoldersByCouponId(couponId, now);
        List<SmsCouponHistory> holders = smsCouponHistoryMapper.selectUnusedHoldersByCouponId(couponId, now,
                HOLDER_LIST_LIMIT);
        SmsCoupon after = smsCouponMapper.selectCouponRuleStateForUpdate(couponId);
        SmsCouponRuleVersion row = SmsCouponRuleVersion.of(after, after.getRuleVersion(),
                SmsCouponRuleVersion.PLATFORM_INVALIDATE);
        row.setChangedFields("status");
        row.setReason(normalized);
        row.setAffectedHolderCount(affected);
        row.setOperatorId(operatorId);
        insertVersion(row);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", row);
        result.put("affectedHolderCount", affected);
        result.put("holders", holderRows(holders));
        result.put("holdersTruncated", affected > holders.size());
        return result;
    }

    @Override
    public List<SmsCouponRuleVersion> listVersions(Long couponId)
    {
        if (couponId == null) return Collections.emptyList();
        List<SmsCouponRuleVersion> rows = ruleVersionMapper.selectByCouponId(couponId);
        return rows == null ? Collections.<SmsCouponRuleVersion>emptyList() : rows;
    }

    @Override
    public Map<String, Object> affectedHolders(Long couponId)
    {
        Date now = new Date();
        int total = smsCouponHistoryMapper.countUnusedHoldersByCouponId(couponId, now);
        List<SmsCouponHistory> holders = smsCouponHistoryMapper.selectUnusedHoldersByCouponId(couponId, now,
                HOLDER_LIST_LIMIT);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("latestInvalidation", ruleVersionMapper.selectLatestInvalidation(couponId));
        result.put("affectedHolderCount", total);
        result.put("holders", holderRows(holders));
        result.put("holdersTruncated", total > holders.size());
        return result;
    }

    // ------------------------------------------------------------------ 纯函数(单测友好)

    /** 返回第一条不合法原因;合法返回 null。比对的是「变更后的有效规则」(未提交的字段取当前值)。 */
    static String validate(SmsCoupon current, CouponRuleChangeDTO change)
    {
        if (!change.getRejectedFields().isEmpty())
        {
            return "以下字段不允许在规则变更中修改：" + String.join("、", change.getRejectedFields());
        }
        if (isDeleted(current))
        {
            return "优惠券已删除";
        }
        if (STATUS_INVALIDATED.equals(current.getStatus()))
        {
            return "已平台失效的券不能再修改规则";
        }
        if (change.getName() != null)
        {
            String name = change.getName().trim();
            if (name.isEmpty()) return "优惠券名称不能为空";
            if (name.length() > 80) return "优惠券名称不能超过 80 字";
        }
        if (change.getDescription() != null && change.getDescription().length() > 500)
        {
            return "说明不能超过 500 字";
        }
        if (change.getAmount() != null)
        {
            BigDecimal amount = change.getAmount();
            if (amount.signum() <= 0) return "面额必须大于 0";
            if (amount.stripTrailingZeros().scale() > 2) return "面额最多两位小数";
            if (amount.compareTo(MAX_AMOUNT) > 0) return "面额不能超过 " + MAX_AMOUNT.toPlainString();
        }
        if (change.getMinPoint() != null)
        {
            BigDecimal minPoint = change.getMinPoint();
            if (minPoint.signum() < 0) return "使用门槛不能为负数";
            if (minPoint.stripTrailingZeros().scale() > 2) return "使用门槛最多两位小数";
            if (minPoint.compareTo(MAX_MIN_POINT) > 0) return "使用门槛不能超过 " + MAX_MIN_POINT.toPlainString();
        }
        Date start = change.getStartTime() != null ? change.getStartTime() : current.getStartTime();
        Date end = change.getEndTime() != null ? change.getEndTime() : current.getEndTime();
        if (start != null && end != null && !end.after(start))
        {
            return "结束时间必须晚于开始时间";
        }
        Date enable = change.getEnableTime() != null ? change.getEnableTime() : current.getEnableTime();
        if (enable != null && end != null && enable.after(end))
        {
            return "可领取日期不能晚于结束时间";
        }
        if (change.getPublishCount() != null)
        {
            long publish = change.getPublishCount();
            long received = current.getReceiveCount() == null ? 0L : current.getReceiveCount();
            if (publish < 0) return "发放总量不能为负数";
            if (publish > 0 && publish < received)
            {
                return "发放总量不能低于已领取数量（已领取 " + received + " 张）";
            }
        }
        return null;
    }

    /** 白名单字段中真正有变化的项:{field,label,before,after} */
    static List<Map<String, Object>> diff(SmsCoupon current, CouponRuleChangeDTO change)
    {
        List<Map<String, Object>> changes = new ArrayList<>();
        if (change.getName() != null && !change.getName().trim().equals(current.getName()))
            changes.add(item("name", "优惠券名称", current.getName(), change.getName().trim()));
        if (change.getDescription() != null && !change.getDescription().equals(current.getDescription()))
            changes.add(item("description", "说明", current.getDescription(), change.getDescription()));
        if (change.getAmount() != null && !sameDecimal(change.getAmount(), current.getAmount()))
            changes.add(item("amount", "面额 / 折扣", current.getAmount(), change.getAmount()));
        if (change.getMinPoint() != null && !sameDecimal(change.getMinPoint(), current.getMinPoint()))
            changes.add(item("minPoint", "使用门槛", current.getMinPoint(), change.getMinPoint()));
        if (change.getStartTime() != null && !sameTime(change.getStartTime(), current.getStartTime()))
            changes.add(item("startTime", "开始时间", current.getStartTime(), change.getStartTime()));
        if (change.getEndTime() != null && !sameTime(change.getEndTime(), current.getEndTime()))
            changes.add(item("endTime", "结束时间", current.getEndTime(), change.getEndTime()));
        if (change.getEnableTime() != null && !sameTime(change.getEnableTime(), current.getEnableTime()))
            changes.add(item("enableTime", "可领取日期", current.getEnableTime(), change.getEnableTime()));
        if (change.getPublishCount() != null && !Objects.equals(change.getPublishCount(), current.getPublishCount()))
            changes.add(item("publishCount", "发放总量", current.getPublishCount(), change.getPublishCount()));
        return changes;
    }

    // ------------------------------------------------------------------ 内部

    private SmsCoupon lockCurrent(Long couponId, Integer expectedVersion)
    {
        if (couponId == null)
        {
            throw new ServiceException("优惠券不存在", HttpStatus.BAD_REQUEST);
        }
        SmsCoupon current = smsCouponMapper.selectCouponRuleStateForUpdate(couponId);
        if (current == null || isDeleted(current))
        {
            throw new ServiceException("优惠券不存在", HttpStatus.BAD_REQUEST);
        }
        if (expectedVersion == null || !expectedVersion.equals(current.getRuleVersion()))
        {
            throw conflict(current.getRuleVersion());
        }
        return current;
    }

    private ServiceException conflict(Integer currentVersion)
    {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("errorCode", "COUPON_RULE_VERSION_CONFLICT");
        data.put("currentVersion", currentVersion);
        return new ServiceException("优惠券规则已被他人修改（当前版本 v" + currentVersion + "），请刷新后重试",
                HttpStatus.CONFLICT).setData(data);
    }

    /** 首次治理动作前补记原规则,让「改动前的券读旧规则」有据可查。 */
    private void ensureBaseline(SmsCoupon current, Long operatorId)
    {
        if (ruleVersionMapper.countByCouponAndVersion(current.getId(), current.getRuleVersion()) > 0)
        {
            return;
        }
        SmsCouponRuleVersion baseline = SmsCouponRuleVersion.of(current, current.getRuleVersion(),
                SmsCouponRuleVersion.BASELINE);
        baseline.setOperatorId(operatorId);
        insertVersion(baseline);
    }

    private void insertVersion(SmsCouponRuleVersion row)
    {
        row.setCreateTime(new Date());
        if (ruleVersionMapper.insertRuleVersion(row) != 1)
        {
            throw new IllegalStateException("优惠券规则版本写入失败");
        }
    }

    private Map<String, Object> claimedSummary(Long couponId)
    {
        long unused = 0, used = 0, expired = 0, legacy = 0;
        List<Map<String, Object>> rows = smsCouponHistoryMapper.countClaimsByUseStatus(couponId);
        if (rows != null)
        {
            for (Map<String, Object> row : rows)
            {
                Object status = row.get("useStatus");
                long total = row.get("total") == null ? 0L : ((Number) row.get("total")).longValue();
                long legacyRows = row.get("legacyTotal") == null ? 0L : ((Number) row.get("legacyTotal")).longValue();
                legacy += legacyRows;
                int code = status == null ? -1 : ((Number) status).intValue();
                if (code == 0) unused += total;
                else if (code == 1) used += total;
                else if (code == 2) expired += total;
            }
        }
        Map<String, Object> claimed = new LinkedHashMap<>();
        claimed.put("total", unused + used + expired);
        claimed.put("unused", unused);
        claimed.put("used", used);
        claimed.put("expired", expired);
        // 迁移前领取的券没有冻结面额:读侧按最早的规则版本(BASELINE)回放,不跟随本次修改。
        claimed.put("legacyWithoutSnapshot", legacy);
        claimed.put("keepsClaimRule", true);
        return claimed;
    }

    private static List<Map<String, Object>> holderRows(List<SmsCouponHistory> holders)
    {
        List<Map<String, Object>> rows = new ArrayList<>();
        if (holders == null) return rows;
        for (SmsCouponHistory h : holders)
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("historyId", h.getId());
            row.put("memberId", h.getMemberId());
            row.put("couponCode", h.getCouponCode());
            row.put("getTime", h.getGetTime());
            row.put("endTime", h.getEndTime());
            rows.add(row);
        }
        return rows;
    }

    private static void applyField(SmsCoupon update, CouponRuleChangeDTO change, String field)
    {
        switch (field)
        {
            case "name": update.setName(change.getName().trim()); break;
            case "description": update.setDescription(change.getDescription()); break;
            case "amount": update.setAmount(change.getAmount()); break;
            case "minPoint": update.setMinPoint(change.getMinPoint()); break;
            case "startTime": update.setStartTime(change.getStartTime()); break;
            case "endTime": update.setEndTime(change.getEndTime()); break;
            case "enableTime": update.setEnableTime(change.getEnableTime()); break;
            case "publishCount": update.setPublishCount(change.getPublishCount()); break;
            default: throw new IllegalArgumentException("unknown field " + field);
        }
    }

    private static String optionalReason(String reason)
    {
        if (reason == null || reason.trim().isEmpty()) return null;
        String normalized = reason.trim();
        if (normalized.length() > REASON_MAX)
        {
            throw new ServiceException("原因不能超过 " + REASON_MAX + " 字", HttpStatus.BAD_REQUEST);
        }
        return normalized;
    }

    private static boolean isIssuable(SmsCoupon coupon)
    {
        return STATUS_NOT_STARTED.equals(coupon.getStatus()) || STATUS_ACTIVE.equals(coupon.getStatus());
    }

    private static boolean isDeleted(SmsCoupon coupon)
    {
        return coupon.getDelFlag() != null && coupon.getDelFlag() != 0;
    }

    private static boolean sameDecimal(BigDecimal a, BigDecimal b)
    {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private static boolean sameTime(Date a, Date b)
    {
        return a == null ? b == null : b != null && a.getTime() == b.getTime();
    }

    private static Map<String, Object> item(String field, String label, Object before, Object after)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("field", field);
        item.put("label", label);
        item.put("before", before);
        item.put("after", after);
        return item;
    }
}
