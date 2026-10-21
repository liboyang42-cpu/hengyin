package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import com.chengyinhub.business.domain.ImBlock;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Isolated block adapter. Explicitly constructed. Not a Spring bean and not wired to
 * {@code ImServiceImpl.block}. The three-argument constructor binds {@code guardStop},
 * the existing in-tree pair stop, because A's {@code terminateCurrentMatch} is absent.
 * A missing terminate port must use {@link RendezvousBlockIntegration#unwired()} and fails closed.
 */
public final class RendezvousImBlockMutation implements ImBlockMutation {
    private final RendezvousBlockIntegration integration;

    public RendezvousImBlockMutation(MyBatisRendezvousConsentStore.Mode mode,
            SqlSessionTemplate session, DataSourceTransactionManager manager) {
        this(new RendezvousBlockIntegration(mode, session, manager, RendezvousBlockIntegration.guardStop()));
    }

    public RendezvousImBlockMutation(RendezvousBlockIntegration integration) {
        this.integration = Objects.requireNonNull(integration);
    }

    public void insert(ImBlock block) {
        integration.block(block);
    }
}
