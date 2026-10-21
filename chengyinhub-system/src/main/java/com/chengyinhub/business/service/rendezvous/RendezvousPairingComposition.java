package com.chengyinhub.business.service.rendezvous;

import java.time.Clock;
import javax.sql.DataSource;
import com.chengyinhub.business.mapper.ImBlockMapper;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;

/**
 * Default-off pairing composition. The property lives on this class and defaults to false.
 * Formal mode is uncontracted, so the bean method does not construct a scheduler.
 * {@link #evaluation} is not a bean. It does not run a pair and does not insert rules,
 * facts, or quota. Reviewed eligibility rows are the only facts; no member-profile reader is wired.
 */
@Configuration
public class RendezvousPairingComposition {
    public static final String ENABLED_PROPERTY = "chengyinhub.w16.pairing";
    public static final boolean DEFAULT_ENABLED = false;
    /** Consent store has no formal persistence mode, and no fact provider is contracted. */
    public enum FormalMode { UNCONTRACTED }
    public static final FormalMode FORMAL_MODE = FormalMode.UNCONTRACTED;
    public static final String FACT_PROVIDER = "UNCONTRACTED";

    public static final class Pairing {
        public final RendezvousConsentService consent;
        public final RendezvousPoolScheduler scheduler;
        private Pairing(RendezvousConsentService consent, RendezvousPoolScheduler scheduler) {
            this.consent = consent;
            this.scheduler = scheduler;
        }
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = DEFAULT_ENABLED)
    public Pairing rendezvousPairing(DataSource authority, SqlSessionTemplate session, DataSourceTransactionManager manager) {
        return formal(authority, session, manager);
    }

    /** Resources may agree. Formal mode still refuses and does not build a scheduler. */
    public static Pairing formal(DataSource authority, SqlSessionTemplate session, DataSourceTransactionManager manager) {
        requireConsistent(authority, session, manager);
        throw new IllegalStateException("W16_PAIRING_FORMAL_MODE_UNCONTRACTED");
    }

    /** Explicit isolated evaluation. Not a Spring bean and not started. */
    public static Pairing evaluation(DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager, Clock clock) {
        requireConsistent(authority, session, manager);
        if (clock == null) throw new IllegalStateException("W16_PAIRING_DEPENDENCY_MISSING");
        MyBatisRendezvousConsentStore store = new MyBatisRendezvousConsentStore(ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        RendezvousProtocolMapper protocol = session.getMapper(RendezvousProtocolMapper.class);
        RendezvousContactLedger ledger = new RendezvousContactLedger(session.getMapper(RendezvousContactMapper.class));
        MyBatisRendezvousWaiting waiting = new MyBatisRendezvousWaiting(protocol, ledger);
        RendezvousVersionedPolicy policy = new RendezvousVersionedPolicy(
                new MyBatisRendezvousCatalog(protocol, session.getMapper(RendezvousEligibilityMapper.class)),
                session.getMapper(ImBlockMapper.class));
        RendezvousConsentService consent = new RendezvousConsentService(store, policy, clock, waiting);
        return new Pairing(consent, new RendezvousPoolScheduler(consent, policy, waiting, clock));
    }

    private static void requireConsistent(DataSource authority, SqlSessionTemplate session, DataSourceTransactionManager manager) {
        if (authority == null || session == null || manager == null) throw new IllegalStateException("W16_PAIRING_DEPENDENCY_MISSING");
        Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        if (environment == null || environment.getDataSource() != authority || manager.getDataSource() != authority
                || !(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)) {
            throw new IllegalStateException("W16_PAIRING_RESOURCE_MISMATCH");
        }
        if (!session.getConfiguration().hasMapper(RendezvousConsentMapper.class)
                || !session.getConfiguration().hasMapper(RendezvousProtocolMapper.class)
                || !session.getConfiguration().hasMapper(RendezvousContactMapper.class)
                || !session.getConfiguration().hasMapper(RendezvousEligibilityMapper.class)
                || !session.getConfiguration().hasMapper(ImBlockMapper.class)) {
            throw new IllegalStateException("W16_PAIRING_MAPPER_MISSING");
        }
    }
}
