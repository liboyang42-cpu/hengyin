package com.chengyinhub.business.service.rendezvous;

import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.ImBlockMapper;
import com.chengyinhub.business.mapper.ImConversationMapper;
import com.chengyinhub.business.mapper.ImConversationMemberMapper;
import com.chengyinhub.business.mapper.PlayTeamHandoffMapper;
import com.chengyinhub.business.mapper.PlayTeamMapper;
import com.chengyinhub.business.service.IImService;
import com.chengyinhub.business.service.IPlayTeamService;
import com.chengyinhub.business.service.impl.ImServiceImpl;
import com.chengyinhub.business.service.impl.PlayTeamServiceImpl;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.Objects;
import org.apache.ibatis.mapping.Environment;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Formal handoff assembly. The only input is the session. The transaction manager is created from
 * that session's data source, and every mapper the team and team-group calls use is taken from
 * that same session. Not a Spring bean and not a production switch.
 */
public final class PlayTeamHandoffDelegate {
    private PlayTeamHandoffDelegate() {}

    public static Wiring bind(SqlSessionTemplate session) {
        Objects.requireNonNull(session);
        Environment environment = session.getSqlSessionFactory().getConfiguration().getEnvironment();
        if (!(environment.getTransactionFactory() instanceof SpringManagedTransactionFactory)) {
            throw new IllegalArgumentException("W16_TRANSACTION_RESOURCE_MISMATCH");
        }
        DataSourceTransactionManager manager = new DataSourceTransactionManager(environment.getDataSource());
        PlayTeamMapper teams = session.getMapper(PlayTeamMapper.class);
        ImServiceImpl imTarget = new ImServiceImpl();
        set(imTarget, "conversationMapper", session.getMapper(ImConversationMapper.class));
        set(imTarget, "memberMapper", session.getMapper(ImConversationMemberMapper.class));
        set(imTarget, "playTeamMapper", teams);
        IImService grouped = transactional(imTarget, manager);
        PlayTeamServiceImpl target = new PlayTeamServiceImpl();
        set(target, "teamMapper", teams);
        set(target, "handoffMapper", session.getMapper(PlayTeamHandoffMapper.class));
        set(target, "blockMapper", session.getMapper(ImBlockMapper.class));
        set(target, "cmsActivityMapper", session.getMapper(CmsActivityMapper.class));
        set(target, "cmsTopicMapper", session.getMapper(CmsTopicMapper.class));
        set(target, "imService", teamGroupOnly(grouped));
        IPlayTeamService proxied = transactional(target, manager);
        PlayTeamHandoffGateway gateway = PlayTeamHandoffAssembly.guard(new PlayTeamBoundHandoff(proxied), manager);
        return new Wiring(session, manager, target, proxied, teams, gateway);
    }

    /** Forwards only team-group creation. Block writes stay on H's service. */
    private static IImService teamGroupOnly(IImService grouped) {
        return (IImService) Proxy.newProxyInstance(IImService.class.getClassLoader(), new Class<?>[]{IImService.class},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) return objectCall(proxy, method.getName(), args);
                    if ("getOrCreateTeamGroup".equals(method.getName()) && method.getParameterCount() == 2) {
                        return grouped.getOrCreateTeamGroup((Long) args[0], (Long) args[1]);
                    }
                    throw new IllegalStateException("W16_BLOCK_NOT_OWNED");
                });
    }

    private static Object objectCall(Object proxy, String name, Object[] args) {
        if ("toString".equals(name)) return "PlayTeamHandoffDelegate.teamGroupOnly";
        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
        if ("equals".equals(name)) return proxy == args[0];
        throw new IllegalStateException("W16_BLOCK_NOT_OWNED");
    }

    @SuppressWarnings("unchecked")
    private static <T> T transactional(T target, DataSourceTransactionManager manager) {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
        return (T) factory.getProxy();
    }

    private static void set(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("W16_HANDOFF_DELEGATE_INCOMPLETE", failure);
        }
    }

    /** Session, derived manager, real service proxy, and the guarded gateway. */
    public static final class Wiring {
        private final SqlSessionTemplate session;
        private final DataSourceTransactionManager manager;
        private final PlayTeamServiceImpl service;
        private final IPlayTeamService teams;
        private final PlayTeamMapper teamMapper;
        private final PlayTeamHandoffGateway gateway;

        Wiring(SqlSessionTemplate session, DataSourceTransactionManager manager, PlayTeamServiceImpl service,
               IPlayTeamService teams, PlayTeamMapper teamMapper, PlayTeamHandoffGateway gateway) {
            this.session = session;
            this.manager = manager;
            this.service = service;
            this.teams = teams;
            this.teamMapper = teamMapper;
            this.gateway = gateway;
        }

        public DataSourceTransactionManager manager() { return manager; }
        public PlayTeamServiceImpl service() { return service; }
        public IPlayTeamService teams() { return teams; }
        public PlayTeamMapper teamMapper() { return teamMapper; }
        public PlayTeamHandoffGateway gateway() { return gateway; }

        public MyBatisRendezvousConsentStore consent(MyBatisRendezvousConsentStore.Mode mode) {
            return new MyBatisRendezvousConsentStore(mode, session, manager);
        }

        public MyBatisRendezvousMeetingStore meetings(MyBatisRendezvousMeetingStore.Mode mode) {
            return new MyBatisRendezvousMeetingStore(mode, session, manager);
        }
    }
}
