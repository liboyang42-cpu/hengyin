package com.chengyinhub.business.it;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Actual original completion/grant and coupon-only clawback branch on isolated MySQL.
 * Does not fabricate FULL/first-completion eligibility or claim public refund orchestration.
 * All economic mapper writes use the same datasource and actual Spring transaction boundary. */
@EnabledIfEnvironmentVariable(named="CHENGYIN_FUNDS_MYSQL_IT",matches="1")
class FixedCouponAtomicMySqlIT extends FixedCouponMySqlFixture {
    private int rows(String table){return jdbc.queryForObject("SELECT COUNT(*) FROM "+table,Integer.class);}
    private long currentLog(){return jdbc.queryForObject("SELECT id FROM cms_topic_completion_coupon_reward_log WHERE topic_id=100 AND member_id=7",Long.class);}
    private long grant(){assertTrue(completion.grantOnce(100L,7L,8L));return Long.parseLong((String)receipts.owned(7,currentLog()).get("historyId"));}
    private int use(long history){return histories.selectSmsCouponHistoryById(history).getUseStatus();}
    private long stock(){return coupons.selectSmsCouponById(8L).getReceiveCount();}
    @SuppressWarnings("unchecked") private Map<String,Object> reverse(){return new TransactionTemplate(manager).execute(s->ReflectionTestUtils.invokeMethod(clawback,"voidCompletionCoupon",7L,100L,500L));}
    private void emptyGrant(){assertEquals(0,rows("cms_topic_completion_coupon_reward_log"));assertEquals(0,rows("sms_coupon_history"));assertEquals(0,rows("cms_topic_completion_coupon_receipt"));assertEquals(0L,stock());}
    private void markedOnce(){assertEquals(1,rows("cms_topic_completion_coupon_reward_log"));assertEquals(1,rows("sms_coupon_history"));assertEquals(1,rows("cms_topic_completion_coupon_receipt"));assertEquals(1L,stock());assertEquals(1,jdbc.queryForObject("SELECT receipt_contract_version FROM cms_topic_completion_coupon_reward_log",Integer.class));}

    @Test void originalGrantAtomicallyPersistsSourceHistoryStockReceiptAndMarker(){
        long h=grant();markedOnce();Map<String,Object> recorded=receipts.owned(7,currentLog());
        assertEquals(Long.toString(h),recorded.get("historyId"));assertEquals("NOT_PROVIDED",recorded.get("termsAvailability"));
        assertEquals(Long.valueOf(h),new TransactionTemplate(manager).execute(s->resolver.currentHistoryForClawback(100,7,8)));
        assertFalse(completion.grantOnce(100L,7L,8L));markedOnce();assertNull(receipts.owned(9,currentLog()));
    }
    @Test void historyInsertFailureRollsBackSourceAndStock(){jdbc.execute("ALTER TABLE sms_coupon_history ADD CONSTRAINT reject_history CHECK(coupon_id<0)");assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));emptyGrant();}
    @Test void receiptInsertFailureRollsBackOriginalHistoryAndStock(){jdbc.execute("ALTER TABLE cms_topic_completion_coupon_receipt ADD CONSTRAINT reject_receipt CHECK(history_id<0)");assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));emptyGrant();}
    @Test void finalMarkerFailureRollsBackAllEarlierWrites(){jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log ADD CONSTRAINT reject_marker CHECK(receipt_contract_version IS NULL OR receipt_contract_version<0)");assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));emptyGrant();}
    @Test void sourceInsertFailureCannotIncrementStockOrIssueHistory(){jdbc.execute("CREATE TRIGGER reject_source_insert BEFORE INSERT ON cms_topic_completion_coupon_reward_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='LOCAL_SOURCE_FAILURE'");assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));emptyGrant();}
    @Test void parentTransactionFailureRollsBackCompleteGrant(){assertThrows(IllegalStateException.class,()->new TransactionTemplate(manager).execute(s->{assertTrue(completion.grantOnce(100L,7L,8L));markedOnce();throw new IllegalStateException("LOCAL_PARENT_ROLLBACK");}));emptyGrant();}
    @Test void originalFiniteStockRefusalDoesNotLeaveCompletionSlot(){jdbc.update("UPDATE sms_coupon SET publish_count=1,receive_count=1 WHERE id=8");assertThrows(RuntimeException.class,()->completion.grantOnce(100L,7L,8L));assertEquals(0,rows("cms_topic_completion_coupon_reward_log"));assertEquals(0,rows("sms_coupon_history"));assertEquals(0,rows("cms_topic_completion_coupon_receipt"));assertEquals(1L,stock());}
    @Test void stoppedDefinitionUsesOriginalNoGrantBehavior(){jdbc.update("UPDATE sms_coupon SET status=4 WHERE id=8");assertFalse(completion.grantOnce(100L,7L,8L));emptyGrant();}
    @Test void exactClawbackNeverVoidsNearbySameCouponHistories(){
        long before=legacy.grantCouponWithReceipt(7L,coupons.selectSmsCouponById(8L));long exact=grant();long after=legacy.grantCouponWithReceipt(7L,coupons.selectSmsCouponById(8L));
        Map<String,Object> result=reverse();assertEquals("VOIDED",result.get("result"));assertEquals(exact,result.get("historyId"));assertEquals(0,use(before));assertEquals(2,use(exact));assertEquals(0,use(after));assertEquals(3L,stock());assertNull(reverse());assertEquals(1,rows("cms_topic_completion_coupon_receipt"));
    }
    @Test void usedExactCouponRefusesRecoveryAndRegrant(){long h=grant();jdbc.update("UPDATE sms_coupon_history SET start_time=NULL,end_time=NULL WHERE id=?",h);assertEquals(1,histories.claimUnusedCoupon(h,new Date()));assertEquals("USED_NOT_RECOVERED",reverse().get("result"));assertFalse(completion.grantOnce(100L,7L,8L));markedOnce();assertEquals(1,use(h));}
    @Test void deletingOldGenerationNeverRebindsItsReceipt(){long old=grant(),oldLog=currentLog();assertEquals("VOIDED",reverse().get("result"));long next=grant();assertNotEquals(old,next);assertNotEquals(oldLog,currentLog());assertEquals(Long.toString(old),receipts.owned(7,oldLog).get("historyId"));assertEquals(next,reverse().get("historyId"));assertEquals(2,use(old));assertEquals(2,use(next));assertEquals(2,rows("cms_topic_completion_coupon_receipt"));}
    @Test void conflictedMarkerCannotFallBackToHeuristic(){long h=grant();jdbc.update("UPDATE cms_topic_completion_coupon_reward_log SET receipt_contract_version=NULL");assertThrows(RuntimeException.class,()->reverse());assertEquals(0,use(h));assertEquals(1,rows("cms_topic_completion_coupon_reward_log"));}
    @Test void missingReceiptCannotVoidOrDeleteCurrentSource(){long h=grant();jdbc.update("DELETE FROM cms_topic_completion_coupon_receipt");assertThrows(RuntimeException.class,()->reverse());assertEquals(0,use(h));assertEquals(1,rows("cms_topic_completion_coupon_reward_log"));}
    @Test void sourceDeletionFailureRollsBackEarlierCouponVoid(){long h=grant();jdbc.execute("CREATE TRIGGER reject_source_delete BEFORE DELETE ON cms_topic_completion_coupon_reward_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='LOCAL_DELETE_FAILURE'");assertThrows(RuntimeException.class,()->reverse());assertEquals(0,use(h));markedOnce();}
    @Test void concurrentIdenticalCompletionCreatesOneEconomicGrant()throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try{Callable<Boolean> task=()->{assertTrue(go.await(5,TimeUnit.SECONDS));return completion.grantOnce(100L,7L,8L);};Future<Boolean>a=pool.submit(task),b=pool.submit(task);go.countDown();assertNotEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));markedOnce();}finally{go.countDown();pool.shutdownNow();}
    }
    @Test void concurrentDifferentSourcesShareStockButKeepExactHistories()throws Exception{
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try{Future<Boolean>a=pool.submit(()->{go.await();return completion.grantOnce(100L,7L,8L);}),b=pool.submit(()->{go.await();return completion.grantOnce(101L,7L,8L);});go.countDown();assertTrue(a.get(15,TimeUnit.SECONDS));assertTrue(b.get(15,TimeUnit.SECONDS));assertEquals(2L,stock());assertEquals(2,rows("cms_topic_completion_coupon_receipt"));assertEquals(2,jdbc.queryForObject("SELECT COUNT(DISTINCT history_id) FROM cms_topic_completion_coupon_receipt",Integer.class));}finally{go.countDown();pool.shutdownNow();}
    }
    @Test void simultaneousRedemptionAndClawbackHaveOneWinningStatusTransition()throws Exception{
        long h=grant();jdbc.update("UPDATE sms_coupon_history SET start_time=NULL,end_time=NULL WHERE id=?",h);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);
        try{Future<Map<String,Object>>a=pool.submit(()->{go.await();return reverse();});Future<Integer>b=pool.submit(()->{go.await();return new TransactionTemplate(manager).execute(s->histories.claimUnusedCoupon(h,new Date()));});go.countDown();Map<String,Object> reversed=a.get(15,TimeUnit.SECONDS);int redeemed=b.get(15,TimeUnit.SECONDS);
            if(redeemed==1){assertEquals("USED_NOT_RECOVERED",reversed.get("result"));assertEquals(1,use(h));assertEquals(1,rows("cms_topic_completion_coupon_reward_log"));}else{assertEquals(0,redeemed);assertEquals("VOIDED",reversed.get("result"));assertEquals(2,use(h));assertEquals(0,rows("cms_topic_completion_coupon_reward_log"));}assertEquals(1L,stock());assertEquals(1,rows("cms_topic_completion_coupon_receipt"));
        }finally{go.countDown();pool.shutdownNow();}
    }
}
