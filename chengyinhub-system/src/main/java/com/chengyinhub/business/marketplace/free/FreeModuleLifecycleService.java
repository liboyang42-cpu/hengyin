package com.chengyinhub.business.marketplace.free;

import com.chengyinhub.business.marketplace.free.store.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.chengyinhub.business.marketplace.free.FreeInstallationPersistence.require;

/** Inactive owner-only lifecycle. No administrative/refund authority or reactivation is implied. */
public final class FreeModuleLifecycleService {
    private final FreeInstallationPersistence persistence;
    private final TransactionTemplate transactions;
    public FreeModuleLifecycleService(SqlSessionTemplate session,DataSourceTransactionManager manager) {
        persistence=new FreeInstallationPersistence(session,manager);transactions=new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    /** Ordinary retirement preserves existing frozen grants. Emergency withdrawal stops new use. */
    public void retire(long seller,String publicationId){changePublication(seller,publicationId,false);}
    public void withdraw(long seller,String publicationId){changePublication(seller,publicationId,true);}
    private void changePublication(long seller,String publicationId,boolean emergency) {
        transactions.execute(status->{
            persistence.lockBuyer(seller);
            FreePublicationRow row=persistence.claims.lockPublication(publicationId);
            require(row!=null&&Long.valueOf(seller).equals(row.getSellerMemberId()),"SELLER_SCOPE_DENIED");
            FreeModuleClaimService.verified(row);
            require("LISTED".equals(row.getState())||"RETIRED".equals(row.getState())||"EMERGENCY_BLOCKED".equals(row.getState()),"PUBLICATION_CORRUPT");
            if(emergency) { if(!"EMERGENCY_BLOCKED".equals(row.getState()))require(persistence.claims.withdrawPublication(publicationId)==1,"STORAGE_INVARIANT"); }
            else if("LISTED".equals(row.getState()))require(persistence.claims.retirePublication(publicationId)==1,"STORAGE_INVARIANT");
            return null;
        });
    }
    /** A buyer may relinquish only their own claim; irreversible here, never a refund or quota reset. */
    public void revokeOwnedClaim(long actor,String claimId) {
        transactions.execute(status->{
            persistence.lockBuyer(actor);
            FreeClaimRow claim=persistence.claims.findClaim(actor,claimId);
            require(claim!=null,"GRANT_SCOPE_DENIED");
            require("ACTIVE".equals(claim.getState())||"SUSPENDED".equals(claim.getState())||"REVOKED".equals(claim.getState()),"GRANT_CORRUPT");
            if(!"REVOKED".equals(claim.getState()))require(persistence.claims.revokeOwnedClaim(actor,claimId)==1,"STORAGE_INVARIANT");
            return null;
        });
    }
}
