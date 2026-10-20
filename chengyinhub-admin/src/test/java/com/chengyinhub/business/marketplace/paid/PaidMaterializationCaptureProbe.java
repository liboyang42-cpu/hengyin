package com.chengyinhub.business.marketplace.paid;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.Clock;
import static org.junit.jupiter.api.Assertions.*;
/** Test-only access to the package-private proof. Production constructors/visibility unchanged. */
public final class PaidMaterializationCaptureProbe {
 public static void assertLifetime(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock,String request,long owned){
  PaidPersistenceBoundary one=new PaidPersistenceBoundary(session,manager),two=new PaidPersistenceBoundary(session,manager);
  PaidInstalledTextReader reader=new PaidInstalledTextReader(one,clock,PaidInstalledTextReader.Policy.OWNER_PAID_COMMITTED_TEXT_READ_V1);
  PaidInstalledTextReader.Captured capture=one.inTransaction(()->{PaidInstalledTextReader.Captured c=reader.captureInCurrentTransaction(7,request,owned);c.requireCurrent(one,clock.instant());assertThrows(RuntimeException.class,()->c.requireCurrent(two,clock.instant()));return c;});
  assertThrows(RuntimeException.class,()->capture.requireCurrent(one,clock.instant()));
  one.inTransaction(()->{assertThrows(RuntimeException.class,()->capture.requireCurrent(one,clock.instant()));return null;});
 }
}
