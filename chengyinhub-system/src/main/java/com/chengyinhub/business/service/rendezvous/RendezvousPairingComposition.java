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
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.FORMAL;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;

/**
 * Pairing composition. The property on this class defaults to false, so no bean is registered
 * until that configuration is turned on. {@link #formal} then validates one DataSource, session
 * and transaction manager and wires the real ledger charge. It does not insert rules or start a loop.
 * Reviewed rows in {@code w16_eligibility_fact} are the only age, region and window facts.
 */
@Configuration
public class RendezvousPairingComposition {
    public static final String ENABLED_PROPERTY = "chengyinhub.w16.pairing";
    public static final boolean DEFAULT_ENABLED = false;
    /** Reviewed fact table. Member profile columns are not a source. */
    public static final String FACT_PROVIDER = "w16_eligibility_fact";

    public static final class Pairing {
        public final RendezvousConsentService consent;
        public final RendezvousVersionedPolicy policy;
        public final RendezvousPoolScheduler scheduler;
        private Pairing(RendezvousConsentService consent, RendezvousVersionedPolicy policy, RendezvousPoolScheduler scheduler) {
            this.consent = consent;
            this.policy = policy;
            this.scheduler = scheduler;
        }
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = DEFAULT_ENABLED)
    public Pairing rendezvousPairing(DataSource authority, SqlSessionTemplate session, DataSourceTransactionManager manager) {
        return formal(authority, session, manager, Clock.systemUTC());
    }

    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = DEFAULT_ENABLED)
    public RendezvousConsentService rendezvousConsentService(Pairing pairing) { return pairing.consent; }

    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = DEFAULT_ENABLED)
    public RendezvousVersionedPolicy rendezvousVersionedPolicy(Pairing pairing) { return pairing.policy; }

    /** Property-on assembly. Uses {@link MyBatisRendezvousConsentStore.Mode#FORMAL}. Does not run a pair. */
    public static Pairing formal(DataSource authority, SqlSessionTemplate session, DataSourceTransactionManager manager, Clock clock) {
        return assemble(FORMAL, authority, session, manager, clock);
    }

    /** Isolated local trial. Not a Spring bean. */
    public static Pairing evaluation(DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager, Clock clock) {
        return assemble(ISOLATED_PERSISTENCE_EVALUATION, authority, session, manager, clock);
    }

    private static Pairing assemble(MyBatisRendezvousConsentStore.Mode mode, DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager, Clock clock) {
        requireConsistent(authority, session, manager);
        if (clock == null) throw new IllegalStateException("W16_PAIRING_DEPENDENCY_MISSING");
        MyBatisRendezvousConsentStore store = new MyBatisRendezvousConsentStore(mode, session, manager);
        RendezvousProtocolMapper protocol = session.getMapper(RendezvousProtocolMapper.class);
        RendezvousContactLedger ledger = new RendezvousContactLedger(session.getMapper(RendezvousContactMapper.class));
        MyBatisRendezvousWaiting waiting = new MyBatisRendezvousWaiting(protocol, ledger);
        RendezvousVersionedPolicy policy = new RendezvousVersionedPolicy(
                new MyBatisRendezvousCatalog(protocol, session.getMapper(RendezvousEligibilityMapper.class)),
                session.getMapper(ImBlockMapper.class));
        RendezvousConsentService consent = new RendezvousConsentService(store, policy, clock, ledger::charge);
        return new Pairing(consent, policy, new RendezvousPoolScheduler(consent, policy, waiting, clock));
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
