package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.marketplace.free.store.FreeInstallTargetRow;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.chengyinhub.business.marketplace.free.FreeInstallationPersistence.require;

/** Inactive owner-selected planning region for a real editor draft, not verified physical location. */
public final class FreeContentDraftScopeService {
    private final FreeInstallationPersistence persistence;
    private final TransactionTemplate transactions;
    public FreeContentDraftScopeService(SqlSessionTemplate session,DataSourceTransactionManager manager) {
        persistence=new FreeInstallationPersistence(session,manager); transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public void prepare(long actor,long draftId,long expectedVersion,String explicitRegion) {
        if(actor<=0||draftId<=0||expectedVersion<=0||explicitRegion==null||explicitRegion.trim().isEmpty()
                ||!explicitRegion.equals(explicitRegion.trim())||explicitRegion.length()>128)
            throw new IllegalArgumentException("explicit owner, draft, version and region required");
        transactions.execute(status->{
            persistence.lockBuyer(actor);
            FreeInstallTargetRow draft=persistence.targets.lockDraft(draftId,actor);
            requireEditable(draft,actor); require(draft.getVersion()==expectedVersion,"DRAFT_REVISION_MISMATCH");
            FreeInstallTargetRow previous=persistence.targets.lockScope(draftId,expectedVersion);
            if(previous!=null) {
                require(sameRevision(draft,previous)&&explicitRegion.equals(previous.getRegion()),"DRAFT_SCOPE_CONFLICT");
            } else { draft.setRegion(explicitRegion); require(persistence.targets.insertScope(draft)==1,"STORAGE_INVARIANT"); }
            return null;
        });
    }
    static void requireEditable(FreeInstallTargetRow draft,long actor) {
        require(draft!=null && Long.valueOf(actor).equals(draft.getOwnerMemberId()),"DRAFT_SCOPE_DENIED");
        require("DRAFT".equals(draft.getStatus()) && ("TOPIC".equals(draft.getBusinessType())||"ACTIVITY".equals(draft.getBusinessType())),"DRAFT_NOT_EDITABLE");
        require(draft.getVersion()!=null&&draft.getVersion()>0&&draft.getPayloadHash()!=null&&draft.getPayloadHash().matches("[0-9a-f]{64}"),"DRAFT_CORRUPT");
    }
    static boolean sameRevision(FreeInstallTargetRow draft,FreeInstallTargetRow scope) {
        return scope!=null&&draft.getId().equals(scope.getId())&&draft.getVersion().equals(scope.getVersion())
                &&draft.getOwnerMemberId().equals(scope.getOwnerMemberId())&&draft.getPayloadHash().equals(scope.getPayloadHash());
    }
}
