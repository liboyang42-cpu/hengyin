package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.common.core.domain.entity.AppUser;
import com.chengyinhub.common.core.domain.model.LoginAppUser;
import com.chengyinhub.web.controller.api.ApiRendezvousPoolController;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleContentSource.*;
import static com.chengyinhub.business.service.rendezvous.RendezvousRoleSecretService.Projection;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** In-memory doubles only. SQL mapping is covered by RendezvousRoleContentPersistenceTest. */
class RendezvousRoleContentServiceTest {
    private final ObjectMapper json = new ObjectMapper();

    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void closedPortsDoNotCallConsentReadOrInventAClue() {
        Mem mem = new Mem();
        int[] consentTx = {0};
        Store consentStore = consentStore(mem, consentTx);
        RendezvousRoleSecretService.Store secretStore = secretStore(mem);
        Clock clock = new MutableClock();
        RendezvousConsentService consent = new RendezvousConsentService(consentStore, new OpenSafety(), clock, new RecordedAdmission());
        View first = consent.join(1, "pool", "v1", 1000);
        View second = consent.join(2, "pool", "v1", 1000);
        consent.propose(first.ticketId, second.ticketId, "public-poi");
        consent.accept(1, first.ticketId, 1);
        consent.accept(2, second.ticketId, 2);
        mem.binding = new Binding(consent.read(1, first.ticketId).matchId, "goal-1", "pub-1", "role-first", "role-second", "BOUND");
        mem.publicationState = "ISSUED";
        mem.bodies.put("role-first", "fixture-body-first");
        mem.bodies.put("role-second", "fixture-body-second");
        int before = consentTx[0];
        RendezvousRoleSecretService closed = new RendezvousRoleSecretService(consent, secretStore);
        Rejection unavailable = assertThrows(Rejection.class, () -> closed.read(1, first.ticketId));
        assertEquals("SECRET_UNAVAILABLE", unavailable.getCode());
        assertNull(unavailable.getCause());
        assertEquals(before, consentTx[0]);
        assertTrue(mem.grants.isEmpty());
        assertTrue(mem.legacyClues.isEmpty());
        RendezvousRoleSecretService noCatalog = new RendezvousRoleSecretService(
                consent, secretStore, new CheckingGuard(new OpenSafety()), RendezvousRoleContentSource.Closed.INSTANCE, clock);
        Rejection missing = assertThrows(Rejection.class, () -> noCatalog.read(1, first.ticketId));
        assertEquals("CONTENT_UNAVAILABLE", missing.getCode());
        assertFalse(missing.getMessage().contains("fixture-body"));
        assertEquals(before, consentTx[0]);
        assertTrue(mem.grants.isEmpty());
    }

    @Test void onlyExplicitSafeOwnedMutualPasses() {
        OngoingVerdict[] next = new OngoingVerdict[1];
        RendezvousRoleContentGuardAdapter adapter = new RendezvousRoleContentGuardAdapter(
                (tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now) -> {
                    assertNull(expectedMatchId);
                    assertNull(expectedPeer);
                    assertTrue(requireMutualAccepted);
                    assertEquals(7L, actor);
                    assertEquals("ticket-7", ticketId);
                    assertEquals(99L, now);
                    return next[0];
                });
        next[0] = verdict(true, true, true);
        adapter.recheck(null, 7, "ticket-7", 99);
        int rejected = 0;
        for (boolean safe : new boolean[]{false, true}) {
            for (boolean owned : new boolean[]{false, true}) {
                for (boolean mutual : new boolean[]{false, true}) {
                    if (safe && owned && mutual) continue;
                    next[0] = verdict(safe, owned, mutual);
                    Rejection partial = assertThrows(Rejection.class, () -> adapter.recheck(null, 7, "ticket-7", 99));
                    assertEquals("SECRET_UNAVAILABLE", partial.getCode());
                    assertNotEquals("NOT_FOUND", partial.getCode());
                    assertNull(partial.getCause());
                    rejected++;
                }
            }
        }
        assertEquals(7, rejected);
        next[0] = null;
        assertEquals("SECRET_UNAVAILABLE", assertThrows(Rejection.class, () -> adapter.recheck(null, 7, "ticket-7", 99)).getCode());
        next[0] = new OngoingVerdict(false, false, false, false, false, false, false, false, "missing", null, null, null);
        Rejection stranger = assertThrows(Rejection.class, () -> adapter.recheck(null, 7, "ticket-7", 99));
        assertEquals("SECRET_UNAVAILABLE", stranger.getCode());
        assertFalse(RendezvousRoleContentGuardAdapter.allows("ongoing"));
        assertFalse(RendezvousRoleContentGuardAdapter.allows(new Object()));
        RendezvousRoleContentGuardAdapter broken = new RendezvousRoleContentGuardAdapter(
                (tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now) -> {
                    throw new IllegalStateException("fixture-body-first");
                });
        Rejection hidden = assertThrows(Rejection.class, () -> broken.recheck(null, 7, "ticket-7", 99));
        assertEquals("SECRET_UNAVAILABLE", hidden.getCode());
        assertNull(hidden.getCause());
        assertFalse(hidden.getMessage().contains("fixture-body"));
    }

    @Test void realVerifyParticipantsSafePassesOnTheSameTransaction() {
        Mem mem = new Mem();
        int[] consentTx = {0};
        Store consentStore = consentStore(mem, consentTx);
        RendezvousRoleSecretService.Store secretStore = secretStore(mem);
        MutableClock clock = new MutableClock();
        RendezvousConsentService consent = new RendezvousConsentService(consentStore, new OpenSafety(), clock, new RecordedAdmission());
        View first = consent.join(1, "pool", "v1", 1000);
        View second = consent.join(2, "pool", "v1", 1000);
        consent.propose(first.ticketId, second.ticketId, "public-poi");
        consent.accept(1, first.ticketId, 1);
        Transaction[] seen = new Transaction[1];
        int[] calls = {0};
        RendezvousRoleContentGuardAdapter adapter = new RendezvousRoleContentGuardAdapter(
                (tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now) -> {
                    calls[0]++;
                    seen[0] = tx;
                    assertNull(expectedMatchId);
                    assertNull(expectedPeer);
                    assertTrue(requireMutualAccepted);
                    assertEquals(clock.millis(), now);
                    return consent.verifyParticipants(tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now);
                });
        RendezvousRoleSecretService secrets = new RendezvousRoleSecretService(
                consent, secretStore, adapter, RendezvousRoleContentSource.Rows.INSTANCE, clock);
        int before = consentTx[0];
        Rejection oneSide = assertThrows(Rejection.class, () -> secrets.read(1, first.ticketId));
        assertEquals("SECRET_UNAVAILABLE", oneSide.getCode());
        assertEquals(1, calls[0]);
        assertSame(mem, seen[0]);
        assertEquals(before, consentTx[0]);
        assertTrue(mem.grants.isEmpty());
        View done = consent.accept(2, second.ticketId, 2);
        mem.binding = new Binding(done.matchId, "goal-1", "pub-1", "role-first", "role-second", "BOUND");
        mem.publicationState = "ISSUED";
        mem.bodies.put("role-first", "fixture-body-first");
        mem.bodies.put("role-second", "fixture-body-second");
        RendezvousRoleContentGuardAdapter refused = new RendezvousRoleContentGuardAdapter(
                (tx, actor, ticketId, expectedMatchId, expectedPeer, requireMutualAccepted, now) -> {
                    throw new Rejection("NOT_ELIGIBLE");
                });
        Rejection propagated = assertThrows(Rejection.class, () -> new RendezvousRoleSecretService(
                consent, secretStore, refused, RendezvousRoleContentSource.Rows.INSTANCE, clock).read(1, first.ticketId));
        assertEquals("NOT_ELIGIBLE", propagated.getCode());
        assertTrue(mem.grants.isEmpty());
        calls[0] = 0;
        Projection mine = secrets.read(1, first.ticketId);
        assertEquals("fixture-body-first", mine.roleClue);
        assertEquals(1, calls[0]);
        assertSame(mem, seen[0]);
        Rejection stranger = assertThrows(Rejection.class, () -> consentStore.transaction(tx -> {
            adapter.recheck(tx, 3, first.ticketId, clock.millis());
            return null;
        }));
        assertEquals("SECRET_UNAVAILABLE", stranger.getCode());
        clock.now = 11_000;
        Rejection expired = assertThrows(Rejection.class, () -> secrets.read(1, first.ticketId));
        assertEquals("SECRET_UNAVAILABLE", expired.getCode());
        assertEquals(MatchState.MUTUAL_ACCEPTED, mem.match(done.matchId).state);
        assertFalse(expired.getMessage().contains("fixture-body-first"));
        Rejection closed = assertThrows(Rejection.class, () -> new RendezvousRoleSecretService(consent, secretStore).read(2, second.ticketId));
        assertEquals("SECRET_UNAVAILABLE", closed.getCode());
    }

    private static OngoingVerdict verdict(boolean safe, boolean owned, boolean mutual) {
        return new OngoingVerdict(safe, true, false, true, false, true, owned, mutual,
                "ticket-7", "match-7", TicketState.ACCEPTED, MatchState.MUTUAL_ACCEPTED);
    }

    @Test void httpProjectionKeepsOnlyTheCallerBody() throws Exception {
        Mem mem = new Mem();
        Store consentStore = consentStore(mem, new int[1]);
        RendezvousRoleSecretService.Store secretStore = secretStore(mem);
        MutableClock clock = new MutableClock();
        OpenSafety safety = new OpenSafety();
        RendezvousConsentService consent = new RendezvousConsentService(consentStore, safety, clock, new RecordedAdmission());
        RendezvousVersionedPolicy policy = new RendezvousVersionedPolicy(new EmptyCatalog(), new RendezvousProtocolTest.Blocks());
        RendezvousRoleSecretService secrets = new RendezvousRoleSecretService(
                consent, secretStore, new CheckingGuard(safety), RendezvousRoleContentSource.Rows.INSTANCE, clock);
        View first = consent.join(1, "pool", "v1", 1000);
        View second = consent.join(2, "pool", "v1", 1000);
        consent.propose(first.ticketId, second.ticketId, "public-poi");
        consent.accept(1, first.ticketId, 1);
        View done = consent.accept(2, second.ticketId, 2);
        mem.binding = new Binding(done.matchId, "goal-1", "pub-1", "role-first", "role-second", "BOUND");
        mem.publicationState = "ISSUED";
        mem.bodies.put("role-first", "fixture-body-first");
        mem.bodies.put("role-second", "fixture-body-second");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new ApiRendezvousPoolController(consent, policy, secrets)).build();
        authenticate(1);
        JsonNode mine = response(mvc, get("/api/rendezvous/tickets/" + first.ticketId + "/secret"), 200);
        authenticate(2);
        JsonNode theirs = response(mvc, get("/api/rendezvous/tickets/" + second.ticketId + "/secret"), 200);
        JsonNode again = response(mvc, get("/api/rendezvous/tickets/" + second.ticketId + "/secret"), 200);
        assertEquals("fixture-body-first", mine.get("data").get("roleClue").asText());
        assertEquals("fixture-body-second", theirs.get("data").get("roleClue").asText());
        assertEquals(theirs.get("data").get("secretRef").asText(), again.get("data").get("secretRef").asText());
        assertEquals("goal-1", mine.get("data").get("goalId").asText());
        assertEquals("pub-1", mine.get("data").get("publishVersion").asText());
        assertNotEquals(mine.get("data").get("roleClue").asText(), mine.get("data").get("secretRef").asText());
        assertFalse(mine.toString().contains("fixture-body-second"));
        assertFalse(mine.toString().contains(theirs.get("data").get("secretRef").asText()));
        assertFalse(mine.toString().contains("publicPlace"));
        authenticate(3);
        JsonNode stranger = response(mvc, get("/api/rendezvous/tickets/" + first.ticketId + "/secret"), 404);
        assertEquals("NOT_FOUND", stranger.get("errorCode").asText());
        assertFalse(stranger.toString().contains("fixture-body"));
        mem.publicationState = "REVOKED";
        authenticate(1);
        JsonNode revoked = response(mvc, get("/api/rendezvous/tickets/" + first.ticketId + "/secret"), 503);
        assertEquals("W16_UNAVAILABLE", revoked.get("errorCode").asText());
        assertFalse(revoked.toString().contains("fixture-body"));
    }

    private JsonNode response(MockMvc mvc, org.springframework.test.web.servlet.RequestBuilder request, int status) throws Exception {
        var result = mvc.perform(request).andReturn();
        assertEquals(status, result.getResponse().getStatus(), result.getResponse().getContentAsString());
        assertEquals("no-store", result.getResponse().getHeader("Cache-Control"));
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static void authenticate(long actor) {
        AppUser app = new AppUser();
        app.setUserId(actor);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(new LoginAppUser(999L, app), null, Collections.emptyList()));
    }

    static final class CheckingGuard implements RendezvousRoleContentGuard {
        final Safety safety;
        CheckingGuard(Safety safety) { this.safety = safety; }
        public void recheck(Transaction tx, long actor, String ticketId, long now) {
            Ticket ticket = tx.ticket(ticketId);
            if (ticket == null || ticket.actor != actor) throw new Rejection("NOT_FOUND");
            Match match = ticket.matchId == null ? null : tx.match(ticket.matchId);
            if (match == null || match.state != MatchState.MUTUAL_ACCEPTED
                    || match.firstAcceptedAt == null || match.secondAcceptedAt == null || match.expiresAt <= now) {
                throw new Rejection("SECRET_UNAVAILABLE");
            }
            Ticket a = tx.ticket(match.firstTicket), b = tx.ticket(match.secondTicket);
            if (a == null || b == null || !safety.eligible(a.actor, a.pool, a.eligibilityVersion, now)
                    || !safety.eligible(b.actor, b.pool, b.eligibilityVersion, now) || safety.blocked(a.actor, b.actor)
                    || !safety.publicMeetingAllowed(a.pool, match.publicPlace, now)) throw new Rejection("SECRET_UNAVAILABLE");
        }
    }

    /** Records the pair inside the guard transaction. Not a quota ledger and not the closed default. */
    static final class RecordedAdmission implements Admission {
        final java.util.List<String> matchIds = new java.util.ArrayList<>();
        public void charge(String matchId, long first, long second, String pool, long now) {
            if (matchId == null || matchId.trim().isEmpty() || pool == null || pool.trim().isEmpty()
                    || first <= 0 || second <= 0 || first == second || now < 0) {
                throw new Rejection("POLICY_CLOSED");
            }
            matchIds.add(matchId);
        }
    }

    static final class OpenSafety implements Safety {
        public boolean eligible(long actor, String pool, String version, long now) { return true; }
        public boolean blocked(long a, long b) { return false; }
        public boolean compatible(long a, long b, String pool, long now) { return true; }
        public boolean publicMeetingAllowed(String pool, String place, long now) { return "public-poi".equals(place); }
    }

    static final class EmptyCatalog implements RendezvousVersionedPolicy.Catalog {
        public RendezvousVersionedPolicy.Row find(String dimension, String versionId) { return null; }
        public int contacts(long actor) { return 0; }
    }

    static final class MutableClock extends Clock {
        long now = 10_000;
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    private static Store consentStore(Mem mem, int[] consentTx) {
        return new Store() {
            public <T> T transaction(java.util.function.Function<Transaction, T> work) {
                consentTx[0]++;
                return work.apply(mem);
            }
        };
    }

    private static RendezvousRoleSecretService.Store secretStore(Mem mem) {
        return new RendezvousRoleSecretService.Store() {
            public <T> T transaction(java.util.function.Function<RendezvousRoleSecretService.Tx, T> work) {
                return work.apply(mem);
            }
        };
    }

    static final class Mem implements RendezvousRoleSecretService.Tx {
        final Map<String, Ticket> tickets = new HashMap<>();
        final Map<String, Match> matches = new HashMap<>();
        final Map<String, String> bodies = new HashMap<>();
        final Map<String, Grant> grants = new HashMap<>();
        final Map<String, String> legacyClues = new HashMap<>();
        Binding binding;
        String publicationState;
        public Ticket activeTicket(long actor) {
            return tickets.values().stream().filter(t -> t.actor == actor && t.active()).findFirst().orElse(null);
        }
        public Ticket ticket(String id) { return tickets.get(id); }
        public Match match(String id) { return matches.get(id); }
        public void save(Ticket ticket) { tickets.put(ticket.id, ticket); }
        public void save(Match match) { matches.put(match.id, match); }
        public String clue(String ticketId) { throw new AssertionError("legacy clue is not content"); }
        public void insertClue(String ticketId, long actor, String matchId, String clue) { throw new AssertionError("legacy clue is not content"); }
        public Binding binding(String matchId) { return binding != null && matchId.equals(binding.matchId) ? binding : null; }
        public String publicationState(String goalId, String publishVersion) {
            return binding != null && goalId.equals(binding.goalId) && publishVersion.equals(binding.publishVersion) ? publicationState : null;
        }
        public String roleBody(String goalId, String publishVersion, String roleKey) {
            if (binding == null || !goalId.equals(binding.goalId) || !publishVersion.equals(binding.publishVersion)) return null;
            if (!roleKey.equals(binding.firstRoleKey) && !roleKey.equals(binding.secondRoleKey)) return null;
            return bodies.get(roleKey);
        }
        public Grant grantByTicket(String ticketId) { return grants.get(ticketId); }
        public void insertGrant(Grant grant) { grants.putIfAbsent(grant.ticketId, grant); }
    }
}
