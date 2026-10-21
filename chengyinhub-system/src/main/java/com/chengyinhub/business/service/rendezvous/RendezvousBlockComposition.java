package com.chengyinhub.business.service.rendezvous;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
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

/**
 * Default-off composition root for one {@link RendezvousBlockEntry}.
 * {@link #DEFAULT_ENABLED} is false, so the property is not read from application config.
 * {@link MyBatisRendezvousConsentStore.Mode} has no production member; {@link #FORMAL_MODE}
 * stays {@link FormalMode#UNCONTRACTED}. {@link #formal} never opens the isolated mode.
 */
@Configuration
public class RendezvousBlockComposition {
    public static final String ENABLED_PROPERTY = "chengyinhub.w16.block-termination";
    public static final boolean DEFAULT_ENABLED = false;
    /** Consent store has no formal persistence mode. Do not treat isolated evaluation as one. */
    public enum FormalMode { UNCONTRACTED }
    public static final FormalMode FORMAL_MODE = FormalMode.UNCONTRACTED;

    @Bean
    @ConditionalOnProperty(name = ENABLED_PROPERTY, havingValue = "true", matchIfMissing = DEFAULT_ENABLED)
    public RendezvousBlockEntry rendezvousBlockEntry(DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager, ImBlockMapper blocks, RendezvousConsentService consent) {
        return formal(authority, session, manager, blocks, consent);
    }

    /** Flag on and resources agree. Still refuses, because formal mode is uncontracted. */
    public static RendezvousBlockEntry formal(DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager, ImBlockMapper blocks, RendezvousConsentService consent) {
        requireConsistent(authority, session, manager, blocks, consent);
        throw new IllegalStateException("W16_BLOCK_FORMAL_MODE_UNCONTRACTED");
    }

    /** Explicit isolated evaluation. Not a Spring bean and not the formal assembly. */
    public static RendezvousBlockEntry evaluation(DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager, ImBlockMapper blocks, RendezvousConsentService consent) {
        requireConsistent(authority, session, manager, blocks, consent);
        RendezvousBlockEntry entry = RendezvousBlockEntry.open(ISOLATED_PERSISTENCE_EVALUATION, session, manager, consent);
        if (!entry.connected()) throw new IllegalStateException("W16_BLOCK_NOT_CONNECTED");
        return entry;
    }

    private static void requireConsistent(DataSource authority, SqlSessionTemplate session,
            DataSourceTransactionManager manager, ImBlockMapper blocks, RendezvousConsentService consent) {
        if (authority == null || session == null || manager == null || blocks == null || consent == null) {
            throw new IllegalStateException("W16_BLOCK_DEPENDENCY_MISSING");
        }
        Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        if (environment == null || environment.getDataSource() != authority || manager.getDataSource() != authority
                || !(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)) {
            throw new IllegalStateException("W16_BLOCK_RESOURCE_MISMATCH");
        }
        if (!session.getConfiguration().hasMapper(ImBlockMapper.class)) {
            throw new IllegalStateException("W16_BLOCK_MAPPER_MISSING");
        }
        SqlSession bound = sessionOf(blocks);
        if (bound.getConfiguration() != session.getConfiguration()
                || bound.getConfiguration().getEnvironment().getDataSource() != authority) {
            throw new IllegalStateException("W16_BLOCK_MAPPER_MISMATCH");
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
}
