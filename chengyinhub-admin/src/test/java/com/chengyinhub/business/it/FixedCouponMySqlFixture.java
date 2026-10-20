package com.chengyinhub.business.it;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.domain.SmsCouponHistory;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.coupon.*;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL + original mappers/services. Not a FULL gameplay eligibility or production authority test. */
abstract class FixedCouponMySqlFixture {
    private static MysqlIntegrationTestConfig mysql;
    private static boolean created;
    protected DriverManagerDataSource source;
    protected JdbcTemplate jdbc;
    protected DataSourceTransactionManager manager;
    protected SqlSessionTemplate sessions;
    protected SmsCouponMapper coupons;
    protected SmsCouponHistoryMapper histories;
    protected CouponTermsSnapshotMapper snapshots;
    protected CmsTopicCompletionCouponRewardMapper logs;
    protected TopicCompletionCouponReceiptMapper receiptMapper;
    protected ISmsCouponHistoryService legacy;
    protected TopicCompletionCouponRewardServiceImpl target;
    protected ITopicCompletionCouponRewardService completion;
    protected TopicCompletionCouponReceiptService receipts;
    protected TopicCompletionCouponHistoryResolver resolver;
    protected RedemptionRewardClawbackService clawback;
    protected CouponTermsSnapshotService snapshotService;

    @BeforeAll static void createDatabase() throws Exception {
        mysql=FixedCouponMySqlTarget.from(System.getenv());
        try(Connection c=DriverManager.getConnection(mysql.adminUrl(),mysql.user(),mysql.password())) {
            assertTrue(MysqlIntegrationTestConfig.isMysql8OrNewer(c.getMetaData().getDatabaseProductVersion()));
            c.createStatement().execute("CREATE DATABASE `"+mysql.databaseName()+"` CHARACTER SET utf8mb4");
            created=true;
        }
    }
    protected static String isolatedDatabaseName(){return mysql.databaseName();}
    @AfterAll static void dropDatabase() throws Exception {
        if(created) try(Connection c=DriverManager.getConnection(mysql.adminUrl(),mysql.user(),mysql.password())) {
            c.createStatement().execute("DROP DATABASE `"+mysql.databaseName()+"`"); created=false;
        }
    }
    @BeforeEach void fixture() throws Exception {
        source=new DriverManagerDataSource(mysql.databaseUrl(),mysql.user(),mysql.password());
        jdbc=new JdbcTemplate(source); manager=new DataSourceTransactionManager(source);
        // Reset only the freshly created, uniquely named database; include views/negative-control guard tables.
        try(Connection c=source.getConnection()) {
            c.createStatement().execute("SET FOREIGN_KEY_CHECKS=0");
            for(Map<String,Object> row:jdbc.queryForList("SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.tables WHERE table_schema=DATABASE()")) {
                String name=(String)row.get("TABLE_NAME"); assertTrue(name.matches("[A-Za-z0-9_]+"));
                c.createStatement().execute(("VIEW".equals(row.get("TABLE_TYPE"))?"DROP VIEW `":"DROP TABLE `")+name+"`");
            }
            c.createStatement().execute("SET FOREIGN_KEY_CHECKS=1");
            ScriptUtils.executeSqlScript(c,new ClassPathResource("it-schema/fixed-coupon-mysql.sql"));
        }
        Configuration config=new Configuration(new Environment("fixed-coupon-isolated-mysql",new SpringManagedTransactionFactory(),source));
        config.getTypeAliasRegistry().registerAlias("SmsCoupon",SmsCoupon.class);
        config.getTypeAliasRegistry().registerAlias("SmsCouponHistory",SmsCouponHistory.class);
        for(String name:new String[]{"SmsCouponMapper","SmsCouponHistoryMapper","CouponTermsSnapshotMapper","CmsTopicCompletionCouponRewardMapper","TopicCompletionCouponReceiptMapper"}) {
            String path="/mapper/business/"+name+".xml";
            try(InputStream in=getClass().getResourceAsStream(path)) {
                assertNotNull(in,path); new XMLMapperBuilder(in,config,path,config.getSqlFragments()).parse();
            }
        }
        sessions=new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        coupons=sessions.getMapper(SmsCouponMapper.class); histories=sessions.getMapper(SmsCouponHistoryMapper.class);
        snapshots=sessions.getMapper(CouponTermsSnapshotMapper.class); logs=sessions.getMapper(CmsTopicCompletionCouponRewardMapper.class);
        receiptMapper=sessions.getMapper(TopicCompletionCouponReceiptMapper.class);
        SmsCouponHistoryServiceImpl original=new SmsCouponHistoryServiceImpl();
        ReflectionTestUtils.setField(original,"smsCouponMapper",coupons);
        ReflectionTestUtils.setField(original,"smsCouponHistoryMapper",histories);
        legacy=transactionProxy(original,ISmsCouponHistoryService.class);
        resolver=new TopicCompletionCouponHistoryResolver(receiptMapper,source);
        receipts=new TopicCompletionCouponReceiptService(receiptMapper,legacy,source);
        target=new TopicCompletionCouponRewardServiceImpl();
        ReflectionTestUtils.setField(target,"rewardMapper",logs);
        ReflectionTestUtils.setField(target,"smsCouponHistoryService",legacy);
        ReflectionTestUtils.setField(target,"receiptService",receipts);
        ReflectionTestUtils.setField(target,"receiptProtocol",resolver);
        completion=transactionProxy(target,ITopicCompletionCouponRewardService.class);
        clawback=new RedemptionRewardClawbackService();
        ReflectionTestUtils.setField(clawback,"completionCouponRewardMapper",logs);
        ReflectionTestUtils.setField(clawback,"smsCouponHistoryMapper",histories);
        ReflectionTestUtils.setField(clawback,"completionCouponReceipts",resolver);
        // Synthetic proof port tests storage only; it supplies no real-world authorization evidence.
        snapshotService=new CouponTermsSnapshotService(CouponTermsSnapshotService.Mode.ISOLATED_REVIEW,snapshots,legacy,
            i->new CouponTermsGrantAuthority.Approval(i,CouponTermsGrantAuthority.Disposition.COMMITTED_ELIGIBLE,Long.MAX_VALUE),manager,Clock.systemUTC());
    }
    protected <T> T transactionProxy(Object original,Class<T> contract) {
        ProxyFactory p=new ProxyFactory(original);p.setInterfaces(contract);
        p.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return contract.cast(p.getProxy());
    }
    protected boolean installed() { return new TransactionTemplate(manager).execute(s->resolver.protocolInstalled()); }
    protected CouponTermsGrantAuthority.Intent intent(String receipt,String nonce) {
        return new CouponTermsGrantAuthority.Intent(9,19,29,7,8,receipt,nonce,CouponTermsSnapshot.fromDefinition(coupons.selectSmsCouponById(8L)).getDigest());
    }
}
