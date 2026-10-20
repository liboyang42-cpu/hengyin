package com.chengyinhub.business.service.support;

import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** Real audit persistence on the calling suite's isolated MySQL datasource and transaction. */
public final class RegistrationLifecycleMySqlFixture {
    private RegistrationLifecycleMySqlFixture() { }

    public static void createTable(JdbcTemplate jdbc) throws Exception {
        Path root = Paths.get("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve(
                "chengyinhub-admin/sql/migration_business_audit_event_20260823.sql"))) {
            root = root.getParent();
        }
        assertNotNull(root, "The authoritative audit migration must be present");
        String migration = new String(Files.readAllBytes(root.resolve(
                "chengyinhub-admin/sql/migration_business_audit_event_20260823.sql")), StandardCharsets.UTF_8);
        Matcher ddl = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS business_audit_event \\(.*?\\) ENGINE=InnoDB[^;]*;")
                .matcher(migration);
        assertTrue(ddl.find(), "The production audit CREATE TABLE must be found");
        jdbc.execute(ddl.group());
        // Final audit-only shape of approval_governance_20260828 + request_id_repair_20260830.
        // No menu/grant/other-domain migration or compatibility trigger is needed in this empty scratch.
        jdbc.execute("ALTER TABLE business_audit_event ADD COLUMN actor_type VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' "
                + "COMMENT '主体命名空间：UNKNOWN/MEMBER/SYS_USER；不猜历史身份' AFTER owner_member_id, "
                + "MODIFY COLUMN request_id VARCHAR(64) NOT NULL COMMENT '稳定业务请求键；禁止 NULL/空串'");
        assertEquals("InnoDB", jdbc.queryForObject("SELECT engine FROM information_schema.tables "
                + "WHERE table_schema=DATABASE() AND table_name='business_audit_event'", String.class));
        assertEquals("domain_type,domain_id,action,request_id", jdbc.queryForObject(
                "SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index) FROM information_schema.statistics "
                + "WHERE table_schema=DATABASE() AND table_name='business_audit_event' "
                + "AND index_name='uk_business_audit_request' AND non_unique=0", String.class));
    }

    public static BusinessAuditEventMapper mapper(DataSource source) {
        Configuration configuration = new Configuration(new Environment("registration-lifecycle-it",
                new SpringManagedTransactionFactory(), source));
        String resource = "mapper/business/BusinessAuditEventMapper.xml";
        try (InputStream input = RegistrationLifecycleMySqlFixture.class.getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, "Use the production audit mapper XML");
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        } catch (java.io.IOException error) {
            throw new IllegalStateException(error);
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration))
                .getMapper(BusinessAuditEventMapper.class);
    }

    public static void assertEvent(JdbcTemplate jdbc, long registrationId, String reason,
                                   int paymentStatus, int registrationStatus) {
        String where = " FROM business_audit_event WHERE domain_type='REGISTRATION' AND domain_id=? AND reason=?";
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*)" + where, Integer.class, registrationId, reason));
        String prefix = "SELECT JSON_UNQUOTE(JSON_EXTRACT(after_json, '$.";
        assertEquals(String.valueOf(paymentStatus), jdbc.queryForObject(prefix + "payment_status'))" + where,
                String.class, registrationId, reason));
        assertEquals(String.valueOf(registrationStatus), jdbc.queryForObject(prefix + "registration_status'))" + where,
                String.class, registrationId, reason));
        assertEquals("false", jdbc.queryForObject(prefix + "metric_eligible'))" + where,
                String.class, registrationId, reason));
        assertEquals("UNKNOWN", jdbc.queryForObject(prefix + "attribution_status'))" + where,
                String.class, registrationId, reason));
        assertNotNull(jdbc.queryForObject(prefix + "occurred_at'))" + where,
                String.class, registrationId, reason));
        assertEquals("UNKNOWN", jdbc.queryForObject("SELECT actor_type" + where,
                String.class, registrationId, reason));
    }
}
