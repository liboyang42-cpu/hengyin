package com.chengyinhub.business.service.coupon;
import com.chengyinhub.business.service.impl.RedemptionRewardClawbackService;
import java.util.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.ReflectionUtils;
import static org.junit.jupiter.api.Assertions.*;

/** Executes the full original clawback class's coupon branch with actual mappers/transactions.
 * Points/XP/audit caller orchestration is not simulated or claimed as full validation.
 */
public class TopicCompletionCouponExactClawbackTest {
    TopicCompletionCouponReceiptTransactionTest f; RedemptionRewardClawbackService clawback; NoGuess witness;
    @Intercepts(@Signature(type=Executor.class,method="query",args={MappedStatement.class,Object.class,RowBounds.class,ResultHandler.class}))
    public static class NoGuess implements Interceptor {
        int guesses;
        public Object intercept(Invocation invocation)throws Throwable {
            String id=((MappedStatement)invocation.getArgs()[0]).getId();
            if(id.endsWith(".selectCompletionRewardHistoryId")) {
                guesses++;throw new IllegalStateException("New receipt protocol must never invoke legacy time-window guessing");
            }
            return invocation.proceed();
        }
    }
    @BeforeEach void setup()throws Exception {
        f=new TopicCompletionCouponReceiptTransactionTest();f.setup();witness=new NoGuess();f.f.sql.getConfiguration().addInterceptor(witness);
        clawback=new RedemptionRewardClawbackService();ReflectionTestUtils.setField(clawback,"completionCouponRewardMapper",f.logs);
        ReflectionTestUtils.setField(clawback,"smsCouponHistoryMapper",f.f.histories);
        // Also permits an exact original-class negative-control replay; no business stub replaces it.
        if(ReflectionUtils.findField(RedemptionRewardClawbackService.class,"completionCouponReceipts")!=null)
            ReflectionTestUtils.setField(clawback,"completionCouponReceipts",f.receipts);
    }
    @AfterEach void close(){try{assertEquals(0,witness.guesses,"A linked or conflicted new receipt cannot enter legacy guessing");}finally{f.close();}}
    long grant(){assertTrue(f.completion.grantOnce(100L,7L,8L));return Long.parseLong((String)f.receipts.owned(7,f.log()).get("historyId"));}
    @SuppressWarnings("unchecked") Map<String,Object> reverse(long member,long topic) {
        return new TransactionTemplate(f.f.manager).execute(s->ReflectionTestUtils.invokeMethod(clawback,"voidCompletionCoupon",member,topic,500L));
    }
    int status(long id){return f.f.histories.selectSmsCouponHistoryById(id).getUseStatus();}
    void unchanged(long history){assertEquals(0,status(history));assertEquals(1,f.count("cms_topic_completion_coupon_reward_log"));}
    @Test void nearbySameDefinitionHistoriesNeverReplaceExactGrantReceipt(){
        long earlier=f.f.legacy.grantCouponWithReceipt(7L,f.f.coupons.selectSmsCouponById(8L));long exact=grant();
        long later=f.f.legacy.grantCouponWithReceipt(7L,f.f.coupons.selectSmsCouponById(8L));
        Map<String,Object> result=reverse(7,100);assertEquals("VOIDED",result.get("result"));assertEquals(exact,result.get("historyId"));
        assertEquals(0,status(earlier));assertEquals(2,status(exact));assertEquals(0,status(later));assertEquals(0,f.count("cms_topic_completion_coupon_reward_log"));
        assertEquals(3L,f.f.coupons.selectSmsCouponById(8L).getReceiveCount());assertEquals(1,f.count("cms_topic_completion_coupon_receipt"));
    }
    @Test void newLogIsMarkedInSameOriginalGrantTransaction(){long history=grant();assertEquals(1,f.f.jdbc.queryForObject("SELECT receipt_contract_version FROM cms_topic_completion_coupon_reward_log",Integer.class));Long resolved=new TransactionTemplate(f.f.manager).execute(s->f.receipts.currentHistoryForClawback(100,7,8));assertEquals(Long.valueOf(history),resolved);}
    @Test void markedSlotWithMissingReceiptRejectsRatherThanGuessing(){long h=grant();f.f.jdbc.update("DELETE FROM cms_topic_completion_coupon_receipt");assertThrows(RuntimeException.class,()->reverse(7,100));unchanged(h);}
    @Test void receiptWithNullMarkerIsNotSilentlyCalledLegacy(){long h=grant();f.f.jdbc.update("UPDATE cms_topic_completion_coupon_reward_log SET receipt_contract_version=NULL");assertThrows(RuntimeException.class,()->reverse(7,100));unchanged(h);}
    @Test void unknownMarkerVersionRejectsRatherThanGuessing(){long h=grant();f.f.jdbc.update("UPDATE cms_topic_completion_coupon_reward_log SET receipt_contract_version=2");assertThrows(RuntimeException.class,()->reverse(7,100));unchanged(h);}
    @Test void legacyUnmarkedAndUnlinkedSlotOnlyReturnsExplicitFallback(){f.logs.insertIgnore(100L,7L,8L);assertNull(new TransactionTemplate(f.f.manager).execute(s->f.receipts.currentHistoryForClawback(100,7,8)));assertEquals(0,f.count("sms_coupon_history"));assertEquals(0,f.count("cms_topic_completion_coupon_receipt"));}
    @Test void missingLinkedHistoryRejectsWithoutDeletingSourceLog(){long h=grant();f.f.jdbc.update("DELETE FROM sms_coupon_history WHERE id=?",h);assertThrows(RuntimeException.class,()->reverse(7,100));assertEquals(1,f.count("cms_topic_completion_coupon_reward_log"));assertEquals(1,f.count("cms_topic_completion_coupon_receipt"));}
    @Test void foreignHistoryOwnerCannotBeVoidedOrUsedAsFallback(){long h=grant();f.f.jdbc.update("UPDATE sms_coupon_history SET member_id=9 WHERE id=?",h);assertThrows(RuntimeException.class,()->reverse(7,100));unchanged(h);}
    @Test void receiptOwnerTopicOrDefinitionConflictRejects(){long h=grant();for(String change:new String[]{"member_id=9","topic_id=101","coupon_id=9"}){f.f.jdbc.update("UPDATE cms_topic_completion_coupon_receipt SET "+change);assertThrows(RuntimeException.class,()->reverse(7,100));unchanged(h);f.f.jdbc.update("UPDATE cms_topic_completion_coupon_receipt SET member_id=7,topic_id=100,coupon_id=8");}}
    @Test void historyDefinitionTimeOrGetTypeConflictRejects(){long h=grant();for(String change:new String[]{"coupon_id=9","get_type=1","get_time=NULL","del_flag=2"}){f.f.jdbc.update("UPDATE sms_coupon_history SET "+change+" WHERE id=?",h);assertThrows(RuntimeException.class,()->reverse(7,100));unchanged(h);f.f.jdbc.update("UPDATE sms_coupon_history SET coupon_id=8,get_type=0,del_flag=0,get_time=(SELECT granted_at FROM cms_topic_completion_coupon_receipt WHERE history_id=?) WHERE id=?",h,h);}}
    @Test void repeatedClawbackHasNoSecondHistoryChangeOrLogRemoval(){long h=grant();assertEquals("VOIDED",reverse(7,100).get("result"));assertNull(reverse(7,100));assertEquals(2,status(h));assertEquals(1,f.count("cms_topic_completion_coupon_receipt"));}
    @Test void deletedOldGenerationAndRecompletionUseOnlyNewExactHistory(){long first=grant(),oldLog=f.log();assertEquals("VOIDED",reverse(7,100).get("result"));long second=grant(),newLog=f.log();assertNotEquals(first,second);assertNotEquals(oldLog,newLog);Map<String,Object> r=reverse(7,100);assertEquals(second,r.get("historyId"));assertEquals(2,status(first));assertEquals(2,status(second));assertEquals(2,f.count("cms_topic_completion_coupon_receipt"));assertNotNull(f.receipts.owned(7,oldLog));assertNotNull(f.receipts.owned(7,newLog));}
    @Test void alreadyUsedExactCouponIsNotRecoveredOrAllowedToRegrant(){long h=grant();f.f.jdbc.update("UPDATE sms_coupon_history SET use_status=1 WHERE id=?",h);assertEquals("USED_NOT_RECOVERED",reverse(7,100).get("result"));assertEquals(1,status(h));assertEquals(1,f.count("cms_topic_completion_coupon_reward_log"));assertFalse(f.completion.grantOnce(100L,7L,8L));assertEquals(1,f.count("sms_coupon_history"));}
    @Test void anotherOwnerCannotDiscoverOrVoidThisCurrentGrant(){long h=grant();assertNull(reverse(9,100));unchanged(h);assertThrows(IllegalStateException.class,()->new TransactionTemplate(f.f.manager).execute(s->f.receipts.currentHistoryForClawback(100,9,8)));}
    @Test void parentRollbackRestoresVoidedCouponAndLogTogether(){long h=grant();assertThrows(IllegalStateException.class,()->new TransactionTemplate(f.f.manager).execute(s->{assertEquals("VOIDED",reverse(7,100).get("result"));throw new IllegalStateException("synthetic parent rollback");}));unchanged(h);assertEquals(1,f.count("cms_topic_completion_coupon_receipt"));}
    @Test void sourceLogDeleteConstraintFailureRollsBackPriorVoid(){long h=grant();f.f.jdbc.execute("CREATE TABLE log_delete_guard(id BIGINT PRIMARY KEY,FOREIGN KEY(id) REFERENCES cms_topic_completion_coupon_reward_log(id))");f.f.jdbc.update("INSERT INTO log_delete_guard(id) VALUES(?)",f.log());assertThrows(RuntimeException.class,()->reverse(7,100));unchanged(h);}
    @Test void markerWriteFailureRollsBackOriginalLogHistoryStockAndReceipt(){f.f.jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log ADD CONSTRAINT reject_marker CHECK(receipt_contract_version IS NULL OR receipt_contract_version<0)");assertThrows(RuntimeException.class,()->f.completion.grantOnce(100L,7L,8L));f.empty();}
    @Test void missingMarkerSchemaCannotProduceUnmarkedNewProtocolRows(){f.f.jdbc.execute("ALTER TABLE cms_topic_completion_coupon_reward_log DROP COLUMN receipt_contract_version");assertThrows(RuntimeException.class,()->f.completion.grantOnce(100L,7L,8L));f.empty();}
}
