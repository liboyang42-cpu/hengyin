package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import java.util.function.Function;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousMeetingService.*;

/** Persists the meeting ledger in the same guard transaction as consent rows.
 * Default constructor is disabled. Not a Spring bean. Idempotency key is the match id. */
public final class MyBatisRendezvousMeetingStore implements RendezvousMeetingService.Store {
    public enum Mode { DISABLED, ISOLATED_PERSISTENCE_EVALUATION }
    private final Mode mode;
    private final RendezvousConsentMapper consent;
    private final RendezvousProtocolMapper protocol;
    private final RendezvousHandoffMapper handoffs;
    private final RendezvousMeetingEvidenceMapper evidence;
    private final TransactionTemplate transactions;
    private final DataSourceTransactionManager manager;

    public MyBatisRendezvousMeetingStore() {
        mode = Mode.DISABLED; consent = null; protocol = null; handoffs = null; evidence = null; transactions = null; manager = null;
    }

    public MyBatisRendezvousMeetingStore(Mode mode, SqlSessionTemplate session, DataSourceTransactionManager manager) {
        this.mode = Objects.requireNonNull(mode);
        Objects.requireNonNull(session);
        Objects.requireNonNull(manager);
        org.apache.ibatis.mapping.Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        if (environment.getDataSource() != manager.getDataSource()
                || !(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)) {
            throw new IllegalArgumentException("W16_TRANSACTION_RESOURCE_MISMATCH");
        }
        consent = session.getMapper(RendezvousConsentMapper.class);
        protocol = session.getMapper(RendezvousProtocolMapper.class);
        handoffs = session.getMapper(RendezvousHandoffMapper.class);
        evidence = session.getMapper(RendezvousMeetingEvidenceMapper.class);
        this.manager = manager;
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(10);
    }

    public <T> T transaction(Function<Tx, T> work) {
        if (mode != Mode.ISOLATED_PERSISTENCE_EVALUATION) throw new IllegalStateException("W16_PERSISTENCE_DISABLED");
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("W16_NESTED_TRANSACTION_FORBIDDEN");
        Objects.requireNonNull(work);
        return transactions.execute(status -> {
            if (!Integer.valueOf(1).equals(consent.lockGuard())) throw new IllegalStateException("W16_GUARD_MISSING");
            PlayTeamHandoffAssembly.hold(manager);
            RendezvousConsentSession tickets = new RendezvousConsentSession(consent);
            MeetingTx tx = new MeetingTx(tickets, protocol, handoffs, evidence);
            try { return work.apply(tx); }
            finally { PlayTeamHandoffAssembly.release(manager); tickets.close(); tx.open = false; }
        });
    }

    private static final class MeetingTx implements Tx {
        private final RendezvousConsentSession tickets;
        private final RendezvousProtocolMapper protocol;
        private final RendezvousHandoffMapper handoffs;
        private final RendezvousMeetingEvidenceMapper evidence;
        private boolean open = true;
        MeetingTx(RendezvousConsentSession tickets, RendezvousProtocolMapper protocol,
                  RendezvousHandoffMapper handoffs, RendezvousMeetingEvidenceMapper evidence) {
            this.tickets = tickets; this.protocol = protocol; this.handoffs = handoffs; this.evidence = evidence;
        }
        private void check() { if (!open) throw new IllegalStateException("W16_TRANSACTION_ACCESS_EXPIRED"); }
        public Ticket activeTicket(long actor) { check(); return tickets.activeTicket(actor); }
        public Ticket ticket(String id) { check(); return tickets.ticket(id); }
        public Match match(String id) { check(); return tickets.match(id); }
        public void save(Ticket ticket) { check(); tickets.save(ticket); }
        public void save(Match match) { check(); tickets.save(match); }
        public Meeting meeting(String matchId) {
            check();
            RendezvousProtocolMapper.MeetingRow row = protocol.meeting(matchId);
            return row == null ? null : row.value();
        }
        public void save(Meeting meeting) {
            check();
            Objects.requireNonNull(meeting);
            if (meeting(meeting.matchId) == null) changed(protocol.insertMeeting(meeting));
            else changed(protocol.updateMeeting(meeting));
        }
        public RendezvousHandoffRequest handoffRequest(String matchId) {
            check();
            RendezvousHandoffMapper.Row row = handoffs.findByMatch(matchId);
            return row == null ? null : row.value();
        }
        public void save(RendezvousHandoffRequest request) {
            check();
            Objects.requireNonNull(request);
            RendezvousHandoffRequest existing = handoffRequest(request.matchId);
            if (existing == null) changed(handoffs.insert(request));
            else {
                if (!existing.requestId.equals(request.requestId)) throw new IllegalStateException("W16_HANDOFF_IDENTITY_CONFLICT");
                changed(handoffs.update(request));
            }
        }
        public boolean markDispatched(String requestId, long now) {
            check();
            return handoffs.markDispatched(requestId, now) == 1;
        }
        public RendezvousMeetingEvidence.Fact evidenceFact(String matchId) {
            check();
            return evidence.find(matchId);
        }
        private static void changed(int count) {
            if (count != 1) throw new IllegalStateException("W16_STALE_OR_INVALID_WRITE");
        }
    }
}
