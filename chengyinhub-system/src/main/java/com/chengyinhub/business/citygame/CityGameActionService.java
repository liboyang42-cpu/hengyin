package com.chengyinhub.business.citygame;

import com.chengyinhub.business.citygame.CityGameRules.Action;
import com.chengyinhub.business.citygame.CityGameSupport.Membership;
import com.chengyinhub.business.mapper.CityGameMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntUnaryOperator;
import java.util.function.LongSupplier;

import static com.chengyinhub.business.citygame.CityGameSupport.*;

/**
 * The CITY game loop, server-authoritative: snapshot → quote → one intent → receipt. The server
 * rolls the die (weighted table published on the board), prices every point-spending action,
 * charges the confirming member only, and settles the team round when its action budget is spent.
 * A requestId is answered once; a retry with the same body gets the stored receipt, never a re-roll.
 *
 * <p>Rules: ROLL_ROUND moves the team token along the board track by the server die face and records
 * where it landed. ACQUIRE_PROPERTY (occupy) needs the team to have landed on the property and to hold
 * an accepted task there. ACCEPT_SERVICE_VISIT (toll) pays a published fee to visit a property run by
 * another team; the fee scores for the operating team. START_CHALLENGE contests a held, unprotected
 * property with a server dice duel (ties keep the defender). When the round budget reaches zero the
 * round settles: every held property scores holding_yield, and the next round opens until max_rounds.
 */
@Service
public class CityGameActionService {
    static final String DOMAIN = "game";
    private static final ZoneId BOARD_ZONE = ZoneId.of("Asia/Shanghai");

    private final CityGameMapper mapper;
    private final IntUnaryOperator random;
    private final LongSupplier clock;

    @Autowired
    public CityGameActionService(CityGameMapper mapper) {
        this(mapper, new SecureRandom()::nextInt, System::currentTimeMillis);
    }

    CityGameActionService(CityGameMapper mapper, IntUnaryOperator random, LongSupplier clock) {
        this.mapper = mapper;
        this.random = random;
        this.clock = clock;
    }

    // ---- snapshot / deltas ----

    public Map<String, Object> snapshot(Long memberId, String boardId) {
        requireMember(memberId);
        requireId(boardId, "boardId");
        Map<String, Object> board = freezeIfDue(mapper.selectBoard(boardId));
        if (board == null) throw new CityGameException(404, "CITY_BOARD_NOT_FOUND", "城市棋盘不存在");
        long now = clock.getAsLong();
        Membership m = membership(mapper, boardId, memberId);
        String myTeam = m.joined() ? m.teamId() : null;
        Map<String, Object> round = myTeam == null ? null : mapper.selectOpenRound(boardId, myTeam);
        Set<String> attempts = myTeam == null ? Set.of() : new HashSet<>(mapper.selectTaskAttemptProperties(boardId, myTeam));
        Map<String, List<Map<String, Object>>> services = new LinkedHashMap<>();
        for (Map<String, Object> s : mapper.selectServices(boardId)) {
            services.computeIfAbsent((String) s.get("propertyId"), k -> new ArrayList<>()).add(s);
        }

        List<Map<String, Object>> properties = new ArrayList<>();
        boolean neutralOpen = false;
        for (Map<String, Object> p : mapper.selectProperties(boardId)) {
            String status = effectiveStatus(p, now);
            boolean neutral = "NEUTRAL".equals(p.get("role"));
            if (neutral && "AVAILABLE".equals(status)) neutralOpen = true;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("propertyId", p.get("propertyId"));
            out.put("pointId", p.get("pointId"));
            out.put("role", p.get("role"));
            out.put("status", status);
            out.put("stateVersion", ((Number) p.get("stateVersion")).longValue());
            out.put("heldByMyTeam", myTeam != null && myTeam.equals(p.get("holderTeamId")));
            out.put("protectedUntilMillis", "PROTECTED".equals(status) ? millis(p.get("protectedUntil")) : null);
            List<Map<String, Object>> offers = new ArrayList<>();
            if (!neutral && !"AVAILABLE".equals(status)) {
                for (Map<String, Object> s : services.getOrDefault((String) p.get("propertyId"), List.of())) {
                    if (offers.size() == 8) break;
                    Map<String, Object> offer = new LinkedHashMap<>();
                    offer.put("serviceId", s.get("serviceId"));
                    offer.put("title", s.get("title"));
                    offer.put("memberPointsFee", s.get("memberPointsFee") == null ? null : ((Number) s.get("memberPointsFee")).longValue());
                    offers.add(offer);
                }
            }
            out.put("services", offers);
            out.put("challenge", neutral || "AVAILABLE".equals(status) ? null : p.get("challengeState"));
            out.put("taskAttemptKept", attempts.contains(p.get("propertyId")));
            properties.add(out);
        }

        Map<String, Object> rules = new LinkedHashMap<>();
        rules.put("rulesReleaseId", board.get("rulesReleaseId"));
        rules.put("ruleVersion", board.get("ruleVersion"));
        rules.put("validFromMillis", millis(board.get("validFrom")));
        rules.put("validUntilMillis", board.get("validUntil") == null ? null : millis(board.get("validUntil")));
        rules.put("actionsRemaining", round == null ? null : ((Number) round.get("actionsRemaining")).intValue());
        rules.put("actionsPerRound", round == null ? null : ((Number) round.get("actionsTotal")).intValue());
        rules.put("holdingLimit", board.get("holdingLimit") == null ? null : ((Number) board.get("holdingLimit")).intValue());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("contract", "CITY_GAME_SNAPSHOT_V1");
        out.put("scope", "CITY");
        out.put("boardId", boardId);
        out.put("seasonId", board.get("seasonId"));
        out.put("boardVersion", ((Number) board.get("boardVersion")).longValue());
        out.put("sequence", ((Number) board.get("eventSequence")).longValue());
        out.put("lifecycle", board.get("lifecycle"));
        out.put("roundSeq", round == null ? null : ((Number) round.get("roundSeq")).longValue());
        out.put("rules", rules);
        out.put("properties", properties);
        out.put("memberPoints", mapper.selectMemberPoints(memberId));
        out.put("neutralTasksAvailable", neutralOpen);
        if (myTeam != null) {
            Map<String, Object> team = new LinkedHashMap<>();
            team.put("trackPosition", ((Number) m.team.get("trackPosition")).intValue());
            team.put("landedPropertyId", m.team.get("landedPropertyId"));
            team.put("score", ((Number) m.team.get("score")).longValue());
            out.put("team", team);
        }
        return out;
    }

    public List<Map<String, Object>> deltas(Long memberId, String boardId, String seasonId, long afterSequence) {
        requireMember(memberId);
        requireId(boardId, "boardId");
        Map<String, Object> board = mapper.selectBoard(boardId);
        if (board == null || !board.get("seasonId").equals(seasonId)) throw new CityGameException(409, "CITY_SNAPSHOT_CHANGED", "城市棋盘已更新");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> e : mapper.selectEvents(boardId, afterSequence, 500)) {
            long sequence = ((Number) e.get("sequence")).longValue();
            Map<String, Object> delta = new LinkedHashMap<>();
            delta.put("eventId", "evt-" + sequence);
            delta.put("boardId", boardId);
            delta.put("seasonId", seasonId);
            delta.put("aggregate", e.get("propertyId") == null ? "board" : e.get("propertyId"));
            delta.put("type", e.get("kind"));
            delta.put("sequence", sequence);
            delta.put("boardVersion", ((Number) e.get("boardVersion")).longValue());
            out.add(delta);
        }
        return out;
    }

    // ---- quote ----

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> quote(Long memberId, Map<String, Object> body) {
        requireMember(memberId);
        Action action = Action.parse(str(body, "action"));
        String boardId = str(body, "boardId"), targetId = str(body, "targetId");
        if (action == null || !action.requiresQuote() || !CityGameRules.id(boardId) || !CityGameRules.id(targetId)) {
            throw new CityGameException(400, "CITY_QUOTE_INVALID", "报价请求无效");
        }
        Map<String, Object> board = mapper.selectBoard(boardId);
        if (board == null) throw new CityGameException(404, "CITY_BOARD_NOT_FOUND", "城市棋盘不存在");
        if (!"OPEN".equals(board.get("lifecycle"))) throw new CityGameException(409, "BOARD_NOT_OPEN", "棋盘未开放");
        Membership m = membership(mapper, boardId, memberId);
        if (!m.joined()) throw new CityGameException(409, "NOT_JOINED", "尚未加入队伍");
        Long cost = price(action, board, targetId);
        if (cost == null) throw new CityGameException(409, "CITY_PRICE_UNPUBLISHED", "价格未公布");

        long now = clock.getAsLong();
        List<String> beneficiaries = new ArrayList<>();
        for (Map<String, Object> member : mapper.selectTeamMembers(m.teamId())) {
            if (flag(member.get("present")) && beneficiaries.size() < 16) beneficiaries.add(String.valueOf(member.get("memberId")));
        }
        Map<String, Object> quote = new LinkedHashMap<>();
        quote.put("quoteId", UUID.randomUUID().toString());
        quote.put("quoteVersion", ((Number) board.get("boardVersion")).longValue());
        quote.put("boardId", boardId);
        quote.put("action", action.name());
        quote.put("targetId", targetId);
        quote.put("payerMemberId", memberId);
        quote.put("ruleVersion", board.get("ruleVersion"));
        quote.put("memberPointsCost", cost);
        quote.put("actionCost", CityGameRules.actionCost(action, board));
        quote.put("expiresAt", new Date(now + 1000L * ((Number) board.get("quoteValidSeconds")).intValue()));
        mapper.insertQuote(quote);

        Map<String, Object> out = new LinkedHashMap<>(quote);
        out.remove("expiresAt");
        out.put("payerMemberId", String.valueOf(memberId));
        out.put("beneficiaryMemberIds", beneficiaries);
        out.put("expiresAtMillis", ((Date) quote.get("expiresAt")).getTime());
        return out;
    }

    /** Current server price for a point-spending action; null when unpublished. */
    private Long price(Action action, Map<String, Object> board, String targetId) {
        switch (action) {
            case ACQUIRE_PROPERTY: return ((Number) board.get("acquirePoints")).longValue();
            case START_CHALLENGE: return ((Number) board.get("challengePoints")).longValue();
            case ACCEPT_SERVICE_VISIT: {
                Map<String, Object> service = mapper.selectService((String) board.get("boardId"), targetId);
                if (service == null || service.get("memberPointsFee") == null) return null;
                return ((Number) service.get("memberPointsFee")).longValue();
            }
            default: return 0L;
        }
    }

    // ---- submit / receipt ----

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> submit(Long memberId, Map<String, Object> body) {
        requireMember(memberId);
        Action action = Action.parse(str(body, "action"));
        String boardId = str(body, "boardId"), participationId = str(body, "participationId"), targetId = str(body, "targetId");
        String requestId = str(body, "requestId");
        Long roundSeq = num(body, "roundSeq"), expectedVersion = num(body, "expectedVersion");
        if (action == null || !CityGameRules.id(boardId) || !CityGameRules.id(participationId) || !CityGameRules.id(targetId)
                || !CityGameRules.id(requestId) || !CityGameRules.version(roundSeq) || !CityGameRules.version(expectedVersion)) {
            throw new CityGameException(400, "CITY_ACTION_INVALID", "行动请求无效");
        }
        boolean consent = body.containsKey("quoteId");
        if (action.requiresQuote() != consent) throw new CityGameException(400, "CITY_ACTION_INVALID", "行动请求无效");
        String hash = CityGameRules.requestHash(body);
        // The board row lock serialises every effect on the board; the receipt is looked up under it so a
        // concurrent duplicate waits and then replays instead of rolling again.
        Map<String, Object> board = freezeIfDue(mapper.selectBoardForUpdate(boardId));
        if (board == null) throw new CityGameException(404, "CITY_BOARD_NOT_FOUND", "城市棋盘不存在");
        Map<String, Object> previous = mapper.selectRequest(memberId, DOMAIN, requestId);
        if (previous != null) {
            if (!hash.equals(previous.get("requestHash"))) throw new CityGameException(409, "CITY_REQUEST_REUSED", "请求编号已用于其他行动");
            return receipt(previous);
        }
        Attempt a = new Attempt(memberId, requestId, hash, action, board, expectedVersion);
        a.currentVersion = ((Number) board.get("boardVersion")).longValue();

        if (!"OPEN".equals(board.get("lifecycle"))) return a.close("BLOCKED", "BOARD_NOT_OPEN");
        Membership m = membership(mapper, boardId, memberId);
        if (!m.joined() || !participationId.equals(m.sessionId())) return a.close("REJECTED", "NOT_JOINED");
        Map<String, Object> team = mapper.selectTeamForUpdate(m.teamId());
        a.teamId = m.teamId();
        Map<String, Object> round = mapper.selectOpenRound(boardId, a.teamId);
        if (round == null || ((Number) round.get("roundSeq")).longValue() != roundSeq) return a.close("REJECTED", "ROUND_CHANGED");
        a.roundSeq = roundSeq;
        int units = CityGameRules.actionCost(action, board);
        if (((Number) round.get("actionsRemaining")).intValue() < units) return a.close("REJECTED", "ACTION_BUDGET_EXHAUSTED");

        if (action == Action.ROLL_ROUND) {
            if (!boardId.equals(targetId)) return a.close("REJECTED", "TARGET_MISMATCH");
            if (expectedVersion != a.currentVersion) return a.close("VERSION_CONFLICT", null);
            CityGameRules.Dice dice = CityGameRules.dice((String) board.get("diceFaces"), (String) board.get("diceWeights"));
            int face = dice.roll(random);
            int position = CityGameRules.advance(((Number) team.get("trackPosition")).intValue(), face, mapper.countTrack(boardId));
            Map<String, Object> landed = mapper.selectPropertyByTrack(boardId, position);
            mapper.insertRoll(boardId, a.teamId, roundSeq, memberId, requestId, "MOVE", (String) board.get("diceId"), face);
            mapper.moveTeam(a.teamId, position, landed == null ? null : (String) landed.get("propertyId"));
            a.diceFace = face;
            return a.apply(units, "TEAM_MOVED", null);
        }
        if (action == Action.SUBMIT_EVIDENCE || action == Action.COMPLETE_SERVICE_VISIT) return a.close("REJECTED", "UNSUPPORTED_ACTION");

        String propertyId = targetId;
        Map<String, Object> service = null;
        if (action == Action.ACCEPT_SERVICE_VISIT) {
            service = mapper.selectService(boardId, targetId);
            if (service == null) return a.close("REJECTED", "SERVICE_NOT_OFFERED");
            propertyId = (String) service.get("propertyId");
        }
        Map<String, Object> property = mapper.selectPropertyForUpdate(boardId, propertyId);
        if (property == null) return a.close("REJECTED", "TARGET_NOT_FOUND");
        long propertyVersion = ((Number) property.get("stateVersion")).longValue();
        a.currentVersion = propertyVersion;
        Set<String> attempts = new HashSet<>(mapper.selectTaskAttemptProperties(boardId, a.teamId));
        a.taskAttemptKept = attempts.contains(propertyId);
        if (expectedVersion != propertyVersion) return a.close("VERSION_CONFLICT", null);
        long now = clock.getAsLong();
        String status = effectiveStatus(property, now);
        if ("PAUSED".equals(status)) return a.close("BLOCKED", "PROPERTY_PAUSED");
        if ("SAFETY_BLOCKED".equals(status)) return a.close("BLOCKED", "SAFETY_BLOCKED");
        boolean neutral = "NEUTRAL".equals(property.get("role"));
        String holder = (String) property.get("holderTeamId");
        boolean ours = a.teamId.equals(holder);

        if (action == Action.ACCEPT_TASK) {
            if (a.taskAttemptKept) return a.close("EFFECT_ALREADY_APPLIED", "TASK_ALREADY_ACCEPTED");
            if (ours) return a.close("REJECTED", "OWN_PROPERTY");
            mapper.insertTaskAttempt(boardId, propertyId, a.teamId, memberId, requestId);
            if (neutral) mapper.addTeamScore(a.teamId, ((Number) board.get("goalScore")).longValue());
            touch(boardId, property, propertyId, holder);
            a.taskAttemptKept = true;
            return a.apply(units, neutral ? "GOAL_COMPLETED" : "TASK_ACCEPTED", propertyId);
        }

        // Point-spending actions: quote and explicit payer consent.
        if (neutral) return a.close("REJECTED", "NEUTRAL_GOAL");
        Map<String, Object> quote = mapper.selectQuoteForUpdate(str(body, "quoteId"));
        String quoteIssue = quoteIssue(quote, body, memberId, action, boardId, targetId, now);
        if (quoteIssue != null) return a.close("REJECTED", quoteIssue);
        Long price = price(action, board, targetId);
        if (price == null || price != ((Number) quote.get("memberPointsCost")).longValue()
                || units != ((Number) quote.get("actionCost")).intValue()) {
            return a.close("QUOTE_CHANGED", null);
        }

        switch (action) {
            case ACQUIRE_PROPERTY:
                if (!"AVAILABLE".equals(status)) return a.close("REJECTED", "PROPERTY_NOT_AVAILABLE");
                if (!propertyId.equals(team.get("landedPropertyId"))) return a.close("REJECTED", "NOT_LANDED");
                if (!a.taskAttemptKept) return a.close("REJECTED", "TASK_REQUIRED");
                if (board.get("holdingLimit") != null && mapper.countHeld(boardId, a.teamId) >= ((Number) board.get("holdingLimit")).intValue()) {
                    return a.close("REJECTED", "HOLDING_LIMIT");
                }
                break;
            case ACCEPT_SERVICE_VISIT:
                if (holder == null || !("HELD".equals(status) || "PROTECTED".equals(status))) return a.close("REJECTED", "SERVICE_NOT_OFFERED");
                if (ours) return a.close("REJECTED", "OWN_PROPERTY");
                break;
            case START_CHALLENGE:
                if (!"HELD".equals(status) || holder == null) return a.close("REJECTED", "NOT_CHALLENGEABLE");
                if (ours) return a.close("REJECTED", "OWN_PROPERTY");
                if (!a.taskAttemptKept) return a.close("REJECTED", "TASK_REQUIRED");
                break;
            default:
                return a.close("REJECTED", "UNSUPPORTED_ACTION");
        }

        if (price > 0) {
            Long before = mapper.selectMemberPoints(memberId);
            if (before == null || mapper.deductMemberPoints(memberId, price) != 1) {
                a.zeroPointBlock = true;
                return a.close("REJECTED", "INSUFFICIENT_POINTS");
            }
            mapper.insertPointsDetail(memberId, before, price, "城市棋盘:" + action.name(), "city-game:" + requestId);
            a.pointsCharged = price;
        }
        mapper.consumeQuote((String) quote.get("quoteId"), requestId);

        Date protectedUntil = new Date(now + 1000L * ((Number) board.get("protectionSeconds")).intValue());
        String held = protectedUntil.getTime() > now ? "PROTECTED" : "HELD";
        switch (action) {
            case ACQUIRE_PROPERTY:
                write(boardId, property, propertyId, held, a.teamId, new Date(now), held.equals("PROTECTED") ? protectedUntil : null, null);
                return a.apply(units, "PROPERTY_ACQUIRED", propertyId);
            case ACCEPT_SERVICE_VISIT:
                mapper.addTeamScore(holder, price);
                touch(boardId, property, propertyId, holder);
                return a.apply(units, "SERVICE_VISITED", propertyId);
            default: {
                CityGameRules.Dice dice = CityGameRules.dice((String) board.get("diceFaces"), (String) board.get("diceWeights"));
                int attack = dice.roll(random), defence = dice.roll(random);
                mapper.insertRoll(boardId, a.teamId, roundSeq, memberId, requestId, "CHALLENGE", (String) board.get("diceId"), attack);
                mapper.insertRoll(boardId, holder, roundSeq, memberId, requestId, "DEFENCE", (String) board.get("diceId"), defence);
                a.diceFace = attack;
                if (CityGameRules.challengerWins(attack, defence)) {
                    write(boardId, property, propertyId, held, a.teamId, new Date(now), held.equals("PROTECTED") ? protectedUntil : null, "RESOLVED");
                } else {
                    write(boardId, property, propertyId, "HELD", holder, date(property.get("heldSince")), null, "RESOLVED");
                }
                return a.apply(units, "CHALLENGE_RESOLVED", propertyId);
            }
        }
    }

    public Map<String, Object> receipt(Long memberId, String requestId) {
        requireMember(memberId);
        if (!CityGameRules.id(requestId)) throw new CityGameException(400, "requestId", "requestId invalid");
        Map<String, Object> row = mapper.selectRequest(memberId, DOMAIN, requestId);
        if (row == null) throw new CityGameException(404, "CITY_RECEIPT_NOT_FOUND", "没有这条请求的回执");
        return receipt(row);
    }

    private String quoteIssue(Map<String, Object> quote, Map<String, Object> body, long memberId, Action action,
                              String boardId, String targetId, long now) {
        if (quote == null || ((Number) quote.get("payerMemberId")).longValue() != memberId) return "QUOTE_NOT_FOUND";
        if (!String.valueOf(memberId).equals(str(body, "payerMemberId"))) return "PAYER_MISMATCH";
        if (!action.name().equals(quote.get("action")) || !boardId.equals(quote.get("boardId")) || !targetId.equals(quote.get("targetId"))
                || num(body, "quoteVersion") == null || num(body, "quoteVersion") != ((Number) quote.get("quoteVersion")).longValue()) {
            return "QUOTE_MISMATCH";
        }
        if (quote.get("consumedRequestId") != null) return "QUOTE_USED";
        if (millis(quote.get("expiresAt")) <= now) return "QUOTE_EXPIRED";
        return null;
    }

    private void touch(String boardId, Map<String, Object> property, String propertyId, String holder) {
        write(boardId, property, propertyId, (String) property.get("status"), holder, date(property.get("heldSince")),
                date(property.get("protectedUntil")), (String) property.get("challengeState"));
    }

    /** Property rows are read FOR UPDATE under the board lock, so this compare-and-set cannot lose. */
    void write(String boardId, Map<String, Object> property, String propertyId, String status, String holder, Date heldSince,
               Date protectedUntil, String challenge) {
        long version = ((Number) property.get("stateVersion")).longValue();
        if (mapper.updateProperty(boardId, propertyId, version, status, holder, heldSince, protectedUntil, challenge) != 1) {
            throw new IllegalStateException("property changed under board lock");
        }
        if (!java.util.Objects.equals(property.get("holderTeamId"), holder)) {
            // Ownership changed: the point's SLOT version advances and the ordered map logs the new state.
            String pointId = (String) property.get("pointId");
            mapper.bumpPointSlot(boardId, pointId);
            mapper.insertPointEvent(boardId, pointId, "SLOT", holder);
        }
    }

    // ---- shared effects (game loop and the r491 surfaces) ----

    /** Advances the board version and appends one ordered board event. Caller holds the board lock. */
    long recordEvent(Map<String, Object> board, String kind, String propertyId, String teamId) {
        String boardId = (String) board.get("boardId");
        mapper.advanceBoard(boardId);
        Map<String, Object> fresh = mapper.selectBoard(boardId);
        long version = ((Number) fresh.get("boardVersion")).longValue();
        mapper.insertEvent(boardId, ((Number) fresh.get("eventSequence")).longValue(), kind, propertyId, teamId, version);
        return version;
    }

    /**
     * Settles a team round whose budget is spent and opens the next one until max_rounds. When the last team
     * finishes its last round the board becomes FROZEN: the season is settled and read-only (lifecycle rule).
     */
    void settleRound(Map<String, Object> board, String teamId, long seq) {
        String boardId = (String) board.get("boardId");
        long score = CityGameRules.settlementScore(mapper.countHeld(boardId, teamId), ((Number) board.get("holdingYield")).intValue());
        mapper.settleRound(boardId, teamId, seq, score);
        mapper.addTeamScore(teamId, score);
        if (seq < ((Number) board.get("maxRounds")).intValue()) {
            mapper.insertRound(boardId, teamId, seq + 1, ((Number) board.get("actionsPerRound")).intValue());
        }
        recordEvent(board, "ROUND_SETTLED", null, teamId);
        if (seq >= ((Number) board.get("maxRounds")).intValue() && mapper.countTeamsStillPlaying(boardId) == 0) {
            if (mapper.freezeBoard(boardId) == 1) {
                Map<String, Object> fresh = mapper.selectBoard(boardId);
                mapper.insertEvent(boardId, ((Number) fresh.get("eventSequence")).longValue(), "BOARD_FROZEN", null, null,
                        ((Number) fresh.get("boardVersion")).longValue());
            }
        }
    }

    /** Spends action units of an open round under the team lock; settles the round when it reaches zero. */
    void spend(Map<String, Object> board, String teamId, long roundSeq, int units) {
        String boardId = (String) board.get("boardId");
        if (mapper.spendActions(boardId, teamId, roundSeq, units) != 1) throw new IllegalStateException("budget changed under team lock");
        Map<String, Object> round = mapper.selectOpenRound(boardId, teamId);
        if (round != null && ((Number) round.get("roundSeq")).longValue() == roundSeq && ((Number) round.get("actionsRemaining")).intValue() == 0) {
            settleRound(board, teamId, roundSeq);
        }
    }

    /** A board past valid_until stops accepting actions: OPEN becomes FROZEN (lazy, on the next read or write). */
    public Map<String, Object> freezeIfDue(Map<String, Object> board) {
        if (board == null || !"OPEN".equals(board.get("lifecycle")) || board.get("validUntil") == null
                || millis(board.get("validUntil")) > clock.getAsLong()) {
            return board;
        }
        if (mapper.freezeBoard((String) board.get("boardId")) == 1) {
            Map<String, Object> fresh = mapper.selectBoard((String) board.get("boardId"));
            mapper.insertEvent((String) fresh.get("boardId"), ((Number) fresh.get("eventSequence")).longValue(), "BOARD_FROZEN", null, null,
                    ((Number) fresh.get("boardVersion")).longValue());
        }
        return mapper.selectBoard((String) board.get("boardId"));
    }

    /** Result of one r491 budget roll: recorded only, the team token does not move. */
    public static final class BudgetRoll {
        public final int faceIndex, face, before, after;

        BudgetRoll(int faceIndex, int face, int before, int after) {
            this.faceIndex = faceIndex;
            this.face = face;
            this.before = before;
            this.after = after;
        }
    }

    /**
     * r491 action-budget ROLL: the server rolls the published weighted die, records the result and spends roll_cost
     * units of the team round. It has no movement, ownership or points effect (contract CITY_ACTION_BUDGET_V2).
     * Caller holds the board lock (selectBoardForUpdate) and the team lock.
     */
    BudgetRoll budgetRoll(Map<String, Object> board, String teamId, long roundSeq, long memberId, String requestId,
                          java.util.function.Function<int[], String> resultJson) {
        String boardId = (String) board.get("boardId");
        Map<String, Object> round = mapper.selectRoundForUpdate(boardId, teamId, roundSeq);
        int cost = ((Number) board.get("rollCost")).intValue();
        int before = ((Number) round.get("actionsRemaining")).intValue();
        CityGameRules.Dice dice = CityGameRules.dice((String) board.get("diceFaces"), (String) board.get("diceWeights"));
        int index = dice.rollIndex(random);
        int face = dice.face(index);
        mapper.insertBudgetRoll(boardId, teamId, roundSeq, memberId, requestId, (String) board.get("diceId"), face, index,
                resultJson.apply(new int[]{index, face, before, before - cost}));
        recordEvent(board, "BUDGET_ROLLED", null, teamId);
        spend(board, teamId, roundSeq, cost);
        return new BudgetRoll(index, face, before, before - cost);
    }

    /** Stored PROTECTED becomes HELD once the window passes; the stored row is not rewritten by reads. */
    static String effectiveStatus(Map<String, Object> property, long now) {
        String status = (String) property.get("status");
        if ("PROTECTED".equals(status) && (property.get("protectedUntil") == null || millis(property.get("protectedUntil")) <= now)) return "HELD";
        return status;
    }

    private Map<String, Object> receipt(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requestId", row.get("requestId"));
        out.put("boardId", row.get("boardId"));
        out.put("action", row.get("action"));
        out.put("decision", row.get("decision"));
        out.put("currentVersion", ((Number) row.get("currentVersion")).longValue());
        out.put("reasonCode", row.get("reasonCode"));
        out.put("taskAttemptKept", flag(row.get("taskAttemptKept")));
        out.put("pointsCharged", ((Number) row.get("pointsCharged")).longValue());
        out.put("diceFace", row.get("diceFace"));
        return out;
    }

    /** One request in flight under the board lock; every exit stores exactly one receipt. */
    private final class Attempt {
        final long memberId;
        final String requestId, hash;
        final Action action;
        final Map<String, Object> board;
        final long expectedVersion;
        long currentVersion;
        String teamId;
        long roundSeq;
        boolean taskAttemptKept;
        boolean zeroPointBlock;
        long pointsCharged;
        Integer diceFace;

        Attempt(long memberId, String requestId, String hash, Action action, Map<String, Object> board, long expectedVersion) {
            this.memberId = memberId;
            this.requestId = requestId;
            this.hash = hash;
            this.action = action;
            this.board = board;
            this.expectedVersion = expectedVersion;
        }

        Map<String, Object> close(String decision, String reason) {
            if ("VERSION_CONFLICT".equals(decision) && currentVersion == expectedVersion) {
                throw new IllegalStateException("conflict without a version change");
            }
            if (zeroPointBlock) outing(0, 1);
            return store(decision, reason);
        }

        Map<String, Object> apply(int units, String eventKind, String propertyId) {
            String boardId = (String) board.get("boardId");
            long version = event(eventKind, propertyId);
            currentVersion = propertyId == null ? version : currentVersion + 1;
            outing(pointsCharged, 0);
            spend(board, teamId, roundSeq, units);
            return store("APPLIED", null);
        }

        private long event(String kind, String propertyId) { return recordEvent(board, kind, propertyId, teamId); }

        private void settle(String boardId, long seq) { settleRound(board, teamId, seq); }

        private void outing(long spent, int zeroBlocks) {
            long now = clock.getAsLong();
            LocalDate day = Instant.ofEpochMilli(now).atZone(BOARD_ZONE).toLocalDate();
            mapper.recordOuting(UUID.randomUUID().toString(), (String) board.get("boardId"), memberId,
                    java.sql.Date.valueOf(day), new Date(now), spent, zeroBlocks);
        }

        private Map<String, Object> store(String decision, String reason) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("memberId", memberId);
            row.put("domain", DOMAIN);
            row.put("requestId", requestId);
            row.put("boardId", board.get("boardId"));
            row.put("action", action.name());
            row.put("requestHash", hash);
            row.put("httpStatus", 200);
            row.put("decision", decision);
            row.put("currentVersion", currentVersion);
            row.put("reasonCode", reason);
            row.put("taskAttemptKept", taskAttemptKept ? 1 : 0);
            row.put("pointsCharged", pointsCharged);
            row.put("diceFace", diceFace);
            mapper.insertRequest(row);
            return receipt(row);
        }
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof String ? (String) value : null;
    }

    private static Long num(Map<String, Object> body, String key) {
        Object value = body.get(key);
        return value instanceof Integer || value instanceof Long ? ((Number) value).longValue() : null;
    }
}
