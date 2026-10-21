package com.chengyinhub.business.home;

import com.chengyinhub.business.home.PrivateHomeContracts.Actor;
import com.chengyinhub.business.home.PrivateHomeContracts.Resource;
import com.chengyinhub.business.home.PrivateHomeContracts.ResourcePolicy;
import com.chengyinhub.common.exception.ServiceException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Same-database CAS for an already-existing stamina/energy row.
 * Does not open accounts, pay rewards, or read wallet, sponsor, or purchase tables.
 */
public final class PrivateHomeResourceAccountAdapter implements PrivateHomeResources {
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;

    public PrivateHomeResourceAccountAdapter(DataSource dataSource) {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override public DataSource transactionDataSource() { return dataSource; }

    @Override public Resource lockAuthorized(Actor actor, ResourcePolicy policy) {
        reject(policy);
        List<Resource> rows = jdbc.query(
                "SELECT current_value, cap_value, version FROM player_private_home_resource WHERE owner_id=? AND resource_scope=? AND resource_type=? FOR UPDATE",
                (rs, row) -> {
                    Resource resource = new Resource();
                    resource.current = rs.getLong(1);
                    resource.cap = rs.getLong(2);
                    resource.version = rs.getLong(3);
                    return resource;
                }, actor.ownerId, policy.scope, policy.resourceType);
        if (rows.isEmpty()) return null;
        Resource locked = rows.get(0);
        if (policy.cap != null && locked.cap != policy.cap) throw new ServiceException("RESOURCE_NOT_AUTHORIZED");
        return locked;
    }

    @Override public int restoreToCap(Actor actor, ResourcePolicy policy, Resource locked, String sourceEvent) {
        reject(policy);
        if (sourceEvent == null || sourceEvent.isBlank()) throw new ServiceException("RESOURCE_WRITE_FAILED");
        return jdbc.update(
                "UPDATE player_private_home_resource SET current_value=cap_value, version=version+1 WHERE owner_id=? AND resource_scope=? AND resource_type=? AND version=? AND current_value<=cap_value",
                actor.ownerId, policy.scope, policy.resourceType, locked.version);
    }

    private static void reject(ResourcePolicy policy) {
        if (policy == null || PrivateHomeLocationPolicy.deniesFunds(policy.scope, policy.resourceType)
                || !("STAMINA".equals(policy.resourceType) || "ENERGY".equals(policy.resourceType)))
            throw new ServiceException("RESOURCE_NOT_AUTHORIZED");
    }
}
