package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.UmsMemberFollowServiceImpl;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.TimeUnit;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R10-07 收尾(独立审查 P2-1):后台 edit 与新增/取消同锁。
 * 真实 MySQL8、两独立事务、无唯一索引(不依赖生产迁移已执行):
 * 并发把两行改到同一 target 只能一行成功;并发新增+编辑不重复;取消/遗留目标仍可解除。
 */
@EnabledIfEnvironmentVariable(named = "CHENGYIN_FUNDS_MYSQL_IT", matches = "1")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FollowEditRaceMybatisIntegrationTest {

    private static final MysqlIntegrationTestConfig MYSQL =
            MysqlIntegrationTestConfig.fromEnvironment("follow_edit_race_it");

    private DataSource dataSource;
    private IUmsMemberFollowService service;

    @BeforeAll
    void createDatabaseAndServices() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
            s.execute("CREATE DATABASE " + MYSQL.databaseName() + " CHARACTER SET utf8mb4");
        }
        dataSource = dataSource(MYSQL.databaseUrl());
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        // 同一个事务管理器:模板与代理必须绑同一 manager,锁与提交才在同一事务里
        PlatformTransactionManager manager = new DataSourceTransactionManager(dataSource);

        UmsMemberFollowServiceImpl target = new UmsMemberFollowServiceImpl();
        ReflectionTestUtils.setField(target, "umsMemberFollowMapper", template.getMapper(UmsMemberFollowMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", template.getMapper(UmsMemberMapper.class));
        service = transactionalProxy(target, IUmsMemberFollowService.class, manager);
    }

    @BeforeEach
    void resetSchema() throws Exception {
        execute("DROP TABLE IF EXISTS ums_member_follow", "DROP TABLE IF EXISTS ums_member");
        execute("CREATE TABLE ums_member (id BIGINT PRIMARY KEY, status INT, del_flag INT) ENGINE=InnoDB",
                "INSERT INTO ums_member(id,status,del_flag) VALUES (1,1,0),(2,1,0),(88,1,0),(100,1,0),(200,1,0)",
                // 无唯一索引:复刻生产当前态,不依赖 migration 已落库
                "CREATE TABLE ums_member_follow (id BIGINT PRIMARY KEY AUTO_INCREMENT, member_id BIGINT, follow_member_id BIGINT,"
                        + " create_by VARCHAR(64), create_time DATETIME(3), update_by VARCHAR(64), update_time DATETIME(3)) ENGINE=InnoDB",
                "INSERT INTO ums_member_follow(id,member_id,follow_member_id) VALUES (1,1,100),(2,1,200)");
    }

    @AfterAll
    void dropDatabase() throws Exception {
        DriverManagerDataSource admin = dataSource(MYSQL.adminUrl());
        try (Connection c = admin.getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + MYSQL.databaseName());
        }
    }

    @Test
    void concurrentEditsToSameTargetKeepSingleRelationRow() throws Exception {
        List<IUmsMemberFollowService.EditResult.Kind> kinds = new CopyOnWriteArrayList<>();
        runConcurrently(
                () -> kinds.add(editResult(1L, 88L)),
                () -> kinds.add(editResult(2L, 88L)));

        assertTrue(kinds.contains(IUmsMemberFollowService.EditResult.Kind.UPDATED), kinds.toString());
        assertTrue(kinds.contains(IUmsMemberFollowService.EditResult.Kind.CONFLICT), kinds.toString());
        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=1 AND follow_member_id=88"),
                "两行并发改到同一 target 只能留一行关系");
        assertEquals(2, number("SELECT COUNT(*) FROM ums_member_follow"), "失败方不得新增/删除行");
    }

    @Test
    void concurrentAddAndEditSamePairDoNotDuplicate() throws Exception {
        runConcurrently(
                () -> service.applyFollowState(1L, 88L, Boolean.TRUE),
                () -> editResult(1L, 88L));

        assertEquals(1, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=1 AND follow_member_id=88"),
                "并发新增+编辑同一关系只能一行");
    }

    @Test
    void editThenCancelRemovesRelation() throws Exception {
        assertEquals(IUmsMemberFollowService.EditResult.Kind.UPDATED, editResult(1L, 88L));
        assertEquals(IUmsMemberFollowService.ApplyResult.UNFOLLOWED,
                service.applyFollowState(1L, 88L, Boolean.FALSE));
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=1 AND follow_member_id=88"));
    }

    @Test
    void pairChangeToMissingTargetRejectedButUnchangedPairAllowed() throws Exception {
        execute("INSERT INTO ums_member_follow(id,member_id,follow_member_id) VALUES (3,1,999)");

        // 建立新关系 → 目标必须存在
        assertEquals(IUmsMemberFollowService.EditResult.Kind.INVALID_MEMBER, editResult(1L, 999L));
        assertEquals("100", string("SELECT follow_member_id FROM ums_member_follow WHERE id=1"));

        // 原样维护(不换 pair)目标已注销也必须允许
        UmsMemberFollow touch = new UmsMemberFollow();
        touch.setId(3L);
        touch.setUpdateBy("ops");
        assertEquals(IUmsMemberFollowService.EditResult.Kind.UPDATED,
                service.editUmsMemberFollowLocked(touch).getKind());
    }

    @Test
    void unfollowStillAllowedWhenTargetIsGone() throws Exception {
        execute("INSERT INTO ums_member_follow(id,member_id,follow_member_id) VALUES (4,1,999)");

        assertEquals(IUmsMemberFollowService.ApplyResult.UNFOLLOWED,
                service.applyFollowState(1L, 999L, Boolean.FALSE));
        assertEquals(0, number("SELECT COUNT(*) FROM ums_member_follow WHERE member_id=1 AND follow_member_id=999"));
    }

    @Test
    void cancellationHoldingMemberLockMustNotDeadlockWithEdit() throws Exception {
        CountDownLatch cancelHasMember = new CountDownLatch(1);
        CountDownLatch editAttemptsMember = new CountDownLatch(1);
        CountDownLatch allowDelete = new CountDownLatch(1);
        IUmsMemberFollowService cancelService = withMemberLockHook(() -> {}, () -> {
            cancelHasMember.countDown();
            awaitBounded(allowDelete);
        });
        IUmsMemberFollowService editService = withMemberLockHook(editAttemptsMember::countDown, () -> {});
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<IUmsMemberFollowService.ApplyResult> cancel = pool.submit(
                    () -> cancelService.applyFollowState(1L, 100L, Boolean.FALSE));
            assertTrue(cancelHasMember.await(5, TimeUnit.SECONDS));
            Future<IUmsMemberFollowService.EditResult.Kind> edit = pool.submit(() -> {
                UmsMemberFollow update = new UmsMemberFollow();
                update.setId(1L);
                update.setFollowMemberId(88L);
                return editService.editUmsMemberFollowLocked(update).getKind();
            });
            assertTrue(editAttemptsMember.await(5, TimeUnit.SECONDS));
            allowDelete.countDown();
            assertEquals(IUmsMemberFollowService.ApplyResult.UNFOLLOWED, cancel.get(10, TimeUnit.SECONDS));
            assertEquals(IUmsMemberFollowService.EditResult.Kind.NOT_FOUND, edit.get(10, TimeUnit.SECONDS));
            assertEquals(0, number("SELECT COUNT(*) FROM ums_member_follow WHERE id=1"));
            assertEquals(1, number("SELECT COUNT(*) FROM ums_member_follow WHERE id=2"));
        } finally {
            allowDelete.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentOwnerChangeRequiresRefreshThenRetryUsesCurrentOwner() throws Exception {
        CountDownLatch editReadSnapshot = new CountDownLatch(1);
        CountDownLatch ownerChanged = new CountDownLatch(1);
        IUmsMemberFollowService waitingEdit = withMemberLockHook(() -> {
            editReadSnapshot.countDown();
            awaitBounded(ownerChanged);
        }, () -> {});
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<IUmsMemberFollowService.EditResult.Kind> edit = pool.submit(() -> {
                UmsMemberFollow update = new UmsMemberFollow();
                update.setId(1L);
                update.setFollowMemberId(88L);
                return waitingEdit.editUmsMemberFollowLocked(update).getKind();
            });
            assertTrue(editReadSnapshot.await(5, TimeUnit.SECONDS));
            UmsMemberFollow transfer = new UmsMemberFollow();
            transfer.setId(1L);
            transfer.setMemberId(2L);
            assertEquals(IUmsMemberFollowService.EditResult.Kind.UPDATED,
                    service.editUmsMemberFollowLocked(transfer).getKind());
            ownerChanged.countDown();
            assertEquals(IUmsMemberFollowService.EditResult.Kind.CONCURRENT_CHANGE, edit.get(10, TimeUnit.SECONDS));
            assertEquals("2", string("SELECT member_id FROM ums_member_follow WHERE id=1"));
            assertEquals("100", string("SELECT follow_member_id FROM ums_member_follow WHERE id=1"));
            assertEquals(IUmsMemberFollowService.EditResult.Kind.UPDATED, editResult(1L, 88L));
            assertEquals("2", string("SELECT member_id FROM ums_member_follow WHERE id=1"));
            assertEquals("88", string("SELECT follow_member_id FROM ums_member_follow WHERE id=1"));
        } finally {
            ownerChanged.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(15, TimeUnit.SECONDS));
        }
    }

    // 只在真实Mapper调用前后排定并发窗口，SQL、服务、Spring事务均为生产实现。
    private IUmsMemberFollowService withMemberLockHook(Runnable before, Runnable after) throws Exception {
        SqlSessionTemplate template = new SqlSessionTemplate(buildFactory(dataSource));
        UmsMemberMapper mapper = template.getMapper(UmsMemberMapper.class);
        UmsMemberMapper observed = (UmsMemberMapper) Proxy.newProxyInstance(
                UmsMemberMapper.class.getClassLoader(), new Class<?>[]{UmsMemberMapper.class}, (proxy, method, args) -> {
                    boolean lock = method.getName().equals("lockMemberById");
                    if (lock) before.run();
                    Object result;
                    try { result = method.invoke(mapper, args); }
                    catch (InvocationTargetException ex) { throw ex.getCause(); }
                    if (lock) after.run();
                    return result;
                });
        UmsMemberFollowServiceImpl target = new UmsMemberFollowServiceImpl();
        ReflectionTestUtils.setField(target, "umsMemberFollowMapper", template.getMapper(UmsMemberFollowMapper.class));
        ReflectionTestUtils.setField(target, "umsMemberMapper", observed);
        return transactionalProxy(target, IUmsMemberFollowService.class, new DataSourceTransactionManager(dataSource));
    }

    private static void awaitBounded(CountDownLatch latch) {
        try { assertTrue(latch.await(10, TimeUnit.SECONDS), "并发检查点未到达"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new AssertionError(ex); }
    }

    private IUmsMemberFollowService.EditResult.Kind editResult(Long id, Long targetId) {
        UmsMemberFollow update = new UmsMemberFollow();
        update.setId(id);
        update.setFollowMemberId(targetId);
        return service.editUmsMemberFollowLocked(update).getKind();
    }

    private void runConcurrently(Runnable a, Runnable b) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> fa = pool.submit(() -> {
                ready.countDown();
                awaitQuietly(start);
                a.run();
                return null;
            });
            Future<?> fb = pool.submit(() -> {
                ready.countDown();
                awaitQuietly(start);
                b.run();
                return null;
            });
            ready.await();
            start.countDown();
            fa.get();
            fb.get();
        } finally {
            pool.shutdownNow();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private int number(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String string(String sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private void execute(String... sql) throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String statement : sql) {
                s.execute(statement);
            }
        }
    }

    private SqlSessionFactory buildFactory(DataSource ds) throws Exception {
        Configuration configuration = new Configuration(new Environment("follow-edit-race-it",
                new SpringManagedTransactionFactory(), ds));
        configuration.getTypeAliasRegistry().registerAliases("com.chengyinhub.business.domain");
        addMapper(configuration, UmsMemberFollowMapper.class, "mapper/business/UmsMemberFollowMapper.xml");
        addMapper(configuration, UmsMemberMapper.class, "mapper/business/UmsMemberMapper.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void addMapper(Configuration configuration, Class<?> mapper, String resource) throws Exception {
        configuration.addMapper(mapper);
        try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("找不到 MyBatis XML: " + resource);
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    @SuppressWarnings("unchecked")
    private <T> T transactionalProxy(Object target, Class<T> iface, PlatformTransactionManager transactionManager) {
        TransactionInterceptor interceptor = new TransactionInterceptor(transactionManager,
                new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory(target);
        factory.setInterfaces(iface);
        factory.addAdvice(interceptor);
        return (T) factory.getProxy();
    }

    private DriverManagerDataSource dataSource(String url) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        ds.setUrl(url);
        ds.setUsername(MYSQL.user());
        ds.setPassword(MYSQL.password());
        return ds;
    }
}
