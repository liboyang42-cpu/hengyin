package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.RoamSession;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** roam_session.status 两态:Java 常量、Mapper 字面量、库侧 CHECK 三处口径一致。 */
class RoamSessionStatusContractTest {
    private static final Path ROAM_XML = Paths.get("../chengyinhub-system/src/main/resources/mapper/business/RoamMapper.xml");
    private static final Path CHECK_SQL = Paths.get("sql/migration_roam_session_status_check_20261024.sql");
    private static final Path DEPENDENCIES = Paths.get("../ci/migration-dependencies.json");

    @Test
    void constantsMatchDocumentedTwoStates() {
        assertEquals(0, RoamSession.STATUS_ACTIVE);
        assertEquals(1, RoamSession.STATUS_FINISHED);
    }

    @Test
    void finishCasMovesActiveToFinishedOnly() throws IOException {
        String xml = Files.readString(ROAM_XML, StandardCharsets.UTF_8).replaceAll("\\s+", " ");
        assertTrue(xml.contains("xp_awarded = #{xpAwarded}, status = " + RoamSession.STATUS_FINISHED
                + " where id = #{id} and status = " + RoamSession.STATUS_ACTIVE), "结算 CAS 必须是 0→1");
    }

    @Test
    void checkConstraintMigrationIsIdempotentAndRegistered() throws IOException {
        String sql = Files.readString(CHECK_SQL, StandardCharsets.UTF_8);
        assertTrue(sql.contains("CHECK (status IN (" + RoamSession.STATUS_ACTIVE + ", " + RoamSession.STATUS_FINISHED + "))"));
        assertTrue(sql.contains("information_schema.table_constraints"), "必须预检,可重复执行");
        assertTrue(sql.contains("-- runner: assert=scalar-true"));
        String deps = Files.readString(DEPENDENCIES, StandardCharsets.UTF_8);
        assertTrue(deps.contains("\"roam_session_status_check_20261024\": [\"roam\"]"));
    }
}
