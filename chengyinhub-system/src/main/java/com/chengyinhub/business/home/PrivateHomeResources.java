package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.*;

/**
 * No production implementation: no global stamina/energy account was found in the audited baseline.
 * An approved adapter MUST participate in the SAME DataSource/Spring transaction as PrivateHomeMapper,
 * lock the real scoped account, recheck current ownership/scope and use CAS. Never map points, money,
 * city actions or topic hp to this port implicitly. No remote writes or independent transactions.
 */
public interface PrivateHomeResources {
    javax.sql.DataSource transactionDataSource();
    Resource lockAuthorized(Actor actor, ResourcePolicy policy);
    int restoreToCap(Actor actor, ResourcePolicy policy, Resource locked, String sourceEvent);
}
