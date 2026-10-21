package com.chengyinhub.business.mapper.merchantauthoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Job;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Release;
import com.chengyinhub.business.domain.merchantauthoring.MerchantAuthoringRows.Revision;

public class MerchantAuthoringMapperTest {
    @Test
    void ownerAndRequestAreUniqueAndReleaseSnapshotIsNotUpdatedOnWithdraw() throws Exception {
        try (SqlSession session = factory().openSession(true)) {
            MerchantAuthoringMapper mapper = session.getMapper(MerchantAuthoringMapper.class);
            Job first = job(7L, "same");
            assertEquals(1, mapper.insertJob(first));
            assertEquals(first.payloadFingerprint, mapper.findJob(7L, "same").payloadFingerprint);
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class, () -> mapper.insertJob(job(7L, "same")));
            assertEquals(1, mapper.insertJob(job(8L, "same")));

            Revision revision = new Revision();
            revision.draftId = "d1";
            revision.version = 1;
            revision.bodyJson = "{\"canonical_plot\":\"one\"}";
            revision.bodyHash = fingerprint();
            revision.factHash = fingerprint();
            revision.contentHash = fingerprint();
            revision.createdBy = 7L;
            revision.createdAtMs = 1L;
            assertEquals(1, mapper.insertRevision(revision));
            assertThrows(org.apache.ibatis.exceptions.PersistenceException.class, () -> mapper.insertRevision(revision));

            Release release = release(7L, "publish-1");
            assertEquals(1, mapper.insertRelease(release));
            String snapshot = release.snapshotJson;
            assertEquals(1, mapper.withdrawRelease(7L, release.id, 9L, "withdraw-1"));
            Release withdrawn = mapper.findReleaseById(release.id);
            assertEquals("WITHDRAWN", withdrawn.status);
            assertEquals(snapshot, withdrawn.snapshotJson);
            assertEquals(0, mapper.withdrawRelease(7L, release.id, 10L, "withdraw-2"));
        }
    }

    private static Job job(Long owner, String requestId) {
        Job job = new Job();
        job.ownerId = owner;
        job.requestId = requestId;
        job.payloadFingerprint = fingerprint();
        job.merchantId = 3L;
        job.factVersion = "f";
        job.status = "CLAIMED";
        job.bindingKey = "STORE";
        job.createdAtMs = 1L;
        job.updatedAtMs = 1L;
        return job;
    }

    private static Release release(Long owner, String requestId) {
        Release release = new Release();
        release.ownerId = owner;
        release.requestId = requestId;
        release.payloadFingerprint = fingerprint();
        release.draftId = "d1";
        release.revisionId = 1L;
        release.revisionVersion = 1;
        release.merchantId = 3L;
        release.bindingKey = "STORE";
        release.snapshotJson = "{\"canonical_plot\":\"one\"}";
        release.snapshotHash = fingerprint();
        release.factHash = fingerprint();
        release.contentHash = fingerprint();
        release.status = "PUBLISHED";
        release.publishedAtMs = 2L;
        return release;
    }

    private static String fingerprint() {
        return "a".repeat(64);
    }

    public static SqlSessionFactory factory() throws Exception {
        UnpooledDataSource source = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:authoring_" + UUID.randomUUID().toString().replace("-", "") + ";MODE=MySQL;DB_CLOSE_DELAY=-1",
                "sa", "");
        try (Connection connection = source.getConnection(); Statement statement = connection.createStatement()) {
            String sql = Files.readString(schema());
            StringBuilder kept = new StringBuilder();
            for (String line : sql.split("\n")) {
                if (!line.trim().startsWith("--")) kept.append(line).append('\n');
            }
            for (String command : kept.toString().split(";")) {
                if (!command.isBlank()) statement.execute(command);
            }
        }
        Configuration config = new Configuration(new Environment("test", new JdbcTransactionFactory(), source));
        String resource = "mapper/business/merchantauthoring/MerchantAuthoringMapper.xml";
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(in, config, resource, config.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(config);
    }

    private static Path schema() {
        Path module = Path.of("sql/migration_merchant_authoring_w05_candidate.sql");
        if (Files.exists(module)) return module;
        return Path.of("chengyinhub-admin/sql/migration_merchant_authoring_w05_candidate.sql");
    }
}
