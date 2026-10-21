package com.chengyinhub.business.home;

import ch.qos.logback.classic.*;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.alibaba.druid.pool.DruidDataSource;
import com.chengyinhub.business.home.PrivateHomeContracts.*;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.framework.privacy.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MyBatis XML, Druid, transaction proxy and privacy aspect together; no production data. */
class PrivateHomePrivacyTransactionTest {
    @Test void fullMapperPathRedactsSuccessfulReadsWritesAndConstraintFailure() throws Exception {
        PrivateHomeStatFilter stats=new PrivateHomeStatFilter();stats.setLogSlowSql(true);stats.setSlowSqlMillis(-1);stats.setMergeSql(true);
        try(DruidDataSource ds=new PrivateHomeDruidDataSource()) {
            ds.setUrl("jdbc:h2:mem:privateLogging"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
            ds.setDriverClassName("org.h2.Driver");ds.setUsername("sa");ds.setPassword("");ds.setTestWhileIdle(false);
            ds.setProxyFilters(Collections.singletonList(stats));
            DruidDataSource pool=ds;
            PrivateHomeTransactionTest fixture=new PrivateHomeTransactionTest(){@Override DataSource createDataSource(){return pool;}};
            fixture.setup();fixture.fixture.fixedGrant="private-opaque-grant";
            AspectJProxyFactory proxy=new AspectJProxyFactory(fixture.service);proxy.setProxyTargetClass(true);proxy.addAspect(new PrivateHomePrivacyBoundary());
            PrivateHomeService service=proxy.getProxy();
            LoggerContext context=(LoggerContext)LoggerFactory.getILoggerFactory();Logger root=context.getLogger(Logger.ROOT_LOGGER_NAME);
            Map<Logger,Level> old=new LinkedHashMap<>();
            for(String name:Arrays.asList(Logger.ROOT_LOGGER_NAME,"com.chengyinhub.business.mapper.PrivateHomeMapper","com.chengyinhub.framework.privacy.PrivateHomePrivacyBoundary","org.springframework.jdbc")) {
                Logger logger=context.getLogger(name);old.put(logger,logger.getLevel());logger.setLevel(Level.TRACE);
            }
            PrivateHomeLogFilter guard=new PrivateHomeLogFilter();guard.start();context.addTurboFilter(guard);
            ListAppender<ILoggingEvent> capture=new ListAppender<>();capture.start();root.addAppender(capture);
            try {
                assertEquals("SAVED",service.setMyHome("set-private",0,"PRIVATE_LABEL",fixture.point()).decision);
                assertEquals(fixture.point().latitude,service.getMyHome().latitude);
                Proof proof=service.verifyReturn("verify-private",1,"run:1","private-evidence");
                Receipt receipt=service.restore("restore-private",proof.proofId,"run:1");assertEquals("RESTORED",receipt.decision);
                ServiceException rejected=assertThrows(ServiceException.class,()->service.verifyReturn("duplicate-private",1,"run:1","private-evidence"));
                assertEquals("REQUEST_CONFLICT",rejected.getMessage());assertNull(rejected.getCause());
                assertEquals(1,fixture.count("player_private_home_proof"));
                StringBuilder logs=new StringBuilder();for(ILoggingEvent event:capture.list) {
                    logs.append(event.getLoggerName()).append(event.getFormattedMessage()).append(event.getMDCPropertyMap());
                    if(event.getThrowableProxy()!=null)logs.append(event.getThrowableProxy().getMessage());
                }
                for(String secret:Arrays.asList("30.123456","120.123456","PRIVATE_LABEL","private-opaque-grant","private-evidence","restore-private"))
                    assertFalse(logs.toString().contains(secret),secret);
                assertTrue(logs.toString().contains("PrivateHomeMapper.writeHome"));assertTrue(logs.toString().contains("event=slow-sql"));
                assertTrue(logs.toString().contains("boundary=service"));assertFalse(PrivateHomeLogScope.active());
                assertEquals(1,fixture.count("player_private_home_restore"));assertEquals(10,fixture.balance("run:1"));
                assertEquals(receipt.receiptId,service.restore("restore-private",proof.proofId,"run:1").receiptId);
            } finally {
                root.detachAppender(capture);context.getTurboFilterList().remove(guard);old.forEach(Logger::setLevel);
            }
        }
    }
}
