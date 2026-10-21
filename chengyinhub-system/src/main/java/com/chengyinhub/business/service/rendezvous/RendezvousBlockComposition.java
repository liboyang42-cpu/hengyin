package com.chengyinhub.business.service.rendezvous;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Clock;
import javax.sql.DataSource;
import com.chengyinhub.business.mapper.ImBlockMapper;
import org.apache.ibatis.binding.MapperProxy;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import static com.chengyinhub.business.service.rendezvous.MyBatisRendezvousConsentStore.Mode.ISOLATED_PERSISTENCE_EVALUATION;
import static com.chengyinhub.business.service.rendezvous.RendezvousConsentService.Rejection;

/**
 * Default-off composition root. The enable flag lives here and defaults to false.
 * {@link #formal} binds the session's block mapper and A's consent store on that same
 * session, data source and transaction manager. {@link #evaluation} is not a bean.
 * A's {@code Mode} enum still has no separate production member; the store writes only in
 * {@code ISOLATED_PERSISTENCE_EVALUATION}. This class does not register an in-memory stand-in.
 */
@Configuration
public class RendezvousBlockComposition {
    public static final String ENABLED_PROPERTY = "chengyinhub.w16.block-termination";
    public static final boolean DEFAULT_ENABLED = false;
    /** Wired to the consent store's only writable mode. Not an in-memory evaluation bean. */
    public enum FormalMode { PERSISTED }
    public static final FormalMode FORMAL_MODE = FormalMode.PERSISTED;

    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = DEFAULT_ENABLED)
    public RendezvousBlockEntry rendezvousBlockEntry(DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager) {
        return formal(authority, session, manager, Clock.systemUTC());
    }

    /** Enabled flag plus one session, data source and transaction manager. Builds the real entry. */
    public static RendezvousBlockEntry formal(DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager, Clock clock) {
        requireSession(authority, session, manager);
        if (clock == null) throw new IllegalStateException("W16_BLOCK_DEPENDENCY_MISSING");
        MyBatisRendezvousConsentStore store = new MyBatisRendezvousConsentStore(
                ISOLATED_PERSISTENCE_EVALUATION, session, manager);
        ImBlockMapper blocks = session.getMapper(ImBlockMapper.class);
        RendezvousConsentService consent = new RendezvousConsentService(store,
                new RendezvousSafetyAdapter(blocks, ClosedPolicy.INSTANCE), clock,
                (matchId, first, second, pool, now) -> { throw new Rejection("POLICY_CLOSED"); });
        return RendezvousBlockEntry.persisted(store, blocks, consent, clock);
    }

    /** Explicit isolated evaluation for tests. Not a Spring bean and not the formal assembly. */
    public static RendezvousBlockEntry evaluation(DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager, ImBlockMapper blocks, RendezvousConsentService consent) {
        requireSession(authority, session, manager);
        if (blocks == null || consent == null) throw new IllegalStateException("W16_BLOCK_DEPENDENCY_MISSING");
        if (!session.getConfiguration().hasMapper(ImBlockMapper.class)) {
            throw new IllegalStateException("W16_BLOCK_MAPPER_MISSING");
        }
        SqlSession bound = sessionOf(blocks);
        if (bound.getConfiguration() != session.getConfiguration()
                || bound.getConfiguration().getEnvironment().getDataSource() != authority) {
            throw new IllegalStateException("W16_BLOCK_MAPPER_MISMATCH");
        }
        RendezvousBlockEntry entry = RendezvousBlockEntry.open(ISOLATED_PERSISTENCE_EVALUATION, session, manager, consent);
        if (!entry.connected()) throw new IllegalStateException("W16_BLOCK_NOT_CONNECTED");
        return entry;
    }

    private static void requireSession(DataSource authority, SqlSessionTemplate session, DataSourceTransactionManager manager) {
        if (authority == null || session == null || manager == null) {
            throw new IllegalStateException("W16_BLOCK_DEPENDENCY_MISSING");
        }
        Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        if (environment == null || environment.getDataSource() != authority || manager.getDataSource() != authority
                || !(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)) {
            throw new IllegalStateException("W16_BLOCK_RESOURCE_MISMATCH");
        }
        if (!session.getConfiguration().hasMapper(ImBlockMapper.class)
                || !session.getConfiguration().hasMapper(RendezvousConsentMapper.class)) {
            throw new IllegalStateException("W16_BLOCK_MAPPER_MISSING");
        }
    }

    // ponytail: MapperProxy.sqlSession is private. A public accessor replaces this; failure stays closed.
    private static SqlSession sessionOf(ImBlockMapper blocks) {
        if (!Proxy.isProxyClass(blocks.getClass())) throw new IllegalStateException("W16_BLOCK_MAPPER_MISMATCH");
        InvocationHandler handler = Proxy.getInvocationHandler(blocks);
        if (!(handler instanceof MapperProxy<?>)) throw new IllegalStateException("W16_BLOCK_MAPPER_MISMATCH");
        try {
            Field field = MapperProxy.class.getDeclaredField("sqlSession");
            field.setAccessible(true);
            Object value = field.get(handler);
            if (value instanceof SqlSession) return (SqlSession) value;
        } catch (ReflectiveOperationException ignored) {
            // closed below
        }
        throw new IllegalStateException("W16_BLOCK_MAPPER_MISMATCH");
    }

    /** Block termination does not admit a new pair. Missing quota stays closed. */
    private enum ClosedPolicy implements RendezvousSafetyAdapter.ReviewedPolicy {
        INSTANCE;
        public boolean eligible(long actor, String pool, String eligibilityVersion, long now) { return false; }
        public boolean publicMeetingAllowed(String pool, String place, long now) { return false; }
        public boolean compatible(long firstActor, long secondActor, String pool, long now) { return false; }
    }
}
