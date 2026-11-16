package com.chengyinhub.business.creatorearnings;

import com.chengyinhub.business.domain.UmsMemberPointsDetail;
import com.chengyinhub.business.mapper.CreatorEarningMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.IUmsMemberPointsDetailService;
import com.chengyinhub.business.service.IUmsMemberService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * R12 creator earnings (GAM-10, MON-06).
 *
 * <p>Accrual reads source facts that other features already record — library template adoptions, workshop
 * installs, verified node check-ins and paid or refunded workshop orders — and appends one ledger row per business
 * event. Each row is written in its own transaction together with the creator's points, so a crash or a replay never
 * pays twice (unique event_key on the ledger, unique reward_business_key on the points ledger).
 *
 * <p>Money: a sale records the intended creator share only. Nothing in this service moves money.
 */
@Service
public class CreatorEarningsService {

    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    static final int BATCH = 500;
    static final int MAX_BATCHES = 40;

    private final CreatorEarningMapper mapper;
    private final UmsMemberMapper members;
    private final IUmsMemberService memberService;
    private final IUmsMemberPointsDetailService pointsDetails;
    private final TransactionTemplate tx;
    private final CreatorEarningsPolicy policy;
    private final boolean moneyOn;

    /** S3a: the template take setting; absent (tests) = the creator share from configuration. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.business.merchantplans.TakeRateService takeRates;

    /**
     * W7 MERCHANT-3: the money state is PENDING_PAYOUT only while the property is on AND the Q5 {@code payout} area is
     * open. No gate (hand-built in tests) reads closed.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.chengyinhub.business.moneyswitch.MoneyGate moneyGate;

    void setMoneyGate(com.chengyinhub.business.moneyswitch.MoneyGate moneyGate) { this.moneyGate = moneyGate; }

    public CreatorEarningsService(CreatorEarningMapper mapper, UmsMemberMapper members, IUmsMemberService memberService,
                                  IUmsMemberPointsDetailService pointsDetails, PlatformTransactionManager transactions,
                                  @Value("${chengyin.creator-earnings.adopt-points:5}") int adoptPoints,
                                  @Value("${chengyin.creator-earnings.install-points:5}") int installPoints,
                                  @Value("${chengyin.creator-earnings.play-points:1}") int playPoints,
                                  @Value("${chengyin.creator-earnings.daily-play-cap:50}") int dailyPlayCap,
                                  @Value("${chengyin.creator-earnings.daily-play-per-player:1}") int dailyPlayPerPlayer,
                                  @Value("${chengyin.creator-earnings.sale-share-bps:7000}") int saleShareBps,
                                  @Value("${chengyin.release.money-payments:false}") boolean moneyOn) {
        this.mapper = mapper; this.members = members; this.memberService = memberService; this.pointsDetails = pointsDetails;
        this.tx = new TransactionTemplate(transactions);
        this.policy = new CreatorEarningsPolicy(new CreatorEarningsPolicy.Config(adoptPoints, installPoints, playPoints,
                dailyPlayCap, dailyPlayPerPlayer, saleShareBps));
        this.moneyOn = moneyOn;
    }

    public CreatorEarningsPolicy policy() { return policy; }

    public boolean moneyOn() {
        return moneyOn && moneyGate != null
                && moneyGate.isOpen(com.chengyinhub.business.moneyswitch.MoneyArea.PAYOUT);
    }

    // ------------------------------------------------------------------ accrual

    /** Appends every not-yet-recorded event that happened on {@code day} (Asia/Shanghai). Safe to run again. */
    public Map<String, Object> accrueDay(LocalDate day) {
        if (day == null) throw new IllegalArgumentException("day required");
        Date from = Date.from(day.atStartOfDay(ZONE).toInstant());
        Date to = Date.from(day.plusDays(1).atStartOfDay(ZONE).toInstant());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("day", day.toString());
        summary.put("adopt", drain(() -> mapper.selectAdoptionCandidates(from, to, BATCH), this::adopt));
        summary.put("install", drain(() -> mapper.selectInstallCandidates(from, to, BATCH), this::install));
        summary.put("templatePlay", drain(() -> mapper.selectTemplatePlayCandidates(from, to, BATCH), r -> play(r, "TEMPLATE")));
        summary.put("workshopPlay", drain(() -> mapper.selectWorkshopPlayCandidates(from, to, BATCH), r -> play(r, "WORKSHOP")));
        summary.put("sale", drain(() -> mapper.selectSaleCandidates(from, to, BATCH), this::sale));
        summary.put("reversal", drain(() -> mapper.selectReversalCandidates(from, to, BATCH), this::reversal));
        summary.put("mirrored", mirror());
        summary.put("moneyState", CreatorEarningsPolicy.moneyState(moneyOn()));
        return summary;
    }

    /** Copies ledger rows of approved creators into creator_income_detail (idempotent). */
    public int mirror() {
        int total = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            Integer n = tx.execute(s -> mapper.mirrorIncomeDetail(BATCH));
            int written = n == null ? 0 : n;
            total += written;
            if (written < BATCH) break;
        }
        return total;
    }

    interface Source { List<Map<String, Object>> next(); }
    interface Step { boolean apply(Map<String, Object> row); }

    /** Processes batches until the source is empty or nothing new was written (rows the ledger declined). */
    static int drain(Source source, Step step) {
        int written = 0;
        for (int i = 0; i < MAX_BATCHES; i++) {
            List<Map<String, Object>> rows = source.next();
            if (rows == null || rows.isEmpty()) break;
            int before = written;
            for (Map<String, Object> row : rows) if (step.apply(row)) written++;
            if (written == before || rows.size() < BATCH) break;
        }
        return written;
    }

    boolean adopt(Map<String, Object> r) {
        long creator = num(r, "creatorId"), actor = num(r, "actorId");
        String item = text(r, "itemRef");
        return append(base(CreatorEarningsPolicy.ADOPT, "T" + item + ":" + actor, creator, actor, "TEMPLATE", item, r),
                policy.pointsFor(CreatorEarningsPolicy.ADOPT), false);
    }

    boolean install(Map<String, Object> r) {
        long creator = num(r, "creatorId"), actor = num(r, "actorId");
        String item = text(r, "itemRef");
        return append(base(CreatorEarningsPolicy.INSTALL, item + ":" + actor, creator, actor, "WORKSHOP", item, r),
                policy.pointsFor(CreatorEarningsPolicy.INSTALL), false);
    }

    boolean play(Map<String, Object> r, String kind) {
        long creator = num(r, "creatorId"), actor = num(r, "actorId");
        Map<String, Object> row = base(CreatorEarningsPolicy.PLAY, String.valueOf(num(r, "progressId")), creator, actor, kind,
                text(r, "itemRef"), r);
        return append(row, policy.pointsFor(CreatorEarningsPolicy.PLAY), true);
    }

    /** One SALE row for a paid workshop order (also called on grant by mod-commerce). Replay-safe. */
    public boolean sale(Map<String, Object> r) {
        long creator = num(r, "creatorId"), actor = num(r, "actorId");
        String orderNo = text(r, "orderNo");
        Map<String, Object> row = base(CreatorEarningsPolicy.SALE, orderNo, creator, actor, "WORKSHOP", text(r, "itemRef"), r);
        long gross = num(r, "grossMinor");
        row.put("grossMinor", gross);
        // S3a: the template take is a setting; none configured = the creator share from configuration (today).
        int shareBps = takeRates == null ? policy.config().saleShareBps()
                : takeRates.templateCreatorShareBps(policy.config().saleShareBps());
        row.put("shareBps", shareBps);
        row.put("creatorMinor", gross <= 0 ? 0L : Math.multiplyExact(gross, (long) shareBps) / 10000L);
        row.put("currency", Objects.toString(r.get("currency"), "CNY"));
        row.put("moneyState", CreatorEarningsPolicy.moneyState(moneyOn()));
        row.put("orderKey", CreatorEarningsPolicy.incomeOrderKey(orderNo));
        return append(row, 0, false);
    }

    /** One SALE_REVERSAL row for a refunded order whose SALE exists (also called by mod-commerce). Replay-safe. */
    public boolean reversal(Map<String, Object> r) {
        String orderNo = text(r, "orderNo");
        Map<String, Object> sale = mapper.selectSaleForOrder(CreatorEarningsPolicy.eventKey(CreatorEarningsPolicy.SALE, orderNo));
        if (sale == null) return false;
        long creator = num(sale, "creatorId");
        Map<String, Object> row = base(CreatorEarningsPolicy.SALE_REVERSAL, orderNo, creator, num(r, "actorId"),
                text(sale, "itemKind"), text(sale, "itemRef"), r);
        row.put("itemTitle", sale.get("itemTitle"));
        row.put("grossMinor", -num(sale, "grossMinor"));
        row.put("shareBps", (int) num(sale, "shareBps"));
        row.put("creatorMinor", -num(sale, "creatorMinor"));
        row.put("currency", sale.get("currency"));
        row.put("moneyState", CreatorEarningsPolicy.moneyState(moneyOn()));
        row.put("orderKey", sale.get("orderKey"));
        return append(row, 0, false);
    }

    private Map<String, Object> base(String type, String sourceRef, long creator, long actor, String kind, String item,
                                     Map<String, Object> source) {
        Map<String, Object> row = new HashMap<>();
        row.put("eventType", type);
        row.put("eventKey", CreatorEarningsPolicy.eventKey(type, sourceRef));
        row.put("sourceRef", sourceRef);
        row.put("creatorId", creator);
        row.put("actorId", actor);
        row.put("itemKind", kind);
        row.put("itemRef", item);
        String title = source.get("itemTitle") == null ? null : source.get("itemTitle").toString();
        row.put("itemTitle", title != null && title.length() > 255 ? title.substring(0, 255) : title);
        Date occur = date(source.get("occurTime"));
        row.put("occurTime", occur);
        // A DATE travels as a plain calendar day, independent of the JVM and database time zones.
        row.put("occurDate", java.sql.Date.valueOf(occur.toInstant().atZone(ZONE).toLocalDate()));
        row.put("grossMinor", 0L); row.put("shareBps", 0); row.put("creatorMinor", 0L);
        row.put("currency", null); row.put("moneyState", CreatorEarningsPolicy.MONEY_NONE); row.put("orderKey", null);
        return row;
    }

    /** One event, one transaction: lock the creator, re-check, append, credit points. */
    private boolean append(Map<String, Object> row, int points, boolean capped) {
        long creator = (Long) row.get("creatorId");
        Long actor = (Long) row.get("actorId");
        if (!CreatorEarningsPolicy.countable(creator, actor)) return false;
        Boolean done = tx.execute(s -> {
            // Locking the creator's member row serialises cap counting per creator across nodes.
            if (members.selectPointsForUpdate(creator) == null) return false;
            String key = (String) row.get("eventKey");
            if (mapper.countEvent(key) > 0) return false;
            int award = points;
            boolean over = false;
            if (capped) {
                Date day = (Date) row.get("occurDate");
                boolean within = policy.playWithinCap(mapper.countRewardedPlays(creator, day),
                        mapper.countRewardedPlayerItemPlays(creator, actor, (String) row.get("itemKind"), (String) row.get("itemRef"), day));
                if (!within) { award = 0; over = true; }
            }
            row.put("points", award);
            row.put("capped", over ? 1 : 0);
            if (mapper.insertLedger(row) != 1) return false;
            if (award > 0) credit(creator, award, key, (String) row.get("eventType"), (String) row.get("itemTitle"));
            return true;
        });
        return Boolean.TRUE.equals(done);
    }

    private void credit(long creator, int points, String eventKey, String type, String title) {
        Long currentRaw = members.selectPointsForUpdate(creator);
        long current = currentRaw == null ? 0L : currentRaw;
        if (!memberService.addPoints(creator, points)) throw new IllegalStateException("creator points credit failed");
        UmsMemberPointsDetail detail = new UmsMemberPointsDetail();
        detail.setMemberId(creator);
        detail.setCurrentPoints(current);
        detail.setAfterPoints(current + points);
        detail.setChangePoints((long) points);
        detail.setChangeType(1L);
        detail.setEventType(CreatorEarningsPolicy.POINTS_EVENT_TYPE);
        detail.setRewardBusinessKey(CreatorEarningsPolicy.pointsBusinessKey(eventKey));
        detail.setChangeReason(reason(type, title));
        detail.setDelFlag(0);
        if (pointsDetails.insertUmsMemberPointsDetail(detail) != 1) throw new IllegalStateException("creator points detail failed");
    }

    static String reason(String type, String title) {
        String what = switch (type) {
            case CreatorEarningsPolicy.ADOPT -> "创作收益：模板被领用";
            case CreatorEarningsPolicy.INSTALL -> "创作收益：工坊作品被安装";
            case CreatorEarningsPolicy.PLAY -> "创作收益：作品被游玩";
            default -> "创作收益";
        };
        if (title == null || title.isBlank()) return what;
        String t = title.length() > 40 ? title.substring(0, 40) : title;
        return what + "《" + t + "》";
    }

    // ------------------------------------------------------------------ creator reads

    public Map<String, Object> mine(long creator) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "creator-earnings-v1");
        result.put("creatorId", creator);
        Map<String, Object> s = mapper.selectSummary(creator);
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String k : List.of("points", "plays", "cappedPlays", "uses", "sales", "items"))
            summary.put(k, s == null ? 0L : num(s, k));
        result.put("summary", summary);
        List<Map<String, Object>> money = new ArrayList<>();
        for (Map<String, Object> m : mapper.selectMoneyIntents(creator)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("currency", m.get("currency")); row.put("moneyState", m.get("moneyState"));
            row.put("creatorMinor", num(m, "creatorMinor"));
            money.add(row);
        }
        result.put("money", money);
        result.put("moneyOn", moneyOn());
        List<Map<String, Object>> items = new ArrayList<>();
        for (Map<String, Object> i : mapper.selectItems(creator, 100)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("itemKind", i.get("itemKind")); row.put("itemRef", i.get("itemRef"));
            row.put("title", Objects.toString(i.get("title"), ""));
            row.put("plays", num(i, "plays")); row.put("uses", num(i, "uses")); row.put("sales", num(i, "sales"));
            row.put("points", num(i, "points")); row.put("lastAt", iso(i.get("lastAt")));
            items.add(row);
        }
        result.put("items", items);
        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("adoptPoints", policy.config().adoptPoints());
        rules.put("installPoints", policy.config().installPoints());
        rules.put("playPoints", policy.config().playPoints());
        rules.put("dailyPlayCap", policy.config().dailyPlayCap());
        rules.put("dailyPlayPerPlayer", policy.config().dailyPlayPerPlayer());
        result.put("rules", rules);
        return result;
    }

    public Map<String, Object> ledger(long creator, Long before) {
        if (before != null && before <= 0) throw new IllegalArgumentException("INVALID_CURSOR");
        List<Map<String, Object>> rows = mapper.selectLedgerPage(creator, before, 51);
        boolean more = rows.size() > 50;
        List<Map<String, Object>> entries = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(50, rows.size()))) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", num(r, "id")); e.put("eventType", r.get("eventType"));
            e.put("itemKind", r.get("itemKind")); e.put("itemRef", r.get("itemRef"));
            e.put("title", Objects.toString(r.get("itemTitle"), ""));
            e.put("points", num(r, "points")); e.put("capped", num(r, "capped") == 1);
            e.put("creatorMinor", num(r, "creatorMinor")); e.put("currency", r.get("currency"));
            e.put("moneyState", r.get("moneyState")); e.put("occurredAt", iso(r.get("occurTime")));
            entries.add(e);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "creator-earnings-ledger-v1");
        result.put("creatorId", creator);
        result.put("entries", entries);
        if (more) result.put("nextBefore", num(rows.get(49), "id"));
        return result;
    }

    // ------------------------------------------------------------------ /api/mod-settlement income facts

    public Map<String, Object> incomePage(long creator, String before) {
        if (before != null && !CreatorEarningsPolicy.isIncomeOrderKey(before)) throw new IllegalArgumentException("INVALID_CURSOR");
        List<Map<String, Object>> rows = mapper.selectIncomeFacts(creator, before, 51);
        boolean more = rows.size() > 50;
        List<Map<String, Object>> facts = new ArrayList<>();
        for (Map<String, Object> r : rows.subList(0, Math.min(50, rows.size()))) {
            Map<String, Object> fact = fact(creator, text(r, "orderKey"), text(r, "itemRef"), text(r, "currency"),
                    num(r, "creatorAmount"), num(r, "refundedAmount"), num(r, "revision"), r.get("paidAt"));
            if (fact != null) facts.add(fact);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "mod-creator-income-page-v1");
        result.put("creatorId", creator);
        result.put("facts", facts);
        if (more && !facts.isEmpty()) result.put("nextCursor", facts.get(facts.size() - 1).get("orderKey"));
        return result;
    }

    /** Null when the order key is not this creator's. */
    public Map<String, Object> incomeDetail(long creator, String orderKey) {
        List<Map<String, Object>> revisions = revisions(creator, orderKey);
        if (revisions.isEmpty()) return null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "mod-creator-income-detail-v1");
        result.put("creatorId", creator);
        result.put("current", revisions.get(revisions.size() - 1));
        return result;
    }

    public Map<String, Object> incomeRevisions(long creator, String orderKey, long asOf, Long before) {
        List<Map<String, Object>> all = revisions(creator, orderKey);
        if (all.isEmpty() || asOf < 1 || asOf > all.size() || (before != null && (before < 1 || before > asOf))) return null;
        List<Map<String, Object>> picked = new ArrayList<>();
        for (int rev = (int) (before == null ? asOf : before - 1); rev >= 1 && picked.size() < 50; rev--)
            picked.add(all.get(rev - 1));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("schema", "mod-creator-income-revisions-v1");
        result.put("creatorId", creator);
        result.put("orderKey", orderKey);
        result.put("asOfRevision", asOf);
        result.put("revisions", picked);
        long last = picked.isEmpty() ? 0 : ((Number) picked.get(picked.size() - 1).get("revision")).longValue();
        if (last > 1) result.put("nextBeforeRevision", last);
        return result;
    }

    /** Revision n is the fact after the first n ledger rows of the order (sale, then any reversal). */
    List<Map<String, Object>> revisions(long creator, String orderKey) {
        if (!CreatorEarningsPolicy.isIncomeOrderKey(orderKey)) return List.of();
        List<Map<String, Object>> rows = mapper.selectIncomeRows(creator, orderKey);
        List<Map<String, Object>> out = new ArrayList<>();
        long amount = 0, refunded = 0;
        Object paidAt = null;
        String item = null, currency = null;
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> r = rows.get(i);
            if (CreatorEarningsPolicy.SALE.equals(r.get("eventType"))) {
                amount += num(r, "creatorMinor"); paidAt = r.get("occurTime");
                item = text(r, "itemRef"); currency = text(r, "currency");
            } else {
                refunded -= num(r, "creatorMinor");
            }
            if (paidAt == null) return List.of();
            Map<String, Object> fact = fact(creator, orderKey, item, currency, amount, refunded, i + 1, paidAt);
            if (fact == null) return List.of();
            out.add(fact);
        }
        return out;
    }

    /** Shapes one fact as the app's ModCreatorIncomeFact; rows the app would reject are left out, not bent. */
    static Map<String, Object> fact(long creator, String orderKey, String item, String currency, long amount, long refunded,
                                    long revision, Object paidAt) {
        if (!CreatorEarningsPolicy.isIncomeOrderKey(orderKey) || item == null || !item.matches("[A-Za-z0-9_-]{1,128}")
                || !("CNY".equals(currency) || "USD".equals(currency)) || amount < 0 || amount > Integer.MAX_VALUE
                || refunded < 0 || revision < 1 || paidAt == null) return null;
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("source", "MOD_PURCHASE");
        f.put("orderKey", orderKey);
        f.put("creatorId", creator);
        f.put("modReleaseId", item);
        f.put("currency", currency);
        f.put("creatorAmount", amount);
        f.put("refundedAmount", Math.min(refunded, amount));
        f.put("chargebackAmount", 0L);
        f.put("revision", revision);
        f.put("paidAt", iso(paidAt));
        f.put("disputed", false);
        return f;
    }

    // ------------------------------------------------------------------ admin

    public List<Map<String, Object>> report(Long creatorId, LocalDate from, LocalDate toInclusive) {
        Date f = from == null ? null : Date.from(from.atStartOfDay(ZONE).toInstant());
        Date t = toInclusive == null ? null : Date.from(toInclusive.plusDays(1).atStartOfDay(ZONE).toInstant());
        return mapper.selectCreatorReport(creatorId, f, t);
    }

    public List<Map<String, Object>> adminLedger(Long creatorId, String eventType) {
        return mapper.selectAdminLedger(creatorId, eventType);
    }

    // ------------------------------------------------------------------ helpers

    static long num(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        if (v instanceof Number n) return n.longValue();
        if (v == null) return 0L;
        return Long.parseLong(v.toString());
    }

    static String text(Map<String, Object> row, String key) {
        Object v = row == null ? null : row.get(key);
        return v == null ? null : v.toString();
    }

    static Date date(Object value) {
        if (value instanceof Date d) return d;
        if (value instanceof LocalDateTime t) return Date.from(t.atZone(ZONE).toInstant());
        if (value instanceof java.time.OffsetDateTime t) return Date.from(t.toInstant());
        throw new IllegalStateException("occur time missing");
    }

    static String iso(Object value) {
        if (value == null) return null;
        return date(value).toInstant().toString();
    }
}
