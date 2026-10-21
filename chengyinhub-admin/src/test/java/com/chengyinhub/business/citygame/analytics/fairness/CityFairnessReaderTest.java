package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.mapper.CityFairnessMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static com.chengyinhub.business.citygame.analytics.fairness.FairnessFactContract.*;
import static org.junit.jupiter.api.Assertions.*;

class CityFairnessReaderTest {
    private final CityFairnessReader reader = new CityFairnessReader();

    @Test void unapprovedParametersAreNotComputableAndDoNotInventThresholds() {
        Memory memory = new Memory();
        memory.parameter = new CityFairnessReader.ParameterRow();
        memory.parameter.state = "DRAFT";
        memory.parameter.sourceRef = "draft-note";
        memory.parameter.deltaMillis = 1L;
        memory.parameter.horizonMillis = 1L;
        memory.parameter.lowerEffectiveEvents = 1;
        memory.events.add(task("e1", "o1", 1, 8_000));
        CityFairnessReader.Result result = reader.read(10_000, memory);
        assertEquals(CityFairnessReader.NOT_COMPUTABLE, result.status);
        assertTrue(result.missing.contains(PARAMETER_APPROVAL));
        assertTrue(result.groups.isEmpty());
        assertTrue(result.secondOutings.isEmpty());
    }

    @Test void missingSourceRefAndNonPositiveDeltaAreNamed() {
        Memory memory = approvedMemory();
        memory.parameter.sourceRef = " ";
        memory.parameter.deltaMillis = 0L;
        CityFairnessReader.Result result = reader.read(10_000, memory);
        assertTrue(result.missing.contains(PARAMETER_SOURCE_REF));
        assertTrue(result.missing.contains(DELTA_MILLIS));
        assertFalse(result.computable());
    }

    @Test void unconnectedSourceNamesStreamsAndParameters() {
        CityFairnessReader.Result result = reader.read(10_000, new UnconnectedFairnessSource());
        assertEquals(CityFairnessReader.NOT_COMPUTABLE, result.status);
        assertTrue(result.missing.contains("stream:outings"));
        assertTrue(result.missing.contains(DELTA_MILLIS));
        assertTrue(result.groups.isEmpty());
    }

    @Test void emptyConnectedFactsAreComputableZeros() {
        CityFairnessReader.Result result = reader.read(10_000, approvedMemory());
        assertTrue(result.computable());
        assertTrue(result.missing.isEmpty());
        assertTrue(result.groups.isEmpty());
        assertTrue(result.secondOutings.isEmpty());
    }

    @Test void secondOutingUsesExplicitDeltaHorizonAndLowerBound() {
        Memory memory = approvedMemory();
        memory.events.add(task("e1", "o1", 7, 6_000));
        memory.events.add(task("e2", "o2", 7, 6_100));
        CityFairnessReader.Result result = reader.read(10_000, memory);
        assertTrue(result.computable());
        assertEquals(1, result.secondOutings.size());
        assertEquals("o2", result.secondOutings.get(0).outingId);
        assertEquals(6_100L, result.secondOutings.get(0).anchorMillis);
        assertEquals(NEWCOMER, result.secondOutings.get(0).newcomer);
        assertEquals(2, result.secondOutings.get(0).teamSize);
        assertEquals(3, result.secondOutings.get(0).actionCost);
    }

    @Test void gapInsideDeltaDoesNotCountASecondOuting() {
        Memory memory = approvedMemory();
        memory.events.add(task("e1", "o1", 7, 6_000));
        memory.events.add(task("e2", "o2", 7, 6_050));
        CityFairnessReader.Result result = reader.read(10_000, memory);
        assertTrue(result.computable());
        assertTrue(result.secondOutings.isEmpty());
    }

    @Test void reopenReconnectAndRegistrationRepeatDoNotCreateOutings() {
        Memory memory = approvedMemory();
        memory.events.add(task("e1", "o1", 7, 6_000));
        memory.events.add(mark("reopen", null, APP_REOPEN, 7, 7_000));
        memory.events.add(mark("reconnect", "o-new", RECONNECT, 7, 8_000));
        memory.events.add(mark("repeat", null, REGISTRATION_REPEAT, 7, 9_000));
        CityFairnessReader.Result result = reader.read(10_000, memory);
        assertTrue(result.computable());
        assertTrue(result.secondOutings.isEmpty());
        assertEquals(1, result.groups.size());
    }

    @Test void duplicateEffectiveEventCountsOnceTowardTheLowerBound() {
        Memory memory = approvedMemory();
        memory.parameter.lowerEffectiveEvents = 2;
        CityFairnessReader.EventRow first = task("e1", "o1", 7, 6_000);
        memory.events.add(first);
        memory.events.add(task("e1", "o1", 7, 6_000));
        CityFairnessReader.Result result = reader.read(10_000, memory);
        assertTrue(result.computable());
        assertTrue(result.secondOutings.isEmpty());
        assertTrue(result.groups.isEmpty());
    }

    @Test void sameEventOnTwoOutingsIsNotComputable() {
        Memory memory = approvedMemory();
        memory.events.add(task("e1", "o1", 7, 6_000));
        memory.events.add(task("e1", "o2", 7, 6_500));
        CityFairnessReader.Result result = reader.read(10_000, memory);
        assertFalse(result.computable());
        assertTrue(result.missing.contains("event-conflict:e1"));
    }

    @Test void groupsKeepJoinTimeTeamSizeCostAndNewcomerApart() {
        Memory memory = approvedMemory();
        memory.events.add(task("a-task", "oa", 1, 6_000));
        memory.events.add(gain("a-gain", "oa", 1, 6_010, 4));
        CityFairnessReader.EventRow other = task("b-task", "ob", 2, 6_200);
        other.teamSize = 9;
        other.newcomer = RETURNING;
        memory.events.add(other);
        CityFairnessReader.EventRow otherGain = gain("b-gain", "ob", 2, 6_210, 7);
        otherGain.teamSize = 9;
        otherGain.newcomer = RETURNING;
        memory.events.add(otherGain);
        CityFairnessReader.Result result = reader.read(10_000, memory);
        assertEquals(2, result.groups.size());
        CityFairnessReader.Group newcomers = result.groups.get(0);
        CityFairnessReader.Group returning = result.groups.get(1);
        assertEquals(4L, newcomers.effectiveGain);
        assertEquals(7L, returning.effectiveGain);
        assertEquals(9, returning.teamSize);
        assertTrue(newcomers.concentrationReported());
        assertEquals(4L, newcomers.topActorGain);
    }

    @Test void operationalReadingsUseOnlyTheirOwnKinds() {
        Memory memory = approvedMemory();
        memory.events.add(task("e1", "o1", 7, 6_000));
        memory.events.add(mark("goal", "o1", GOAL_COMPLETED, 7, 6_010));
        memory.events.add(mark("block", "o1", ZERO_POINT_BLOCK, 7, 6_020));
        memory.events.add(mark("exit", "o1", CHALLENGE_COMPENSATION_EXIT, 7, 6_030));
        memory.events.add(mark("gps", "o1", GPS_FALSE_REJECTION, 7, 6_040));
        memory.events.add(mark("ticket", "o1", SAFETY_TICKET, 7, 6_050));
        CityFairnessReader.EventRow verify = mark("merchant", "o1", MERCHANT_VERIFICATION, 7, 6_060);
        verify.verificationMillis = 90L;
        memory.events.add(verify);
        CityFairnessReader.Group group = reader.read(10_000, memory).groups.get(0);
        assertEquals(1, group.goalCompletions);
        assertEquals(1, group.zeroPointBlocks);
        assertEquals(1, group.challengeCompensationExits);
        assertEquals(1, group.gpsFalseRejections);
        assertEquals(1, group.safetyTickets);
        assertEquals(1, group.merchantVerifications);
        assertEquals(90L, group.merchantVerificationMillis);
        assertFalse(group.concentrationReported());
    }

    @Test void gainOnAnIneffectiveOutingIsNotAnEffectiveGain() {
        Memory memory = approvedMemory();
        memory.parameter.lowerEffectiveEvents = 2;
        memory.events.add(task("e1", "o1", 7, 6_000));
        memory.events.add(gain("g1", "o1", 7, 6_010, 5));
        CityFairnessReader.Result result = reader.read(10_000, memory);
        assertTrue(result.computable());
        assertTrue(result.groups.isEmpty());
    }

    @Test void missingGainOrVerificationDurationIsNamed() {
        Memory memory = approvedMemory();
        memory.events.add(task("e1", "o1", 7, 6_000));
        CityFairnessReader.EventRow gain = gain("g1", "o1", 7, 6_010, 1);
        gain.gainAmount = null;
        memory.events.add(gain);
        assertTrue(reader.read(10_000, memory).missing.contains("gainAmount:g1"));
        memory.events.remove(gain);
        CityFairnessReader.EventRow merchant = mark("m1", "o1", MERCHANT_VERIFICATION, 7, 6_020);
        memory.events.add(merchant);
        assertTrue(reader.read(10_000, memory).missing.contains("verificationMillis:m1"));
    }

    @Test void horizonEdgeAndAttributeConflict() {
        Memory memory = approvedMemory();
        memory.events.add(task("edge", "o1", 7, 5_000));
        CityFairnessReader.Result edge = reader.read(10_000, memory);
        assertTrue(edge.computable());
        assertTrue(edge.groups.isEmpty());
        memory.events.clear();
        CityFairnessReader.EventRow left = task("e1", "o1", 7, 6_000);
        CityFairnessReader.EventRow right = task("e2", "o1", 7, 6_100);
        right.teamSize = 8;
        memory.events.add(left);
        memory.events.add(right);
        assertTrue(reader.read(10_000, memory).missing.contains("outing-attribute-conflict:o1"));
    }

    @Test void mapperFailuresStayNotComputable() {
        CityFairnessReader.Result thrown = reader.read(10_000, new MyBatisCityFairnessSource(new CityFairnessMapper() {
            public List<CityFairnessReader.ParameterRow> selectParameters() { throw new IllegalStateException("down"); }
            public List<CityFairnessReader.EventRow> selectEvents(long start, long asOf) { return List.of(); }
        }));
        assertTrue(thrown.missing.contains("city_fairness_parameter"));
        CityFairnessReader.ParameterRow row = approved();
        CityFairnessReader.Result ambiguous = reader.read(10_000, new MyBatisCityFairnessSource(new CityFairnessMapper() {
            public List<CityFairnessReader.ParameterRow> selectParameters() { return List.of(row, row); }
            public List<CityFairnessReader.EventRow> selectEvents(long start, long asOf) { return List.of(); }
        }));
        assertTrue(ambiguous.missing.contains("parameter-ambiguous"));
        CityFairnessReader.EventRow event = task("e1", "o1", 7, 6_000);
        CityFairnessReader.Result computed = reader.read(10_000, new MyBatisCityFairnessSource(new CityFairnessMapper() {
            public List<CityFairnessReader.ParameterRow> selectParameters() { return List.of(row); }
            public List<CityFairnessReader.EventRow> selectEvents(long start, long asOf) { return List.of(event); }
        }));
        assertTrue(computed.computable());
        assertEquals(1, computed.groups.size());
    }

    @Test void candidateSqlAndMapperDoNotInsertThresholds() throws Exception {
        String sql = Files.readString(Path.of("sql/migration_w20_city_fairness_candidate.sql"));
        String xml = Files.readString(Path.of("../chengyinhub-system/src/main/resources/mapper/business/CityFairnessMapper.xml"));
        assertFalse(sql.toLowerCase().contains("insert"));
        assertFalse(xml.toLowerCase().contains("insert"));
        assertFalse(xml.toLowerCase().contains("coalesce"));
        assertTrue(xml.contains("city_fairness_parameter"));
        assertTrue(xml.contains("city_fairness_event"));
    }

    private static Memory approvedMemory() {
        Memory memory = new Memory();
        memory.parameter = approved();
        return memory;
    }

    private static CityFairnessReader.ParameterRow approved() {
        CityFairnessReader.ParameterRow row = new CityFairnessReader.ParameterRow();
        row.state = "APPROVED";
        row.sourceRef = "fairness-approval-1";
        row.deltaMillis = 100L;
        row.horizonMillis = 5_000L;
        row.lowerEffectiveEvents = 1;
        return row;
    }

    private static CityFairnessReader.EventRow task(String id, String outing, long actor, long at) {
        CityFairnessReader.EventRow row = mark(id, outing, EFFECTIVE_TASK, actor, at);
        row.joinTime = 1_000L;
        row.teamSize = 2;
        row.actionCost = 3;
        row.newcomer = NEWCOMER;
        return row;
    }

    private static CityFairnessReader.EventRow gain(String id, String outing, long actor, long at, long amount) {
        CityFairnessReader.EventRow row = task(id, outing, actor, at);
        row.kind = GAIN;
        row.gainAmount = amount;
        return row;
    }

    private static CityFairnessReader.EventRow mark(String id, String outing, String kind, long actor, long at) {
        CityFairnessReader.EventRow row = new CityFairnessReader.EventRow();
        row.eventId = id;
        row.outingId = outing;
        row.kind = kind;
        row.actorId = actor;
        row.occurredAt = at;
        row.joinTime = 1_000L;
        row.teamSize = 2;
        row.actionCost = 3;
        row.newcomer = NEWCOMER;
        return row;
    }

    private static final class Memory implements CityFairnessReader.Source {
        CityFairnessReader.ParameterRow parameter;
        final List<CityFairnessReader.EventRow> events = new ArrayList<>();
        List<String> unavailable = List.of();
        public CityFairnessReader.ParameterRow parameters() { return parameter; }
        public List<CityFairnessReader.EventRow> events(long windowStartExclusive, long asOfInclusive) { return events; }
        public List<String> unavailableStreams() { return unavailable; }
    }
}
