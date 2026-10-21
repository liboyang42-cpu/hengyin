package com.chengyinhub.business.service.rendezvous;

import java.util.Objects;
import com.chengyinhub.business.domain.ImBlock;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;

/**
 * Optional W16 block path for {@code ImServiceImpl.block}. Not a Spring bean.
 * Missing, disabled, or null consent stays disconnected so the caller keeps the
 * existing {@code im_block} insert. A connected entry uses {@code consentStop}
 * on the supplied session and transaction manager. It does not use {@code guardStop}
 * or the three-argument mutation.
 */
public final class RendezvousBlockEntry {
    private final RendezvousBlockIntegration integration;

    private RendezvousBlockEntry(RendezvousBlockIntegration integration) {
        this.integration = integration;
    }

    public static RendezvousBlockEntry disabled() {
        return new RendezvousBlockEntry(null);
    }

    /** Connected only for an explicit consent service in the isolated evaluation mode. */
    public static RendezvousBlockEntry open(MyBatisRendezvousConsentStore.Mode mode, SqlSessionTemplate session,
            DataSourceTransactionManager manager, RendezvousConsentService consent) {
        if (mode != ISOLATED_PERSISTENCE_EVALUATION || consent == null) return disabled();
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(manager, "manager");
        return new RendezvousBlockEntry(new RendezvousBlockIntegration(mode, session, manager,
                RendezvousBlockIntegration.consentStop(consent)));
    }

    public boolean connected() { return integration != null; }

    public void block(ImBlock block) {
        if (integration == null) throw new IllegalStateException("W16_BLOCK_NOT_CONNECTED");
        integration.block(block);
    }
}
