package com.chengyinhub.business.service.rewardpledge;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Policy;
import com.chengyinhub.business.domain.rewardpledge.RewardPledgeRows.Stock;
import com.chengyinhub.business.mapper.rewardpledge.RewardPledgeAdminMapper;
import com.chengyinhub.business.mapper.rewardpledge.RewardPledgeMapper;
import com.chengyinhub.business.opsconsole.OpsConsoleAudit;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;

/**
 * R10 BE-22 奖励承诺（非现金）的政策行编辑与平台审核队列。
 *
 * <p>承诺只承载券类、无现金价值的奖励；现金类在 {@code RewardPledgeService#draft} 就被 {@link CashPayoutSwitch} 拦住，
 * 本控制台不新增任何付款路径。平台审核默认关闭：只有 {@code PLATFORM_REVIEW} 政策行为 OPEN 时，后台才能通过或驳回，
 * 通过还要求所选规则版本的 {@code RULE} 政策行为 OPEN（与 App 内审核同一闸）。所有写操作写审核备注与平台审计。
 */
@Service
public class RewardPledgeAdminService
{
    public static final String PLATFORM_REVIEW = "PLATFORM_REVIEW";
    /** RewardPledgeService#open 实际读的政策码 + 本控制台的平台审核码；不认识的码不让写，避免写进去没人读的行。 */
    public static final Set<String> POLICY_CODES = Set.of("REVIEWER", "RULE", "CLAIM", "MINOR", "STORE_HOLD",
            "RETURN_TO_POOL", "COMPENSATION", "REVERSE", PLATFORM_REVIEW);
    /** 需要按规则版本单独开关的码；其余码的 rule_version 固定为空串。 */
    static final Set<String> VERSIONED = Set.of("RULE", "CLAIM", "MINOR");
    static final Set<String> QUEUE_STATES = Set.of("CONFIRMED", "FROZEN", "REJECTED", "WITHDRAWN");
    private static final Pattern RULE_VERSION = Pattern.compile("^[A-Za-z0-9._:\\-]{1,128}$");
    private static final Pattern ID = Pattern.compile("^[A-Za-z0-9_\\-]{1,64}$");

    @Autowired
    private RewardPledgeAdminMapper admin;
    @Autowired
    private RewardPledgeMapper ledger;
    @Autowired
    private OpsConsoleAudit audit;
    @Autowired
    private CashPayoutSwitch cash;

    public RewardPledgeAdminService() {}

    RewardPledgeAdminService(RewardPledgeAdminMapper admin, RewardPledgeMapper ledger, OpsConsoleAudit audit, CashPayoutSwitch cash)
    {
        this.admin = admin;
        this.ledger = ledger;
        this.audit = audit;
        this.cash = cash;
    }

    public Map<String, Object> policies()
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", admin.listPolicies());
        body.put("codes", POLICY_CODES.stream().sorted().toList());
        body.put("versionedCodes", VERSIONED.stream().sorted().toList());
        body.put("platformReviewOpen", open(PLATFORM_REVIEW, ""));
        body.put("cashPayoutOpen", cash != null && cash.open());
        return body;
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> savePolicy(Long sysUserId, String code, String ruleVersion, String decision, Long actorMemberId, String note)
    {
        code = code == null ? "" : code.trim();
        if (!POLICY_CODES.contains(code)) throw bad("不认识的政策码");
        String version = ruleVersion == null ? "" : ruleVersion.trim();
        if (VERSIONED.contains(code))
        {
            if (!RULE_VERSION.matcher(version).matches()) throw bad("该政策需要填写规则版本（字母、数字、. _ : -，128 位内）");
        }
        else if (!version.isEmpty()) throw bad("该政策不分规则版本，请留空");
        if (!"OPEN".equals(decision) && !"CLOSED".equals(decision)) throw bad("决定只能是开启或关闭");
        if ("REVIEWER".equals(code) && "OPEN".equals(decision) && (actorMemberId == null || actorMemberId <= 0))
            throw bad("开启 App 内审核人时必须填写审核人会员 ID");
        if (!"REVIEWER".equals(code)) actorMemberId = null;
        if (note == null || note.isBlank() || note.length() > 500) throw bad("请填写 500 字内的变更说明");
        Policy before = ledger.findPolicy(code, version);
        admin.upsertPolicy(code, version, decision, actorMemberId);
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("policyCode", code);
        after.put("ruleVersion", version);
        after.put("decision", decision);
        after.put("actorMemberId", actorMemberId);
        audit.record(sysUserId, "REWARD_POLICY_SAVE", "REWARD_POLICY", 0L, before == null ? null : policyView(before), after, note.trim());
        return after;
    }

    public Map<String, Object> queue(String state, Long storeId)
    {
        String s = state == null || state.isBlank() ? "CONFIRMED" : state.trim();
        if (!QUEUE_STATES.contains(s)) throw bad("不认识的审核状态");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", admin.queue(s, storeId));
        body.put("platformReviewOpen", open(PLATFORM_REVIEW, ""));
        return body;
    }

    public List<Map<String, Object>> notes(String pledgeId)
    {
        requireId(pledgeId);
        return admin.notes(pledgeId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> approve(Long sysUserId, String versionId, String ruleVersion, String note, long now)
    {
        requireId(versionId);
        if (ruleVersion == null || !RULE_VERSION.matcher(ruleVersion.trim()).matches()) throw bad("请选择规则版本");
        String rv = ruleVersion.trim();
        requireNote(note, false);
        if (!open(PLATFORM_REVIEW, "")) throw closed("平台审核尚未开启（PLATFORM_REVIEW 政策行未开启）");
        if (!open("RULE", rv)) throw closed("该规则版本尚未开启（RULE 政策行未开启）");
        Map<String, Object> v = lockCurrent(versionId);
        String hash = sha(v.get("payloadHash") + ":platform:" + sysUserId);
        if (admin.freezeByPlatform(versionId, hash, rv, now) != 1) throw conflict();
        Stock stock = new Stock();
        stock.versionId = versionId;
        stock.quantity = ((Number) v.get("quantity")).intValue();
        stock.available = stock.quantity;
        ledger.insertStock(stock);
        String pledgeId = (String) v.get("pledgeId");
        ledger.updatePledgeState(pledgeId, "FROZEN");
        return decided(sysUserId, v, "APPROVE", "FROZEN", rv, note);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> reject(Long sysUserId, String versionId, String note)
    {
        requireId(versionId);
        requireNote(note, true);
        if (!open(PLATFORM_REVIEW, "")) throw closed("平台审核尚未开启（PLATFORM_REVIEW 政策行未开启）");
        Map<String, Object> v = lockCurrent(versionId);
        if (admin.rejectVersion(versionId) != 1) throw conflict();
        ledger.updatePledgeState((String) v.get("pledgeId"), "REJECTED");
        return decided(sysUserId, v, "REJECT", "REJECTED", null, note);
    }

    /** 只加备注，不改状态（任何状态都可以记）。 */
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> note(Long sysUserId, String versionId, String note)
    {
        requireId(versionId);
        requireNote(note, true);
        Map<String, Object> v = admin.lockVersion(versionId);
        if (v == null) throw new ServiceException("承诺版本不存在", HttpStatus.NOT_FOUND);
        return decided(sysUserId, v, "NOTE", (String) v.get("state"), null, note);
    }

    private Map<String, Object> lockCurrent(String versionId)
    {
        Map<String, Object> v = admin.lockVersion(versionId);
        if (v == null) throw new ServiceException("承诺版本不存在", HttpStatus.NOT_FOUND);
        if (!"CONFIRMED".equals(v.get("state"))) throw conflict();
        if (!versionId.equals(admin.latestVersionId((String) v.get("pledgeId")))) throw conflict();
        return v;
    }

    private Map<String, Object> decided(Long sysUserId, Map<String, Object> v, String decision, String state, String ruleVersion, String note)
    {
        String text = note == null ? null : note.trim();
        String versionId = (String) v.get("versionId");
        String pledgeId = (String) v.get("pledgeId");
        admin.insertNote(versionId, pledgeId, decision, text == null || text.isEmpty() ? null : text, sysUserId);
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("versionId", versionId);
        before.put("pledgeId", pledgeId);
        before.put("state", v.get("state"));
        Map<String, Object> after = new LinkedHashMap<>(before);
        after.put("state", state);
        if (ruleVersion != null) after.put("ruleVersion", ruleVersion);
        audit.record(sysUserId, "REWARD_PLEDGE_" + decision, "REWARD_PLEDGE_VERSION", 0L,
                "NOTE".equals(decision) ? null : before, after, text);
        return after;
    }

    private boolean open(String code, String ruleVersion)
    {
        Policy policy = ledger.findPolicy(code, ruleVersion);
        return policy != null && "OPEN".equals(policy.decision);
    }

    private static Map<String, Object> policyView(Policy p)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("policyCode", p.policyCode);
        m.put("ruleVersion", p.ruleVersion);
        m.put("decision", p.decision);
        m.put("actorMemberId", p.actorMemberId);
        return m;
    }

    private static void requireId(String id)
    {
        if (id == null || !ID.matcher(id).matches()) throw bad("编号无效");
    }

    private static void requireNote(String note, boolean required)
    {
        if (note != null && note.length() > 500) throw bad("备注最多 500 字");
        if (required && (note == null || note.isBlank())) throw bad("请填写备注");
    }

    private static String sha(String text)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static ServiceException bad(String m) { return new ServiceException(m, HttpStatus.BAD_REQUEST); }
    private static ServiceException closed(String m) { return new ServiceException(m, HttpStatus.FORBIDDEN); }
    private static ServiceException conflict() { return new ServiceException("该版本状态已变化或已有新版本，请刷新", HttpStatus.CONFLICT); }
}
