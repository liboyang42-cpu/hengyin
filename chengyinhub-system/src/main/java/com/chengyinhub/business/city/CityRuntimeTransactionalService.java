package com.chengyinhub.business.city;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static com.chengyinhub.business.city.CityChecks.*;
import static com.chengyinhub.business.city.CityContracts.*;
import static com.chengyinhub.business.city.CityRuntimeState.*;
import static com.chengyinhub.business.city.CityPersistenceRows.*;

/**
 * Durable isolated-evaluation adapter, deliberately NOT a Spring bean or HTTP endpoint.
 * Production adapters for authority, approvals, proof verification and event allocation do not exist.
 * Facts below are server-only contracts, never client DTOs. All commands use one local DB transaction.
 * No nested transaction/cross-domain service calls: board -> budget -> reservation -> sorted member rows.
 */
public final class CityRuntimeTransactionalService {
    public enum Mode { DISABLED, ISOLATED_PERSISTENCE_EVALUATION }
    private final Mode mode;
    private final CityRuntimeMapper store;
    private final CityPointsMutationMapper points;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final CityLedgerMapping ledgerMapping;
    private final CityPersistenceCodec codec = new CityPersistenceCodec();
    private final CityRuntimeDraftEngine engine = new CityRuntimeDraftEngine(CityRuntimeDraftEngine.Mode.CONTRACT_EVALUATION);

    public CityRuntimeTransactionalService() {
        this.mode = Mode.DISABLED; this.store = null; this.points = null; this.transaction = null;
        this.clock = null; this.ledgerMapping = null;
    }
    public CityRuntimeTransactionalService(Mode mode, CityRuntimeMapper store, CityPointsMutationMapper points,
                                           PlatformTransactionManager manager, Clock clock, CityLedgerMapping ledgerMapping) {
        require(mode != null && store != null && points != null && manager != null && clock != null, "MISSING_PERSISTENCE_DEPENDENCY");
        this.mode = mode; this.store = store; this.points = points;
        this.transaction = new TransactionTemplate(manager); this.clock = clock; this.ledgerMapping = ledgerMapping;
    }
    private interface Work<T> { T run(); }
    private <T> T atomic(final Work<T> work) {
        require(mode == Mode.ISOLATED_PERSISTENCE_EVALUATION, "CITY_RUNTIME_DISABLED");
        // REQUIRES_NEW would suspend a caller holding member/order locks and can deadlock itself.
        require(!TransactionSynchronizationManager.isActualTransactionActive(), "CITY_NESTED_TRANSACTION_FORBIDDEN");
        return transaction.execute(status -> work.run());
    }
    private static void changed(int count) { require(count == 1, "CITY_PERSISTENCE_WRITE_CONFLICT"); }

    /** No rules publisher or approval is fabricated. Caller must supply the already-approved board. */
    public void createBoard(final Board board) {
        atomic(() -> {
            require(board != null, "MISSING_BOARD");
            BoardRow row = new BoardRow(); row.boardId = board.boardId; row.payload = codec.write(board); row.version = 0;
            changed(store.insertBoard(row)); return null;
        });
    }
    /** Same-board guard also serializes a trusted freeze/closure refresh against accept/complete. */
    public void refreshBoard(final Board currentBoard) {
        atomic(() -> {
            Loaded loaded = load(currentBoard.boardId);
            CityRuntimeState next = loaded.state.withBoardFacts(currentBoard);
            String payload = codec.write(currentBoard);
            if (!payload.equals(codec.write(loaded.state.board))) {
                require(currentBoard.version > loaded.state.board.version, "BOARD_FACTS_CONFLICT");
                changed(store.updateBoard(currentBoard.boardId, next.stateVersion, next.stateVersion, payload));
            }
            return null;
        });
    }
    public void createBudget(final String boardId, final String budgetId, final String approvalId,
                             final long approvedVersion, final long approvedGrossCeiling) {
        atomic(() -> {
            Loaded loaded = load(boardId);
            BudgetRow budget = new BudgetRow(); budget.boardId = boardId; budget.budgetId = id(budgetId);
            budget.approvalId = id(approvalId); budget.version = positive(approvedVersion);
            budget.ceiling = nonnegative(approvedGrossCeiling); budget.rulesReleaseId = loaded.state.board.rules.releaseId;
            changed(store.insertBudget(budget)); return null;
        });
    }
    public Result roll(final String boardId, final Participation participation, final long actorId,
                       final long memberVersion, final long stateVersion, final String requestId, final RoundGrant grant) {
        return atomic(() -> {
            Loaded loaded = load(boardId);
            return persist(loaded, engine.roll(loaded.state, participation, actorId, memberVersion,
                    stateVersion, requestId, grant, clock.instant()), actorId, requestId);
        });
    }
    public Result acceptVisit(final String boardId, final Participation participation, final long actorId,
                              final long stateVersion, final String requestId, final Offer offer,
                              final Property property, final String availabilityRevision, final RoundGrant grant,
                              final PayerConsent consent, final String budgetId, final long budgetVersion) {
        return atomic(() -> {
            Loaded loaded = load(boardId);
            require(offer != null, "MISSING_OFFER");
            require(offer.grossReward() > 0 || budgetId == null, "REWARD_RESERVATION_FORBIDDEN");
            RewardReservation reservation = offer.grossReward() == 0 ? null : new RewardReservation(
                    key("RESERVATION", boardId, offer.settlementKey()), offer, offer.grossReward(), true);
            CityRuntimeDraftEngine.Proposal proposal = engine.acceptVisit(loaded.state, participation, actorId,
                    stateVersion, requestId, offer, property, availabilityRevision, grant, consent, reservation, clock.instant());
            if (!proposal.replay && reservation != null) {
                BudgetRow budget = store.lockBudget(id(budgetId));
                require(budget != null && budget.boardId.equals(boardId) && budget.rulesReleaseId.equals(offer.rulesReleaseId)
                        && budget.version == budgetVersion && budget.approvalId != null, "REWARD_BUDGET_NOT_APPROVED");
                changed(store.reserveBudget(budget.budgetId, offer.grossReward()));
                ReservationRow row = new ReservationRow(); row.reservationId = reservation.reservationId;
                row.boardId = boardId; row.budgetId = budget.budgetId; row.effectKey = offer.settlementKey();
                row.offerHash = offer.fingerprint; row.gross = offer.grossReward(); row.status = "RESERVED";
                changed(store.insertReservation(row));
            }
            return persist(loaded, proposal, actorId, requestId);
        });
    }
    public Result completeVisit(final String boardId, final Participation participation, final long actorId,
                                final long memberVersion, final long stateVersion, final String requestId,
                                final String visitKey, final TaskProof proof, final Property property) {
        return atomic(() -> {
            Loaded loaded = load(boardId);
            Visit visit = loaded.state.visits.get(id(visitKey));
            require(visit != null && participation != null, "VISIT_NOT_AUTHORIZED");
            participation.requireActor(loaded.state.board, actorId, memberVersion);
            require(visit.offer.visitingTeamId.equals(participation.teamId), "VISIT_NOT_AUTHORIZED");
            boolean settled = loaded.state.effects.containsKey(visit.offer.settlementKey());
            RewardReservation reservation = visit.reservation;
            ReservationRow row = null;
            Map<Long, Long> balances = new LinkedHashMap<Long, Long>();
            if (!settled) {
                require(ledgerMapping != null, "CITY_LEDGER_MAPPING_REQUIRED");
                row = lockVisitReservation(loaded.state, visit);
                reservation = row == null ? null : new RewardReservation(row.reservationId, visit.offer, row.gross, true);
                TreeSet<Long> ids = new TreeSet<Long>(visit.offer.rewards.keySet());
                ids.addAll(visit.offer.feeCredits.keySet()); ids.add(visit.offer.payerId);
                for (Long memberId : ids) {
                    PointRow member = points.lockCurrentPoints(memberId);
                    require(member != null && member.id.equals(memberId) && member.point != null, "AUTHORITATIVE_BALANCE_REQUIRED");
                    balances.put(memberId, member.point);
                }
            }
            CityRuntimeDraftEngine.Proposal proposal = engine.completeVisit(loaded.state, participation, actorId,
                    memberVersion, stateVersion, requestId, visitKey, proof, property, reservation, balances, clock.instant());
            if (!proposal.replay && row != null) finishReservation(row, true);
            return persist(loaded, proposal, actorId, requestId);
        });
    }
    public Result cancelVisit(final String boardId, final Participation participation, final long actorId,
                              final long memberVersion, final long stateVersion, final String requestId, final String visitKey) {
        return atomic(() -> {
            Loaded loaded = load(boardId);
            CityRuntimeDraftEngine.Proposal proposal = engine.cancelVisit(loaded.state, participation, actorId,
                    memberVersion, stateVersion, requestId, visitKey, clock.instant());
            if (!proposal.replay) {
                ReservationRow row = lockVisitReservation(loaded.state, loaded.state.visits.get(visitKey));
                if (row != null) finishReservation(row, false);
            }
            return persist(loaded, proposal, actorId, requestId);
        });
    }
    public CityRuntimeState readState(final String boardId) { return atomic(() -> load(boardId).state); }

    private ReservationRow lockVisitReservation(CityRuntimeState state, Visit visit) {
        RewardReservation.requireForOffer(visit.offer, visit.reservation);
        ReservationRow hint = store.findReservation(state.board.boardId, visit.offer.settlementKey());
        if (visit.offer.grossReward() == 0) {
            require(hint == null, "REWARD_RESERVATION_FORBIDDEN"); return null;
        }
        require(hint != null, "REWARD_BUDGET_NOT_RESERVED");
        // Nonlocking hint chooses budget; re-read reservation under lock AFTER budget, before any member.
        BudgetRow budget = store.lockBudget(hint.budgetId);
        ReservationRow row = store.lockReservation(state.board.boardId, visit.offer.settlementKey());
        require(budget != null && budget.boardId.equals(state.board.boardId)
                && budget.rulesReleaseId.equals(visit.offer.rulesReleaseId) && row != null
                && row.budgetId.equals(budget.budgetId) && row.reservationId.equals(visit.reservation.reservationId)
                && row.offerHash.equals(visit.offer.fingerprint) && row.gross == visit.offer.grossReward()
                && "RESERVED".equals(row.status), "REWARD_BUDGET_NOT_RESERVED");
        return row;
    }
    private void finishReservation(ReservationRow row, boolean consume) {
        changed(store.finishBudget(row.budgetId, row.gross, consume));
        changed(store.finishReservation(row.reservationId, consume ? "CONSUMED" : "RELEASED"));
    }
    private Result persist(Loaded loaded, CityRuntimeDraftEngine.Proposal proposal, long actorId, String requestId) {
        CityRuntimeState previous = loaded.state, next = proposal.proposedState;
        String boardId = previous.board.boardId;
        EffectRow effect = loaded.effects.get(proposal.receipt.effectKey);
        if (!proposal.replay) {
            require(effect == null, "CITY_EFFECT_CONFLICT");
            effect = new EffectRow(); effect.boardId = boardId; effect.effectKey = proposal.receipt.effectKey;
            effect.kind = proposal.receipt.kind; effect.sequence = proposal.receipt.resultingStateVersion;
            effect.payload = codec.write(proposal.receipt); changed(store.insertEffect(effect));
            require(effect.id != null && effect.id > 0, "CITY_EFFECT_ID_REQUIRED");
            if (proposal.receipt.pointPlan != null) writePoints(effect.id, proposal.receipt.pointPlan);
            for (Map.Entry<String, Round> entry : next.rounds.entrySet()) {
                if (entry.getValue() != previous.rounds.get(entry.getKey())) {
                    Round round = entry.getValue(); FactRow row = new FactRow(); row.boardId = boardId;
                    row.factKey = entry.getKey(); row.teamId = round.grant.teamId; row.roundSequence = round.grant.sequence;
                    row.payload = codec.write(round);
                    changed(previous.rounds.containsKey(entry.getKey()) ? store.updateRound(row) : store.insertRound(row));
                }
            }
            for (Map.Entry<String, Visit> entry : next.visits.entrySet()) {
                if (entry.getValue() != previous.visits.get(entry.getKey())) {
                    Visit visit = entry.getValue(); FactRow row = new FactRow(); row.boardId = boardId; row.factKey = entry.getKey();
                    row.teamId = visit.offer.visitingTeamId; row.serviceInstanceId = visit.offer.serviceInstanceId;
                    row.visitScope = visit.offer.visitScope; row.status = visit.status.name(); row.payload = codec.write(visit);
                    changed(previous.visits.containsKey(entry.getKey()) ? store.finishVisit(row) : store.insertVisit(row));
                }
            }
            scopes(boardId, "PROOF", previous.proofEffects, next.proofEffects);
            scopes(boardId, "REWARD", previous.rewardEffects, next.rewardEffects);
            changed(store.updateBoard(boardId, previous.stateVersion, next.stateVersion, codec.write(next.board)));
            changed(store.insertOutbox(effect));
        }
        require(effect != null, "CITY_EFFECT_MISSING");
        String requestKey = key("REQUEST", boardId, Long.toString(positive(actorId)), id(requestId));
        if (!previous.requestReceipts.containsKey(requestKey)) {
            Receipt alias = next.requestReceipts.get(requestKey);
            require(alias != null && alias.effectKey.equals(effect.effectKey), "CITY_REQUEST_ALIAS_MISSING");
            RequestRow row = new RequestRow(); row.boardId = boardId; row.actorId = actorId; row.requestId = requestId;
            row.requestKey = requestKey; row.payloadHash = alias.payloadHash; row.effectId = effect.id;
            changed(store.insertRequest(row));
        }
        // This object leaves TransactionTemplate only AFTER commit succeeds.
        return new Result(proposal.receipt, effect.id, proposal.replay);
    }
    private void writePoints(long effectId, CityPointSettlementPlan plan) {
        require(ledgerMapping != null, "CITY_LEDGER_MAPPING_REQUIRED");
        for (CityPointSettlementPlan.MemberEntry entry : plan.entries) {
            Long detailId = null;
            if (entry.netChange != 0) {
                changed(points.applyNet(entry.memberId, entry.expectedBefore, entry.netChange));
                DetailRow detail = new DetailRow(); detail.memberId = entry.memberId;
                detail.before = entry.expectedBefore; detail.after = entry.proposedAfter;
                detail.amount = Math.abs(entry.netChange); detail.changeType = entry.netChange > 0 ? 1 : 2;
                detail.eventType = ledgerMapping.settlementEventType; detail.effectId = effectId;
                detail.businessKey = "city:settlement:" + plan.effectKey;
                changed(points.insertDetail(detail)); require(detail.id != null && detail.id > 0, "CITY_DETAIL_ID_REQUIRED");
                detailId = detail.id;
            }
            changed(store.insertPointFact(effectId, entry, detailId, ledgerMapping.approvalId));
        }
    }
    private void scopes(String boardId, String kind, Map<String, String> before, Map<String, String> after) {
        for (Map.Entry<String, String> entry : after.entrySet()) {
            if (!before.containsKey(entry.getKey())) changed(store.insertScope(boardId, kind, entry.getKey(), entry.getValue()));
        }
    }
    private Loaded load(String boardId) {
        BoardRow board = store.lockBoard(id(boardId));
        require(board != null, "CITY_BOARD_NOT_FOUND");
        Board facts = codec.board(board.payload);
        require(facts.boardId.equals(boardId) && board.version >= 0, "CITY_STORAGE_INVALID");
        Map<String, Round> rounds = new LinkedHashMap<String, Round>();
        Map<String, Visit> visits = new LinkedHashMap<String, Visit>();
        Map<String, Receipt> effects = new LinkedHashMap<String, Receipt>();
        Map<Long, Receipt> effectsById = new LinkedHashMap<Long, Receipt>();
        Map<String, EffectRow> rows = new LinkedHashMap<String, EffectRow>();
        for (FactRow row : store.rounds(boardId)) {
            Round round = codec.round(row.payload); require(row.factKey.equals(round.grant.businessKey()), "CITY_STORAGE_INVALID");
            rounds.put(row.factKey, round);
        }
        for (FactRow row : store.visits(boardId)) {
            Visit visit = codec.visit(row.payload, facts.rules);
            require(row.factKey.equals(visit.offer.visitKey()) && row.status.equals(visit.status.name()), "CITY_STORAGE_INVALID");
            visits.put(row.factKey, visit);
        }
        for (EffectRow row : store.effects(boardId)) {
            Receipt receipt = codec.receipt(row.payload, visits);
            require(row.effectKey.equals(receipt.effectKey) && row.sequence == receipt.resultingStateVersion, "CITY_STORAGE_INVALID");
            effects.put(row.effectKey, receipt); effectsById.put(row.id, receipt); rows.put(row.effectKey, row);
        }
        Map<String, Receipt> requests = new LinkedHashMap<String, Receipt>();
        for (RequestRow row : store.requests(boardId)) {
            Receipt effect = effectsById.get(row.effectId); require(effect != null, "CITY_STORAGE_INVALID");
            require(row.requestKey.equals(key("REQUEST", boardId, Long.toString(row.actorId), row.requestId)), "CITY_STORAGE_INVALID");
            requests.put(row.requestKey, new Receipt(row.requestKey, row.payloadHash, effect.effectKey, effect.kind,
                    effect.outcomeId, effect.resultingStateVersion, effect.pointPlan, effect.reservationToRelease));
        }
        Map<String, String> rewards = new LinkedHashMap<String, String>(), proofs = new LinkedHashMap<String, String>();
        for (ScopeRow row : store.scopes(boardId)) {
            require(effects.containsKey(row.effectKey), "CITY_STORAGE_INVALID");
            if ("REWARD".equals(row.kind)) rewards.put(row.scopeKey, row.effectKey);
            else { require("PROOF".equals(row.kind), "CITY_STORAGE_INVALID"); proofs.put(row.scopeKey, row.effectKey); }
        }
        return new Loaded(new CityRuntimeState(facts, board.version, rounds, visits, requests, effects, rewards, proofs), rows);
    }
    private static final class Loaded {
        final CityRuntimeState state; final Map<String, EffectRow> effects;
        Loaded(CityRuntimeState state, Map<String, EffectRow> effects) { this.state = state; this.effects = effects; }
    }
    public static final class Result {
        public final Receipt receipt; public final long effectId; public final boolean replay;
        public final boolean committed = true;
        private Result(Receipt receipt, long effectId, boolean replay) { this.receipt = receipt; this.effectId = effectId; this.replay = replay; }
    }
}
