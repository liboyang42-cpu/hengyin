package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import com.chengyinhub.business.domain.ImBlock;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/** Isolated block adapter. Explicitly constructed. Not a Spring bean and not wired to
 * {@code ImServiceImpl.block}.
 *
 * <p>The three-argument constructor does this and nothing else:
 * {@code new RendezvousBlockIntegration(mode, session, manager, RendezvousBlockIntegration.guardStop())}.
 * {@code guardStop()} calls {@code RendezvousConsentService.stopBlockedPair}.
 * It does not select {@code unwired()}, and it does not call
 * {@code RendezvousConsentService.terminateCurrentMatch}. Callers that hold a consent
 * service use {@code RendezvousBlockIntegration.consentStop(consent)} for that instance method.
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
