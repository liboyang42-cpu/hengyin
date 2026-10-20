package com.chengyinhub.business.home;
import com.chengyinhub.business.home.PrivateHomeContracts.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
/** Independent synthetic reviewer probes. H2 evidence only. */
class PrivateHomeBoundaryReviewTest extends PrivateHomeTransactionTest {
 @Test void everyActivePrivateCoordinateFieldRejectsNull() {
  home();
  for(String field:new String[]{"label","latitude","longitude","datum"})
   assertThrows(Exception.class,()->jdbc.update("UPDATE player_private_home SET "+field+"=NULL WHERE owner_id=7"),field);
 }
 @Test void everySuccessfulReceiptValueRejectsNull() {
  Proof p=proof(); service.restore("r",p.proofId,"run:1");
  for(String field:new String[]{"before_value","after_value","amount"})
   assertThrows(Exception.class,()->jdbc.update("UPDATE player_private_home_restore SET "+field+"=NULL WHERE owner_id=7"),field);
 }
 @Test void foreignProofCannotRestoreOrDeleteOwnersHome() {
  Proof p=proof(); fixture.owner.set(8L);
  assertEquals("PROOF_INVALID",service.restore("steal",p.proofId,"run:1").decision);
  assertEquals("DELETED",service.deleteMyHome("delete",0).decision);
  assertEquals(4L,jdbc.queryForObject("SELECT current_value FROM test_resource WHERE owner_id=8 AND scope='run:1'",Long.class));
  fixture.owner.set(7L); assertEquals(1L,service.getMyHome().version); assertEquals("ACTIVE",service.getMyHome().status);
  assertEquals("RESTORED",service.restore("good",p.proofId,"run:1").decision);
 }
 @Test void exactExpiryBoundaryRejectsCredit() {
  Proof p=proof();ReflectionTestUtils.setField(target,"clock",Clock.fixed(Instant.ofEpochMilli(p.expiresAt),ZoneOffset.UTC));
  assertEquals("PROOF_EXPIRED",service.restore("r",p.proofId,"run:1").decision);assertEquals(2,balance("run:1"));
 }
 @Test void revokedGrantCannotMintUnderNewHomeVersion() {
  fixture.fixedGrant="stable-evidence";proof();service.setMyHome("change",1,"new",point());
  assertThrows(Exception.class,()->service.verifyReturn("again",2,"run:1","evidence"));assertEquals(1,count("player_private_home_proof"));
 }
 @Test void twoDevicesSameRequestReturnOneReceipt() throws Exception {
  Proof p=proof();ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
  try {Future<Receipt>a=pool.submit(()->{go.await();return service.restore("same",p.proofId,"run:1");});Future<Receipt>b=pool.submit(()->{go.await();return service.restore("same",p.proofId,"run:1");});go.countDown();
   assertEquals(a.get(10,TimeUnit.SECONDS).receiptId,b.get(10,TimeUnit.SECONDS).receiptId);assertEquals(1,count("player_private_home_restore"));assertEquals(10,balance("run:1"));
  } finally {pool.shutdownNow();}
 }
}
