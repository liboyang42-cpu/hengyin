package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.ActivityCancelResult;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.service.support.CancellationReceiptStore;
import com.chengyinhub.business.service.support.CancellationReceiptStore.Request;
import com.chengyinhub.business.service.support.CancellationReceiptStore.Resource;
import com.chengyinhub.common.exception.ServiceException;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Codec/transaction protocol with explicit unit storage. This class is not MySQL/financial proof. */
class CancellationReceiptStoreTest {
    private CmsActivityMapper activities;
    private CmsTopicMapper topics;
    private CancellationReceiptStore store;
    private TransactionTemplate tx;
    private final Request original=new Request(Resource.ACTIVITY,11L,21L,31L,41L,51L,61L,
            "CLUB_OWNER_WRAP","api-activity-cancel-11","原原因");

    @BeforeEach void wire() {
        activities=mock(CmsActivityMapper.class);topics=mock(CmsTopicMapper.class);
        CancellationPhaseTestSupport.wireReceiptMocks(activities,topics);
        store=new CancellationReceiptStore(activities,topics);
        tx=new TransactionTemplate(CancellationPhaseTestSupport.memoryTransactions());
    }
    private <T> T transaction(Supplier<T> work) {return tx.execute(status -> work.get());}

    @Test void resultIsOriginalFullBatchAndDoneCannotBeOverwrittenOrRecounted() {
        transaction(() -> store.begin(original,false));
        transaction(() -> store.complete(original,new ActivityCancelResult(7,2,3)));
        String bytes=transaction(() -> activities.selectCancellationReceiptForUpdate(11L));
        ActivityCancelResult replay=transaction(() -> store.current(original).activity());
        assertEquals(7,replay.getRefundedOrders());assertEquals(2,replay.getManualOrders());assertEquals(3,replay.getClosedPendingOrders());
        var recovered=transaction(() -> store.ownedActivity(11L,21L,31L,41L,51L,61L));
        assertEquals(original.requestId(),recovered.requestId());assertEquals(original.reason(),recovered.reason());
        assertEquals(7,recovered.snapshot().activity().getRefundedOrders());
        assertNull(transaction(() -> store.ownedActivity(11L,22L,31L,41L,51L,61L)));
        assertThrows(ServiceException.class,() -> transaction(() -> store.ownedActivity(11L,21L,32L,41L,51L,61L)));
        assertThrows(ServiceException.class,() -> transaction(() -> store.complete(original,new ActivityCancelResult(0,0,0))));
        assertEquals(bytes,transaction(() -> activities.selectCancellationReceiptForUpdate(11L)));
        assertThrows(ServiceException.class,() -> store.current(original));
    }

    @Test void sameAggregateDifferentActorOwnerScopeRequestOrReasonCannotReadOrChangeOriginal() {
        transaction(() -> store.begin(original,false));
        String bytes=transaction(() -> activities.selectCancellationReceiptForUpdate(11L));
        for(Request foreign:List.of(
                new Request(Resource.ACTIVITY,11L,22L,31L,41L,51L,61L,"CLUB_OWNER_WRAP","api-activity-cancel-11","原原因"),
                new Request(Resource.ACTIVITY,11L,21L,32L,41L,51L,61L,"CLUB_OWNER_WRAP","api-activity-cancel-11","原原因"),
                new Request(Resource.ACTIVITY,11L,21L,31L,42L,51L,61L,"CLUB_OWNER_WRAP","api-activity-cancel-11","原原因"),
                new Request(Resource.ACTIVITY,11L,21L,31L,41L,52L,61L,"CLUB_OWNER_WRAP","api-activity-cancel-11","原原因"),
                new Request(Resource.ACTIVITY,11L,21L,31L,41L,51L,62L,"CLUB_OWNER_WRAP","api-activity-cancel-11","原原因"),
                new Request(Resource.ACTIVITY,11L,21L,31L,41L,51L,61L,"AUTHORIZED_TOPIC_WRAP","api-activity-cancel-11","原原因"),
                new Request(Resource.ACTIVITY,11L,21L,31L,41L,51L,61L,"CLUB_OWNER_WRAP","new-id","原原因"),
                new Request(Resource.ACTIVITY,11L,21L,31L,41L,51L,61L,"CLUB_OWNER_WRAP","api-activity-cancel-11","不同原因"))) {
            assertThrows(ServiceException.class,() -> transaction(() -> store.begin(foreign,false)));
            assertEquals(bytes,transaction(() -> activities.selectCancellationReceiptForUpdate(11L)));
        }
    }

    @Test void rolledBackDoneKeepsOriginalPendingAndOnlySameRequestCanResume() {
        transaction(() -> store.begin(original,false));
        String pending=transaction(() -> activities.selectCancellationReceiptForUpdate(11L));
        assertThrows(ServiceException.class,() -> transaction(() -> {
            store.complete(original,new ActivityCancelResult(2,1,4));
            throw new ServiceException("原业务后续写入失败",409);
        }));
        assertEquals(pending,transaction(() -> activities.selectCancellationReceiptForUpdate(11L)));
        assertFalse(transaction(() -> store.current(original).done()));
        ActivityCancelResult committed=transaction(() -> store.complete(original,new ActivityCancelResult(2,1,4)));
        assertEquals(2,committed.getRefundedOrders());assertEquals(1,committed.getManualOrders());assertEquals(4,committed.getClosedPendingOrders());
        assertTrue(transaction(() -> store.current(original).done()));
    }

    @Test void historicalNullCannotBecomeSyntheticReceiptAndCorruptResultNeverBecomesSuccess() {
        assertThrows(ServiceException.class,() -> transaction(() -> store.begin(original,true)));
        assertNull(transaction(() -> activities.selectCancellationReceiptForUpdate(11L)));
        transaction(() -> store.begin(original,false));
        transaction(() -> {
            var damaged=com.alibaba.fastjson2.JSON.parseObject(activities.selectCancellationReceiptForUpdate(11L));
            damaged.put("state","DONE");damaged.put("resultType","ACTIVITY");
            damaged.put("result",java.util.Map.of("refundedOrders",2,"manualOrders",0,"closedPendingOrders",1));
            damaged.put("resultHash","0".repeat(64));
            assertEquals(1,activities.compareAndSetCancellationReceipt(11L,original.hash(),damaged.toJSONString(),false));
            return null;
        });
        assertThrows(ServiceException.class,() -> transaction(() -> store.current(original)));
    }

    @Test void originalTopicPlanSurvivesLaterListsAndRejectsDuplicateOrUnorderedOriginalIds() {
        Request topic=new Request(Resource.TOPIC,51L,21L,31L,41L,51L,61L,"CLUB_TOPIC_END","club-topic-end-51","原原因");
        assertThrows(ServiceException.class,() -> transaction(() -> store.beginTopicEnd(topic,List.of(12L,11L),true)));
        assertNull(transaction(() -> topics.selectCancellationReceiptForUpdate(51L,0L)));
        transaction(() -> store.beginTopicEnd(topic,List.of(11L,12L),true));
        assertEquals(List.of(11L,12L),transaction(() -> store.beginTopicEnd(topic,List.of(99L),false).activities()));
        transaction(() -> store.completeTopicEnd(topic,new ActivityCancelResult(7,2,3)));
        var result=transaction(() -> store.current(topic).topicEnd(51L));
        assertEquals(2,result.get("sessionTotal"));assertEquals(2,result.get("cancelledSessions"));
        assertEquals(7,result.get("refundedOrders"));assertEquals(2,result.get("manualOrders"));assertEquals(3,result.get("closedPendingOrders"));
    }
}
