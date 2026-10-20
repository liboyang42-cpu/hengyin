package com.chengyinhub.business.service.support.release;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.support.LegacyCmsProvenanceGuard;
import com.chengyinhub.common.constant.ProductSlot;
import com.chengyinhub.common.constant.RoleEntityType;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.dao.DuplicateKeyException;
import java.util.function.Supplier;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * Inactive server-side draft capture seam: no bean, endpoint, publisher or runtime caller.
 * Owns one REPEATABLE_READ transaction and one MyBatis factory/datasource.
 * Preview is read-only; explicit candidate persistence captures and inserts atomically.
 * Any ambient transaction/synchronization is rejected, including READ_COMMITTED callers;
 * isolation is never silently downgraded by transaction participation.
 * Real MySQL/InnoDB concurrency and database routing acceptance remain activation gates.
 */
public final class ConsistentContentCapture {
    private final DataSource dataSource;
    private final SqlSessionTemplate session;
    private final TransactionTemplate transaction;
    private final TransactionTemplate writeTransaction;

    public ConsistentContentCapture(SqlSessionFactory factory) {
        if (factory == null || factory.getConfiguration() == null || factory.getConfiguration().getEnvironment() == null
                || !(factory.getConfiguration().getEnvironment().getTransactionFactory() instanceof SpringManagedTransactionFactory)
                || !factory.getConfiguration().getCacheNames().isEmpty()) {
            throw new IllegalArgumentException("A single Spring-managed uncached MyBatis source is required");
        }
        dataSource = factory.getConfiguration().getEnvironment().getDataSource();
        if (dataSource == null) throw new IllegalArgumentException("Capture datasource is required");
        session = new SqlSessionTemplate(factory);
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource) {
            @Override
            protected void prepareTransactionalConnection(Connection connection, TransactionDefinition definition)
                    throws SQLException {
                if (isEnforceReadOnly() && definition.isReadOnly()) {
                    // Connector/J 8 rejects executeUpdate on a JDBC-read-only connection even for SET.
                    // Keep the exact server READ ONLY command and JDBC flag; use the generic SQL API.
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("SET TRANSACTION READ ONLY");
                    }
                }
            }
        };
        manager.setEnforceReadOnly(true);
        transaction = template(manager, true);
        writeTransaction = template(manager, false);
    }

    /**
     * Narrow member CONTENT-owner slice, matching configureCircleCandidates' existing predicate.
     * Missing/club/platform slots are not guessed; a future approved application entry point
     * must resolve those authority paths explicitly. This does not change runtime authorization.
     */
    public ContentCaptureCandidate captureOwnedDraft(long topicId, long memberId) {
        requireIdentity(topicId, memberId);
        return inTransaction(transaction, true, () -> new ContentCaptureBuilder().build(read(topicId, memberId)));
    }

    /**
     * Inactive opt-in persistence: expected hash/version must come from the exact server-side preview.
     * Snapshots are FROZEN_INCOMPLETE; this allocates no release ID and invokes no approval/publish path.
     */
    public ContentCaptureReceipt persistOwnedDraft(long topicId, long memberId, long expectedConfigVersion,
                                                   String requestId, String expectedContentHash) {
        requireIdentity(topicId, memberId);
        if (expectedConfigVersion < 0 || requestId == null || !requestId.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")
                || expectedContentHash == null || !expectedContentHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Exact candidate version, hash and bounded request ID are required");
        }
        try {
            return inTransaction(writeTransaction, false, () -> {
                ContentCaptureRows rows = new ContentCaptureRows(); rows.topic = readOwnedTopic(topicId, memberId);
                ContentCaptureSnapshotMapper snapshots = session.getMapper(ContentCaptureSnapshotMapper.class);
                ContentCaptureSnapshot prior = snapshots.selectOwnedRequest(topicId, memberId, requestId);
                if (prior != null) return ContentCaptureReceipt.verify(prior, topicId, memberId, requestId, expectedConfigVersion, expectedContentHash);
                if (!Long.valueOf(expectedConfigVersion).equals(rows.topic.getConfigVersion())) throw stale();
                readSources(rows);
                ContentCaptureCandidate candidate = new ContentCaptureBuilder().build(rows);
                if (!expectedContentHash.equals(candidate.contentHash())) throw stale();
                ContentCaptureSnapshot row = new ContentCaptureSnapshot();
                row.setTopicId(topicId); row.setCapturedByMemberId(memberId); row.setRequestId(requestId);
                row.setSourceConfigVersion(expectedConfigVersion); row.setContentHash(candidate.contentHash());
                row.setCanonicalJson(candidate.serverOnlyJson());
                if (snapshots.insertFrozen(row) != 1 || row.getId() == null || row.getId() <= 0) {
                    throw new IllegalStateException("Candidate insert was not acknowledged");
                }
                // Read the persisted row rather than trusting generated keys or in-memory inputs alone.
                ContentCaptureReceipt receipt = ContentCaptureReceipt.verify(snapshots.selectOwnedRequest(topicId,memberId,requestId),
                        topicId,memberId,requestId,expectedConfigVersion,expectedContentHash);
                if (receipt.snapshotId() != row.getId()) throw new IllegalStateException("Candidate identity mismatch");
                return receipt;
            });
        } catch (DuplicateKeyException race) {
            // The failed transaction is rolled back before observing a racing winner. A repeatable-read
            // snapshot created before the competing commit must not be reused for this receipt lookup.
            return inTransaction(transaction, true, () -> {
                readOwnedTopic(topicId, memberId);
                ContentCaptureSnapshot prior = session.getMapper(ContentCaptureSnapshotMapper.class)
                        .selectOwnedRequest(topicId,memberId,requestId);
                if (prior == null) throw race;
                return ContentCaptureReceipt.verify(prior,topicId,memberId,requestId,expectedConfigVersion,expectedContentHash);
            });
        }
    }

    private static TransactionTemplate template(DataSourceTransactionManager manager, boolean readOnly) {
        TransactionTemplate result = new TransactionTemplate(manager);
        result.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
        result.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        result.setReadOnly(readOnly); result.setTimeout(30); return result;
    }

    private <T> T inTransaction(TransactionTemplate selected, boolean readOnly, Supplier<T> action) {
        if (TransactionSynchronizationManager.isActualTransactionActive()
                || TransactionSynchronizationManager.isSynchronizationActive()
                || !TransactionSynchronizationManager.getResourceMap().isEmpty()) {
            throw new IllegalStateException("Capture requires its own transaction with no ambient resources");
        }
        return selected.execute(status -> {
            Connection connection = DataSourceUtils.getConnection(dataSource);
            try {
                if (connection.getTransactionIsolation() != Connection.TRANSACTION_REPEATABLE_READ
                        || connection.getAutoCommit() || TransactionSynchronizationManager.isCurrentTransactionReadOnly() != readOnly) {
                    throw new IllegalStateException("Capture did not establish repeatable-read isolation");
                }
                return action.get();
            } catch (SQLException e) {
                throw new IllegalStateException("Capture transaction unavailable", e);
            } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
            }
        });
    }

    private static void requireIdentity(long topicId, long memberId) {
        if (topicId <= 0 || memberId <= 0) throw new IllegalArgumentException("Topic and member are required");
    }
    private static IllegalStateException stale() { return new IllegalStateException("Draft changed since the expected candidate hash or version"); }

    private ContentCaptureRows read(long topicId, long memberId) {
        ContentCaptureRows rows = new ContentCaptureRows();
        rows.topic = readOwnedTopic(topicId,memberId);
        readSources(rows);
        return rows;
    }

    private CmsTopic readOwnedTopic(long topicId, long memberId) {
        CmsTopic topic = session.getMapper(CmsTopicMapper.class).selectCmsTopicById(topicId);
        if (topic == null || !Long.valueOf(topicId).equals(topic.getId())
                || Integer.valueOf(2).equals(topic.getDelFlag())) throw invalid("Topic source unavailable");
        List<ProductRole> roles = session.getMapper(ProductRoleMapper.class).selectActiveByTopicAndSlot(topicId,ProductSlot.CONTENT);
        if (roles == null || roles.size() != 1) throw invalid("Member CONTENT ownership is required");
        ProductRole owner = roles.get(0);
        if (owner == null || !Long.valueOf(topicId).equals(owner.getTopicId()) || !ProductSlot.CONTENT.equals(owner.getSlot())
                || !Integer.valueOf(1).equals(owner.getStatus()) || !RoleEntityType.MEMBER.equals(owner.getEntityType())
                || !Long.valueOf(memberId).equals(owner.getEntityId())) throw invalid("Member CONTENT ownership is required");
        return topic;
    }

    private void readSources(ContentCaptureRows rows) {
        long topicId = rows.topic.getId();
        CmsTopicNode nodeProbe = new CmsTopicNode(); nodeProbe.setTopicId(topicId); nodeProbe.setStatus(1L);
        rows.nodes = emptyIfNull(session.getMapper(CmsTopicNodeMapper.class).selectCmsTopicNodeList(nodeProbe));
        CmsTopicChapter chapterProbe = new CmsTopicChapter(); chapterProbe.setTopicId(topicId);
        rows.chapters = emptyIfNull(session.getMapper(CmsTopicChapterMapper.class).selectCmsTopicChapterList(chapterProbe));
        rows.keys = emptyIfNull(session.getMapper(ThemeStateKeyMapper.class).selectByTopicId(topicId));
        Set<Long> templateIds = new TreeSet<>();
        for (CmsTopicNode node : rows.nodes) {
            if (node == null || !Long.valueOf(topicId).equals(node.getTopicId())) throw invalid("Node source mismatch");
            if (node.getTemplateId() != null && node.getTemplateId() > 0) templateIds.add(node.getTemplateId());
        }
        if (!templateIds.isEmpty()) {
            // Reuse this capture's exact session/RR snapshot; never open a Spring reader transaction.
            // Managed writers do not adopt legacy rows. Both provenance signals must be read before body.
            LegacyCmsProvenanceGuard provenance = new LegacyCmsProvenanceGuard(
                    session.getMapper(LegacyCmsProvenanceMapper.class));
            for (Long templateId : templateIds) provenance.requireLegacy(templateId);
            List<CmsMemberTemplate> templates = session.getMapper(CmsMemberTemplateMapper.class)
                    .selectCmsMemberTemplateByIds(new ArrayList<>(templateIds));
            for (CmsMemberTemplate t : emptyIfNull(templates)) {
                if (t == null || !templateIds.contains(t.getId()) || rows.templates.put(t.getId(),t) != null) throw invalid("Template source mismatch");
            }
        }
        Set<Long> couponIds = new TreeSet<>(); addCoupon(couponIds,rows.topic.getCompleteRewardCouponId());
        couponIds.addAll(ContentCaptureBuilder.completionCouponIds(rows.topic,rows.nodes.size()));
        for (CmsMemberTemplate t : rows.templates.values()) {
            addCoupon(couponIds,t.getCouponId());
            JsonNode rewards = ContentCaptureBuilder.rewardRules(t.getRewardRulesJson());
            if (rewards != null) for (JsonNode rule : rewards) {
                JsonNode coupon=rule.get("couponId"); if(coupon != null) addCoupon(couponIds,coupon.longValue());
            }
        }
        for (CmsTopicNode node : rows.nodes) {
            CmsRegistrationMerchant probe = new CmsRegistrationMerchant(); probe.setNodeId(node.getId());
            probe.setAuditStatus(1L); probe.setDelFlag(0);
            List<CmsRegistrationMerchant> won = session.getMapper(CmsRegistrationMerchantMapper.class).selectCmsRegistrationMerchantList(probe);
            if (won == null || won.isEmpty()) continue;
            // Legacy runtime keeps the first row for availability; a release capture cannot certify ambiguity.
            if (won.size() != 1) throw invalid("Ambiguous merchant winner source");
            CmsRegistrationMerchant winner=won.get(0);
            if(winner==null) throw invalid("Merchant winner source missing");
            rows.merchants.put(node.getId(),winner); addCoupon(couponIds,winner.getCouponId());
        }
        SmsCouponMapper coupons=session.getMapper(SmsCouponMapper.class);
        for(Long id:couponIds) rows.coupons.put(id,coupons.selectSmsCouponById(id));
        rows.sale=session.getMapper(TopicSaleSnapshotMapper.class).selectSnapshotByTopicId(topicId);
        new ContentCaptureBuilder().validateScope(rows);
        ContentCaptureNpcEvidence.capture(session, rows);
    }

    private static void addCoupon(Set<Long> ids,Long id) { if(id!=null && id>0) ids.add(id); }
    private static <T> List<T> emptyIfNull(List<T> values) { return values==null?Collections.emptyList():values; }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
