package com.chengyinhub.business.marketplace.editor;

import com.chengyinhub.business.marketplace.editor.store.*;
import com.chengyinhub.common.exception.ServiceException;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.util.*;

/**
 * Unregistered read-only adapter. Installation is not a perpetual-use or publication grant.
 * The contract has no post-install preview/use decision, so only historical receipt metadata is
 * projected, including after expiry/revocation. No licence/publication state is promoted to use rights.
 */
public final class PersistedInstalledDraftReceiptReader implements InstalledDraftReceiptReader {
    private static final int MAX_RECEIPTS=50;
    private final SqlSessionTemplate session;
    private final DataSourceTransactionManager manager;
    private final DataSource dataSource;
    private final InstalledDraftReceiptStore store;
    private final TransactionTemplate transaction;
    public PersistedInstalledDraftReceiptReader(SqlSessionTemplate session,DataSourceTransactionManager manager) {
        this.session=Objects.requireNonNull(session);this.manager=Objects.requireNonNull(manager);
        dataSource=Objects.requireNonNull(manager.getDataSource()); requireComposition();
        store=session.getMapper(InstalledDraftReceiptStore.class);
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
    }
    public InstalledDraftReceipts read(long actor,long draftId,long version,String hash) {
        if(actor<=0)throw new ServiceException("请先登录",401);
        if(draftId<=0||version<=0||!hash(hash))throw new ServiceException("草稿版本绑定无效",400);
        requireComposition();
        return transaction.execute(status -> {
            // One consistent read snapshot, with no locks or new writes. A restore raced by an edit
            // cannot attach old receipts to the new payload or silently substitute the new version.
            InstalledDraftTargetRow target=store.findOwnedTarget(actor,draftId);
            if(target==null||target.getId()!=draftId||target.getOwnerMemberId()!=actor
                    ||!"DRAFT".equals(target.getStatus())
                    ||!("TOPIC".equals(target.getBusinessType())||"ACTIVITY".equals(target.getBusinessType())))
                throw new ServiceException("草稿不存在或已结束",404);
            if(target.getVersion()!=version||!hash.equals(target.getPayloadHash()))
                throw new ServiceException("DRAFT_CHANGED: 草稿版本已变化，请重新恢复",409);
            List<InstalledDraftReceiptRow> rows=store.findReceipts(actor,draftId);
            List<InstalledDraftReceipts.Receipt> output=new ArrayList<InstalledDraftReceipts.Receipt>();
            for(InstalledDraftReceiptRow row:rows.subList(0,Math.min(MAX_RECEIPTS,rows.size()))) {
                if(row.getInstallationId()<=0||row.getTargetDraftId()!=draftId||row.getTargetRevision()<=0
                        ||row.getInstalledAt()==null||!hash(row.getContentHash())||!hash(row.getTermsHash())
                        ||row.getVersionId()==null||row.getVersionId().isEmpty()||row.getVersionId().length()>512)
                    throw new ServiceException("INSTALLATION_RECEIPT_INVALID",409);
                String boundHash=row.getScopePayloadHash();
                InstalledDraftReceipts.Binding binding=!hash(boundHash)?InstalledDraftReceipts.Binding.BINDING_UNVERIFIABLE:
                    row.getTargetRevision()==version&&hash.equals(boundHash)?InstalledDraftReceipts.Binding.EXACT_REVISION:
                    InstalledDraftReceipts.Binding.STALE_BINDING;
                output.add(new InstalledDraftReceipts.Receipt(row.getInstallationId(),draftId,row.getTargetRevision(),
                    row.getInstalledAt().toInstant().toString(),row.getVersionId(),row.getContentHash(),row.getTermsHash(),
                    hash(boundHash)?boundHash:null,binding));
            }
            return new InstalledDraftReceipts(InstalledDraftReceipts.Availability.HISTORICAL_RECEIPTS_ONLY,output,rows.size()>MAX_RECEIPTS);
        });
    }
    private static boolean hash(String value){return value!=null&&value.matches("[0-9a-f]{64}");}
    private void requireComposition(){
        Environment e=session.getSqlSessionFactory().getConfiguration().getEnvironment();
        if(manager.getDataSource()!=dataSource||e.getDataSource()!=dataSource
                ||!(e.getTransactionFactory() instanceof SpringManagedTransactionFactory))
            throw new IllegalStateException("RECEIPT_DATASOURCE_MISMATCH");
    }
}
