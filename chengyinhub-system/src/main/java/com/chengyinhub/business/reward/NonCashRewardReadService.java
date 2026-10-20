package com.chengyinhub.business.reward;

import java.time.Clock;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import static com.chengyinhub.business.reward.NonCashRewardContracts.*;

/** Real ledger read adapter, default disabled and never a production bean/factory. */
public final class NonCashRewardReadService {
    public enum Mode { DISABLED, ISOLATED_READ_EVALUATION }
    @JsonFormat(shape=JsonFormat.Shape.STRING)
    public enum ValidityStatus { UPCOMING, IN_WINDOW, ELAPSED }
    @JsonFormat(shape=JsonFormat.Shape.STRING)
    public enum FulfillmentStatus { UNVERIFIED, MERCHANT_DISABLED, MERCHANT_UNAVAILABLE }
    public static final int MAX_PAGE_SIZE=50; // Transport protection only, never a reward/season quota.
    private final Mode mode;
    private final NonCashRewardReadMapper store;
    private final TransactionTemplate transaction;
    private final Clock clock;
    private final NonCashRewardCodec codec=new NonCashRewardCodec();
    public NonCashRewardReadService(){mode=Mode.DISABLED;store=null;transaction=null;clock=null;}
    public NonCashRewardReadService(Mode mode,NonCashRewardReadMapper store,PlatformTransactionManager manager,Clock clock){
        require(mode!=null&&store!=null&&manager!=null&&clock!=null,"MISSING_DEPENDENCY");
        this.mode=mode;this.store=store;this.clock=clock;transaction=new TransactionTemplate(manager);transaction.setReadOnly(true);
    }
    public Page list(long memberId,int limit,String cursor){
        actor(memberId);if(limit<1||limit>MAX_PAGE_SIZE)throw new Rejection("INVALID_REQUEST");
        if(cursor!=null)awardId(cursor);
        return atomic(()->{
            NonCashRewardReadMapper.Row anchor=cursor==null?null:store.owned(memberId,cursor);
            if(cursor!=null&&anchor==null)throw new Rejection("INVALID_REQUEST");
            if(anchor!=null)validate(anchor,memberId);
            List<NonCashRewardReadMapper.Row> rows=store.page(memberId,anchor==null?null:anchor.awardedAt,cursor,limit+1);
            require(rows!=null&&rows.size()<=limit+1,"INVALID_LEDGER_PAGE");
            long now=clock.millis();List<View> result=new ArrayList<>();Map<Long,FulfillmentStatus> merchants=new HashMap<>();
            for(int i=0;i<Math.min(limit,rows.size());i++)result.add(project(rows.get(i),memberId,now,merchants));
            String next=rows.size()>limit?result.get(result.size()-1).awardId:null;
            return new Page(result,next,now);
        });
    }
    /** Caller supplies the exact tuple received from list; no THEME/MAP/run/season fallback. */
    public View detail(long memberId,String awardId,Scope expectedScope){
        actor(memberId);awardId(awardId);if(expectedScope==null)throw new Rejection("INVALID_REQUEST");
        return atomic(()->{
            NonCashRewardReadMapper.Row row=store.owned(memberId,awardId);
            if(row==null)throw new Rejection("NOT_FOUND");
            Pledge p=validate(row,memberId);
            if(!p.scope.key().equals(expectedScope.key()))throw new Rejection("NOT_FOUND");
            return project(row,memberId,clock.millis(),new HashMap<>());
        });
    }
    private <T>T atomic(Supplier<T> work){
        if(mode!=Mode.ISOLATED_READ_EVALUATION)throw new Rejection("UNAVAILABLE");
        require(!TransactionSynchronizationManager.isActualTransactionActive(),"NESTED_TRANSACTION_FORBIDDEN");
        return transaction.execute(status->work.get());
    }
    private Pledge validate(NonCashRewardReadMapper.Row row,long memberId){
        require(row!=null&&row.memberId==memberId&&row.awardedAt>=0,"CORRUPT_LEDGER_ROW");
        require(row.awardId!=null&&row.awardId.matches("[0-9a-f]{64}"),"CORRUPT_LEDGER_ROW");
        Pledge p=codec.read(row.payload);
        require(p.pledgeId.equals(row.pledgeId)&&p.fingerprint().equals(row.fingerprint),"CORRUPT_LEDGER_SNAPSHOT");
        AwardState.valueOf(row.state);return p;
    }
    private View project(NonCashRewardReadMapper.Row row,long memberId,long now,Map<Long,FulfillmentStatus> merchants){
        Pledge p=validate(row,memberId);
        FulfillmentStatus fulfillment=merchants.get(p.merchantId);
        if(fulfillment==null){fulfillment=merchantStatus(store.merchant(p.merchantId),p.merchantId);merchants.put(p.merchantId,fulfillment);}
        return new View(row,p,now,fulfillment);
    }
    private FulfillmentStatus merchantStatus(NonCashRewardReadMapper.MerchantRow row,long expectedId){
        if(row==null)return FulfillmentStatus.MERCHANT_UNAVAILABLE;
        require(row.id!=null&&row.id==expectedId,"CORRUPT_MERCHANT_ROW");
        if(row.delFlag!=null&&row.delFlag!=0)return FulfillmentStatus.MERCHANT_UNAVAILABLE;
        if(row.accountStatus!=null&&row.accountStatus!=1L)return FulfillmentStatus.MERCHANT_DISABLED;
        if(row.status!=null&&row.status!=1L)return FulfillmentStatus.MERCHANT_UNAVAILABLE;
        // Enabled/approved merchant is NOT evidence of store fulfillment or unfrozen pledge.
        return FulfillmentStatus.UNVERIFIED;
    }
    private static void actor(long memberId){if(memberId<=0)throw new Rejection("AUTH_REQUIRED");}
    private static void awardId(String value){if(value==null||!value.matches("[0-9a-f]{64}"))throw new Rejection("INVALID_REQUEST");}
    public static final class Rejection extends RuntimeException{
        private static final long serialVersionUID=1L;
        private final String code;
        public Rejection(String code){super(code);this.code=code;}
        public String getCode(){return code;}
    }
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class Page{
        public final List<View> items;
        public final String nextCursor;
        public final long asOf;
        Page(List<View> items,String nextCursor,long asOf){this.items=Collections.unmodifiableList(new ArrayList<>(items));this.nextCursor=nextCursor;this.asOf=asOf;}
    }
    /** Flat player DTO: decimal-string IDs, epoch-ms timestamps, immutable merchant terms. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public static final class View{
        public final String awardId,contextType,contextId,releaseId,instanceId,rulesVersion,merchantId,storeId;
        public final String rewardKind="PHYSICAL",rewardTitle,redemptionConditions,state;
        public final int quantity=1;
        public final long validFrom,validUntil,awardedAt,asOf;
        public final ValidityStatus validityStatus;
        public final FulfillmentStatus fulfillmentStatus;
        View(NonCashRewardReadMapper.Row row,Pledge p,long now,FulfillmentStatus fulfillment){
            awardId=row.awardId;contextType=p.scope.type.name();contextId=p.scope.contextId;releaseId=p.scope.releaseId;instanceId=p.scope.instanceId;
            rulesVersion=p.rulesVersion;merchantId=Long.toString(p.merchantId);storeId=Long.toString(p.storeId);rewardTitle=p.rewardTitle;
            redemptionConditions=p.redemptionConditions;state=row.state;validFrom=p.validFrom;validUntil=p.validUntil;awardedAt=row.awardedAt;asOf=now;
            validityStatus=now<p.validFrom?ValidityStatus.UPCOMING:now>=p.validUntil?ValidityStatus.ELAPSED:ValidityStatus.IN_WINDOW;
            fulfillmentStatus=fulfillment;
        }
    }
}
