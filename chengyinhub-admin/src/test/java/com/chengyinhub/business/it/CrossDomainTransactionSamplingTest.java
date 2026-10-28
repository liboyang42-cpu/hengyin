package com.chengyinhub.business.it;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Pure fixture guard SOURCE, NOT_RUN. Synthetic rows/clock values do not prove MySQL freshness. */
class CrossDomainTransactionSamplingTest {
    private static final String SCHEMA = "cy_wp14_" + "a".repeat(32);
    private static final long WITNESS = 91, OBSERVER = 92;

    private Map<String, Object> row(long id, String database, String state, long locks, long modified) {
        return Map.of("mysqlConnectionId", id, "database", database, "state", state,
                "rowsLocked", locks, "rowsModified", modified);
    }

    private Map<String, Object> witness() { return row(WITNESS, "mysql", "RUNNING", 1, 0); }

    @Test void cacheIdleUsesStrictlyGreaterThanThePinnedHundredMilliseconds() {
        long begin = 1_000_000_000L, idle = TimeUnit.MILLISECONDS.toNanos(100);
        assertEquals(idle, CrossDomainStorageFixture.TRX_CACHE_MIN_IDLE_NANOS);
        assertEquals(1, CrossDomainStorageFixture.cacheIdleRemainingNanos(begin + idle, begin));
        assertEquals(0, CrossDomainStorageFixture.cacheIdleRemainingNanos(begin + idle + 1, begin));
    }

    @Test void twentyMillisecondReadsCannotRenewThemselvesIntoAnEligibleSample() {
        long firstEnd = 1_000_000_000L, nextEnd = firstEnd + TimeUnit.MILLISECONDS.toNanos(20);
        assertTrue(CrossDomainStorageFixture.cacheIdleRemainingNanos(nextEnd, firstEnd) > 0);
        // A stale read also becomes the new end_read anchor in the actual observer.
        assertTrue(CrossDomainStorageFixture.cacheIdleRemainingNanos(firstEnd + TimeUnit.MILLISECONDS.toNanos(101), nextEnd) > 0);
        assertEquals(0, CrossDomainStorageFixture.cacheIdleRemainingNanos(nextEnd + TimeUnit.MILLISECONDS.toNanos(100) + 1, nextEnd));
    }

    @Test void newWitnessBoundaryRequiresItsOwnQuietWindowEvenAfterAnOlderReadWasIdle() {
        long oldEnd = 1_000_000_000L, witnessReady = oldEnd + TimeUnit.MILLISECONDS.toNanos(300);
        long quietSince = Math.max(oldEnd, witnessReady);
        assertTrue(CrossDomainStorageFixture.cacheIdleRemainingNanos(witnessReady, quietSince) > 0);
        assertEquals(0, CrossDomainStorageFixture.cacheIdleRemainingNanos(witnessReady + TimeUnit.MILLISECONDS.toNanos(100) + 1, quietSince));
    }

    @Test void exhaustedOrInvalidMonotonicEvidenceIsUnknownRatherThanZero() {
        for (long now : List.of(100L, 101L)) {
            AssertionError failure = assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.requireSampleBudget(now, 100));
            assertTrue(failure.getMessage().contains("TRANSACTION_SAMPLE_UNKNOWN"));
        }
        AssertionError reversed = assertThrows(AssertionError.class, () -> CrossDomainStorageFixture.cacheIdleRemainingNanos(99, 100));
        assertTrue(reversed.getMessage().contains("TRANSACTION_SAMPLE_UNKNOWN"));
    }

    @Test void emptyCacheCannotBecomeAZeroTransactionSuccessWithoutTheCurrentWitness() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> CrossDomainStorageFixture.freshDomainTransactions(List.of(), SCHEMA, WITNESS, OBSERVER));
        assertTrue(failure.getMessage().contains("current physical witness absent/duplicated"));
    }

    @Test void anOldPhysicalWitnessIsNotTheCurrentCacheGeneration() {
        AssertionError failure = assertThrows(AssertionError.class,
                () -> CrossDomainStorageFixture.freshDomainTransactions(List.of(row(90, "mysql", "RUNNING", 1, 0)), SCHEMA, WITNESS, OBSERVER));
        assertTrue(failure.getMessage().contains("current physical witness absent/duplicated"));
    }

    @Test void aValidatedCurrentWitnessMayAccompanyZeroDomainTransactions() {
        assertEquals(List.of(), CrossDomainStorageFixture.freshDomainTransactions(List.of(witness()), SCHEMA, WITNESS, OBSERVER));
    }

    @Test void calibrationAndOtherDomainTransactionsAreNeverExcludedByIdStateOrZeroWrites() {
        var originalCalibration = row(35, SCHEMA, "RUNNING", 1, 0);
        var waiting = row(36, SCHEMA, "LOCK WAIT", 2, 0);
        var modified = row(37, SCHEMA, "RUNNING", 3, 1);
        assertEquals(List.of(originalCalibration, waiting, modified), CrossDomainStorageFixture.freshDomainTransactions(
                List.of(witness(), originalCalibration, waiting, modified), SCHEMA, WITNESS, OBSERVER));
    }

    @Test void witnessMustBeIndependentUnmodifiedAndActuallyOwnItsFixtureLock() {
        assertThrows(AssertionError.class,
                () -> CrossDomainStorageFixture.freshDomainTransactions(List.of(witness()), SCHEMA, WITNESS, WITNESS));
        for (var invalid : List.of(row(WITNESS, SCHEMA, "RUNNING", 1, 0),
                row(WITNESS, "mysql", "RUNNING", 0, 0), row(WITNESS, "mysql", "RUNNING", 1, 1),
                row(WITNESS, "mysql", "LOCK WAIT", 1, 0))) {
            assertThrows(AssertionError.class,
                    () -> CrossDomainStorageFixture.freshDomainTransactions(List.of(invalid), SCHEMA, WITNESS, OBSERVER));
        }
    }

    @Test void duplicatedWitnessOrUnexpectedMysqlRoleCannotBeSilentlyNormalizedAway() {
        assertThrows(AssertionError.class,
                () -> CrossDomainStorageFixture.freshDomainTransactions(List.of(witness(), witness()), SCHEMA, WITNESS, OBSERVER));
        assertThrows(AssertionError.class,
                () -> CrossDomainStorageFixture.freshDomainTransactions(List.of(witness(), row(90, "mysql", "RUNNING", 1, 0)), SCHEMA, WITNESS, OBSERVER));
    }
}
