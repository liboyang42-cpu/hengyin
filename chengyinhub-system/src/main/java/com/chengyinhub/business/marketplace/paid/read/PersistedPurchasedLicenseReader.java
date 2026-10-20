package com.chengyinhub.business.marketplace.paid.read;

import com.chengyinhub.business.marketplace.paid.PersistedPaidLicenseService;
import com.chengyinhub.common.exception.ServiceException;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import java.time.Clock;
import java.util.*;

/** Independent metadata gate. No bean registration, payment action, protected body or installation authority. */
public final class PersistedPurchasedLicenseReader implements PurchasedLicenseReading {
    public enum Policy { DISABLED, OWNER_PURCHASED_VERSION_METADATA_V1 }
    private final PersistedPaidLicenseService licenses;private final Policy policy;private final Clock clock;
    public PersistedPurchasedLicenseReader(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock){this(session,manager,clock,Policy.DISABLED);}
    public PersistedPurchasedLicenseReader(SqlSessionTemplate session,DataSourceTransactionManager manager,Clock clock,Policy policy){
        Objects.requireNonNull(session);this.licenses=new PersistedPaidLicenseService(session,manager,clock,policy==Policy.OWNER_PURCHASED_VERSION_METADATA_V1?PersistedPaidLicenseService.Policy.VERIFIED_WECHAT_PURCHASED_VERSION_V1:PersistedPaidLicenseService.Policy.DISABLED);this.clock=Objects.requireNonNull(clock);this.policy=Objects.requireNonNull(policy);
    }
    private void enabled(long actor){if(policy!=Policy.OWNER_PURCHASED_VERSION_METADATA_V1)throw new ServiceException("PURCHASED_LIBRARY_NOT_ENABLED",503);if(actor<=0)throw new ServiceException("AUTH_REQUIRED",401);licenses.requireCurrentPersistence();}
    @Override public PurchasedLicenseMetadata.Page list(long actor,Long before){
        enabled(actor);if(before!=null&&before<=0)throw new ServiceException("INVALID_REQUEST",400);
        List<PurchasedLicenseIndexStore.Row> rows=licenses.listOwnedIndex(actor,before);if(rows==null||rows.size()>51)throw unavailable();
        List<PurchasedLicenseMetadata> items=new ArrayList<PurchasedLicenseMetadata>();Long previous=before;
        for(PurchasedLicenseIndexStore.Row row:rows.subList(0,Math.min(rows.size(),50))){
            if(row==null||row.getOrderLineId()==null||row.getOrderLineId()<=0||(previous!=null&&row.getOrderLineId()>=previous))throw unavailable();previous=row.getOrderLineId();
            // Index rows are references only; each item is verified against the real durable receipt and current owner/state.
            items.add(item(actor,row.getLicenseId()));
        }
        Long next=rows.size()==51?previous:null;return new PurchasedLicenseMetadata.Page(items,next,clock.instant());
    }
    @Override public PurchasedLicenseMetadata.Detail detail(long actor,String licenseId){enabled(actor);return new PurchasedLicenseMetadata.Detail(item(actor,licenseId),clock.instant());}
    private PurchasedLicenseMetadata item(long actor,String id){
        if(id==null||!id.matches("w18-paid-[1-9][0-9]{0,18}"))throw new ServiceException("INVALID_REQUEST",400);
        try{return new PurchasedLicenseMetadata(licenses.readOwned(actor,id));}catch(IllegalArgumentException absent){throw new ServiceException("NOT_FOUND",404);}
    }
    private static ServiceException unavailable(){return new ServiceException("PURCHASED_LIBRARY_UNAVAILABLE",503);}
}
