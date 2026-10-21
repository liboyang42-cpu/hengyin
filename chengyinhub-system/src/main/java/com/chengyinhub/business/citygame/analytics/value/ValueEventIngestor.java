package com.chengyinhub.business.citygame.analytics.value;

import com.chengyinhub.business.cityboard.CityBoardDraftDocument;
import com.chengyinhub.business.cityboard.CityBoardHashes;
import com.chengyinhub.business.cityboard.CityBoardReleaseRow;
import com.chengyinhub.business.citygame.analytics.value.mapper.ValueEventMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * 可信服务端事件摄入。去重键在写入前算好；相同身份相同内容回放，内容冲突保留原行。
 * 进程内序号只用于本进程。表迁到数据库后改用库序列，唯一键才是幂等边界。
 */
@Component
public class ValueEventIngestor {
    public static final String FACT_SOURCE = "city-release-fact";
    static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final Pattern CURRENCY = Pattern.compile("\\A[A-Z]{3}\\z");
    private static final Set<String> BANNED = Set.of(
            "sample", "samples", "client", "fixture", "demo", "hardcoded", "anonymous");

    private final ValueEventMapper mapper;
    private final Set<String> trusted;
    private final Clock clock;
    private final AtomicLong ids = new AtomicLong(8_000_000L);

    @Autowired
    public ValueEventIngestor(ValueEventMapper mapper) {
        this(mapper, Set.of(FACT_SOURCE), Clock.systemUTC());
    }

    public ValueEventIngestor(ValueEventMapper mapper, Set<String> trusted, Clock clock) {
        this.mapper = mapper;
        this.trusted = Set.copyOf(trusted);
        this.clock = clock;
    }

    public Accept acceptBehavior(ValueContracts.Ingest ingest, CityBoardReleaseRow release) {
        Accept rejected = validateBehavior(ingest, release);
        if (rejected != null) {
            return rejected;
        }
        ValueEventRow row = new ValueEventRow();
        row.setSourceId(ingest.sourceId.trim());
        row.setKind(ingest.kind);
        row.setRegionId(release.getRegionId());
        row.setBoardId(release.getBoardId());
        row.setSeasonId(release.getSeasonId());
        row.setRulesReleaseId(release.getRulesReleaseId());
        row.setMemberId(ingest.memberId);
        row.setVisitId(ingest.visitId.trim());
        row.setServiceId(blank(ingest.serviceId) ? null : ingest.serviceId.trim());
        row.setActionCode(blank(ingest.actionCode) ? null : ValueKinds.norm(ingest.actionCode));
        row.setGoalId(blank(ingest.goalId) ? null : ingest.goalId.trim());
        row.setReasonCode(blank(ingest.reasonCode) ? null : ingest.reasonCode.trim());
        row.setAmount(ingest.amount);
        row.setUnit(ingest.unit);
        row.setCurrency(ingest.currency);
        row.setRating(ingest.rating);
        row.setOccurredAt(ingest.occurredAt);
        return persist(row);
    }

    public Accept acceptFact(ValueEventRow draft) {
        if (draft == null || draft.getOccurredAt() == null || draft.getDetailJson() == null
                || draft.getDetailJson().length() > 2000) {
            return Accept.rejected("MISSING_PARAM", "事实不完整");
        }
        if (!ValueKinds.DEFINITION.equals(draft.getKind()) && !ValueKinds.COVERAGE.equals(draft.getKind())) {
            return Accept.rejected("MISSING_PARAM", "事实种类无效");
        }
        if (!CityBoardDraftDocument.isId(draft.getRegionId()) || !CityBoardDraftDocument.isId(draft.getBoardId())
                || !CityBoardDraftDocument.isId(draft.getSeasonId())
                || !CityBoardDraftDocument.isId(draft.getRulesReleaseId())) {
            return Accept.rejected("MISSING_PARAM", "事实范围无效");
        }
        if (draft.getMemberId() == null || draft.getMemberId() != 0L) {
            return Accept.rejected("MISSING_PARAM", "事实不绑定玩家");
        }
        ValueEventRow row = new ValueEventRow();
        row.setSourceId(FACT_SOURCE);
        row.setKind(draft.getKind());
        row.setRegionId(draft.getRegionId());
        row.setBoardId(draft.getBoardId());
        row.setSeasonId(draft.getSeasonId());
        row.setRulesReleaseId(draft.getRulesReleaseId());
        row.setMemberId(0L);
        row.setDetailJson(draft.getDetailJson());
        row.setOccurredAt(draft.getOccurredAt());
        if (ValueKinds.DEFINITION.equals(draft.getKind())) {
            if (!CityBoardDraftDocument.isId(draft.getGoalId())) {
                return Accept.rejected("MISSING_PARAM", "定义缺少内容哈希");
            }
            row.setGoalId(draft.getGoalId());
        } else if (!ValueKinds.BEHAVIOR.contains(draft.getActionCode())) {
            return Accept.rejected("MISSING_PARAM", "覆盖家族无效");
        } else {
            row.setActionCode(draft.getActionCode());
        }
        return persist(row);
    }

    private Accept persist(ValueEventRow row) {
        String key = CityBoardHashes.sha256(natural(row));
        String hash = CityBoardHashes.sha256(payload(row));
        ValueEventRow existing = mapper.selectByDedupe(key);
        if (existing != null) {
            return hash.equals(existing.getPayloadHash()) ? Accept.replayed(existing) : Accept.conflict(existing);
        }
        row.setId(ids.incrementAndGet());
        row.setDedupeKey(key);
        row.setPayloadHash(hash);
        row.setCreateTime(Date.from(clock.instant()));
        if (mapper.insertEvent(row) != 1) {
            existing = mapper.selectByDedupe(key);
            if (existing == null) {
                return Accept.rejected("STORE_FAILED", "事件没有写入");
            }
            return hash.equals(existing.getPayloadHash()) ? Accept.replayed(existing) : Accept.conflict(existing);
        }
        return Accept.accepted(row);
    }

    private Accept validateBehavior(ValueContracts.Ingest ingest, CityBoardReleaseRow release) {
        if (ingest == null || release == null) {
            return Accept.rejected("NO_CURRENT_BOARD", "没有当前发布");
        }
        if (blank(ingest.sourceId)) {
            return Accept.rejected("NOT_TRUSTED", "来源不可信");
        }
        String source = ingest.sourceId.trim();
        String sourceKey = source.toLowerCase(java.util.Locale.ROOT);
        if (BANNED.contains(sourceKey) || FACT_SOURCE.equals(source) || !trusted.contains(source)) {
            return Accept.rejected("NOT_TRUSTED", "来源不可信");
        }
        if (!ValueKinds.behavior(ingest.kind)) {
            return Accept.rejected("MISSING_PARAM", "事件种类无效");
        }
        if (!same(release.getRegionId(), ingest.regionId) || !same(release.getBoardId(), ingest.boardId)
                || !same(release.getSeasonId(), ingest.seasonId)
                || !same(release.getRulesReleaseId(), ingest.rulesReleaseId)) {
            return Accept.rejected("SCOPE_MISMATCH", "事件不在当前发布范围");
        }
        if (ingest.memberId == null || ingest.memberId <= 0 || !CityBoardDraftDocument.isId(ingest.visitId)) {
            return Accept.rejected("MISSING_PARAM", "访问身份无效");
        }
        if (ingest.occurredAt == null) {
            return Accept.rejected("MISSING_PARAM", "缺少发生时间");
        }
        Instant latest = clock.instant().plus(Duration.ofMinutes(5));
        if (ingest.occurredAt.toInstant().isAfter(latest)) {
            return Accept.rejected("FUTURE_EVENT", "发生时间超前");
        }
        if (present(ingest.serviceId) && !CityBoardDraftDocument.isId(ingest.serviceId.trim())) {
            return Accept.rejected("MISSING_PARAM", "服务标识无效");
        }
        if (present(ingest.actionCode) && (ValueKinds.noise(ingest.actionCode) || !ValueKinds.actionCode(ingest.actionCode))) {
            return Accept.rejected(ValueKinds.noise(ingest.actionCode) ? "NOT_MEANINGFUL" : "MISSING_PARAM", "动作无效");
        }
        if (present(ingest.goalId) && !CityBoardDraftDocument.isId(ingest.goalId.trim())) {
            return Accept.rejected("MISSING_PARAM", "目标标识无效");
        }
        if (present(ingest.reasonCode) && !CityBoardDraftDocument.isId(ingest.reasonCode.trim())) {
            return Accept.rejected("MISSING_PARAM", "原因无效");
        }
        return switch (ingest.kind) {
            case ValueKinds.MEANINGFUL_ACTION -> present(ingest.actionCode) ? null
                    : Accept.rejected("MISSING_PARAM", "缺少动作");
            case ValueKinds.SERVICE_SUCCESS, ValueKinds.SERVICE_RATING -> {
                if (!present(ingest.serviceId)) {
                    yield Accept.rejected("MISSING_PARAM", "缺少服务");
                }
                if (ValueKinds.SERVICE_RATING.equals(ingest.kind)
                        && (ingest.rating == null || ingest.rating < 0 || ingest.rating > 100)) {
                    yield Accept.rejected("INVALID_RATING", "评分超出存储范围");
                }
                yield null;
            }
            case ValueKinds.PUBLIC_GOAL, ValueKinds.COOPERATIVE_GOAL -> present(ingest.goalId) ? null
                    : Accept.rejected("MISSING_PARAM", "缺少目标");
            case ValueKinds.SKIP, ValueKinds.EXIT -> present(ingest.reasonCode) ? null
                    : Accept.rejected("MISSING_PARAM", "缺少原因");
            case ValueKinds.NET_POINTS -> points(ingest);
            case ValueKinds.VISIT_BUDGET -> budget(ingest);
            default -> null;
        };
    }

    private static Accept points(ValueContracts.Ingest ingest) {
        if (ingest.amount == null || ingest.amount > MAX_SAFE || ingest.amount < -MAX_SAFE) {
            return Accept.rejected("INVALID_AMOUNT", "积分净额无效");
        }
        if ("POINT".equals(ingest.unit)) {
            return ingest.currency == null ? null : Accept.rejected("MISSING_PARAM", "点数不能带币种");
        }
        if ("MINOR".equals(ingest.unit) && ingest.currency != null && CURRENCY.matcher(ingest.currency).matches()) {
            return null;
        }
        return Accept.rejected("MISSING_PARAM", "积分单位无效");
    }

    private static Accept budget(ValueContracts.Ingest ingest) {
        if (ingest.amount == null || ingest.amount < 0 || ingest.amount > MAX_SAFE || !"MINOR".equals(ingest.unit)
                || ingest.currency == null || !CURRENCY.matcher(ingest.currency).matches()) {
            return Accept.rejected("INVALID_AMOUNT", "访问预算无效");
        }
        return null;
    }

    private static String natural(ValueEventRow row) {
        if (ValueKinds.DEFINITION.equals(row.getKind())) {
            return "definition\n" + row.getRulesReleaseId() + "\n" + row.getGoalId();
        }
        if (ValueKinds.COVERAGE.equals(row.getKind())) {
            return "coverage\n" + row.getRulesReleaseId() + "\n" + row.getActionCode();
        }
        String head = row.getRegionId() + "\n" + row.getRulesReleaseId() + "\n" + row.getMemberId() + "\n" + row.getVisitId();
        String at = row.getOccurredAt() == null ? "" : Long.toString(row.getOccurredAt().getTime());
        return switch (row.getKind()) {
            case ValueKinds.ENTRY -> "entry\n" + head;
            case ValueKinds.MEANINGFUL_ACTION -> "meaningful\n" + head + "\n" + row.getActionCode();
            case ValueKinds.SERVICE_SUCCESS -> "service\n" + head + "\n" + row.getServiceId();
            case ValueKinds.PUBLIC_GOAL -> "public\n" + head + "\n" + row.getGoalId();
            case ValueKinds.COOPERATIVE_GOAL -> "coop\n" + head + "\n" + row.getGoalId();
            case ValueKinds.SKIP -> "skip\n" + head + "\n" + row.getReasonCode() + "\n" + at;
            case ValueKinds.EXIT -> "exit\n" + head + "\n" + row.getReasonCode() + "\n" + at;
            case ValueKinds.SERVICE_RATING -> "rating\n" + head + "\n" + row.getServiceId();
            case ValueKinds.NET_POINTS -> "points\n" + head;
            case ValueKinds.VISIT_BUDGET -> "budget\n" + head + "\n" + row.getCurrency();
            default -> "unknown\n" + head;
        };
    }

    static String payload(ValueEventRow row) {
        return String.join("\n",
                text(row.getSourceId()), text(row.getKind()), text(row.getRegionId()), text(row.getBoardId()),
                text(row.getSeasonId()), text(row.getRulesReleaseId()),
                row.getMemberId() == null ? "" : row.getMemberId().toString(),
                text(row.getVisitId()), text(row.getServiceId()), text(row.getActionCode()), text(row.getGoalId()),
                text(row.getReasonCode()), row.getAmount() == null ? "" : row.getAmount().toString(),
                text(row.getUnit()), text(row.getCurrency()),
                row.getRating() == null ? "" : row.getRating().toString(), text(row.getDetailJson()),
                row.getOccurredAt() == null ? "" : Long.toString(row.getOccurredAt().getTime()));
    }

    private static boolean same(String releaseValue, String requested) {
        return CityBoardDraftDocument.isId(releaseValue) && releaseValue.equals(requested == null ? null : requested.trim());
    }

    private static boolean present(String value) {
        return !blank(value);
    }

    private static boolean blank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    public static final class Accept {
        public final String status;
        public final String errorCode;
        public final String message;
        public final ValueEventRow row;

        private Accept(String status, String errorCode, String message, ValueEventRow row) {
            this.status = status;
            this.errorCode = errorCode;
            this.message = message;
            this.row = row;
        }

        static Accept accepted(ValueEventRow row) {
            return new Accept("ACCEPTED", null, "已接受", row);
        }

        static Accept replayed(ValueEventRow row) {
            return new Accept("REPLAYED", null, "已回放", row);
        }

        static Accept conflict(ValueEventRow row) {
            return new Accept("DUPLICATE_CONFLICT", "DUPLICATE_CONFLICT", "去重冲突，保留原事件", row);
        }

        static Accept rejected(String code, String message) {
            return new Accept("REJECTED", code, message, null);
        }
    }
}
