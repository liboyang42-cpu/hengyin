package com.chengyinhub.business.service.coupon;

import com.chengyinhub.business.domain.SmsCoupon;
import com.chengyinhub.business.mapper.TopicCompletionCouponReceiptMapper;
import com.chengyinhub.business.mapper.TopicCompletionCouponReceiptMapper.Row;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Records only the original completion grant's generated history ID.
 * This is not a qualification service. The only installed caller is the existing
 * new-log branch of TopicCompletionCouponRewardServiceImpl under its parent transaction;
 * FULL/first-completion/clawback decisions stay in the original completion authority.
 * No bean exists without the explicit receipt profile. No legacy receipt is inferred.
 */
public final class TopicCompletionCouponReceiptService implements TopicCompletionCouponHistoryResolving {
    private final TopicCompletionCouponReceiptMapper mapper;
    private final ISmsCouponHistoryService originalGrant;
    private final DataSource dataSource;
    private final TopicCompletionCouponHistoryResolver historyResolver;
    public TopicCompletionCouponReceiptService(TopicCompletionCouponReceiptMapper mapper,
            ISmsCouponHistoryService originalGrant, DataSource dataSource) {
        this.mapper=Objects.requireNonNull(mapper);this.originalGrant=Objects.requireNonNull(originalGrant);this.dataSource=Objects.requireNonNull(dataSource);
        this.historyResolver=new TopicCompletionCouponHistoryResolver(mapper,dataSource);
    }
    /** Internal hook immediately after insertIgnore returned one; never a player command. */
    public boolean grantForNewCompletion(long topic,long member,long coupon) {
        if(topic<=0 || member<=0 || coupon<=0)throw new IllegalArgumentException("Invalid completion identity");
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(dataSource))
            throw new IllegalStateException("Completion and receipt require the same active datasource transaction");
        Long log=mapper.lockLog(topic,member,coupon);
        if(log==null || log<=0 || mapper.owned(member,log)!=null)throw new IllegalStateException("New completion log required");
        SmsCoupon definition=mapper.lockDefinition(coupon);
        if(definition==null || !Long.valueOf(coupon).equals(definition.getId()))return false;
        // Reuses the original stopped/manual-invalid/finite-stock behavior exactly.
        Long history=originalGrant.grantCouponWithReceipt(member,definition);
        if(history==null)return false;
        if(history<=0 || mapper.appendExactHistory(log,topic,member,coupon,history)!=1)
            throw new IllegalStateException("Completion coupon receipt correlation failed");
        if(mapper.markExpectedReceipt(log,topic,member,coupon,history)!=1)
            throw new IllegalStateException("Completion receipt protocol marker failed");
        return true;
    }
    @Override public boolean protocolInstalled() { return historyResolver.protocolInstalled(); }
    @Override public Long currentHistoryForClawback(long topic,long member,long coupon) {
        return historyResolver.currentHistoryForClawback(topic,member,coupon);
    }
    public Map<String,Object> owned(long member,long log) {
        if(member<=0 || log<=0)throw new IllegalArgumentException("Invalid receipt identity");
        Row row=mapper.owned(member,log);return row==null?null:project(member,row);
    }
    public Map<String,Object> ownedPage(long member,int limit,Long cursor) {
        if(member<=0 || limit<1 || limit>50 || cursor!=null && cursor<=0)throw new IllegalArgumentException("Invalid receipt page");
        List<Row> rows=mapper.ownedPage(member,cursor,limit+1);
        if(rows==null || rows.size()>limit+1)throw new IllegalStateException("Invalid receipt page");
        List<Map<String,Object>> values=new ArrayList<Map<String,Object>>();long previous=cursor==null?0:cursor;
        for(int i=0;i<Math.min(rows.size(),limit);i++) {
            Row row=rows.get(i);if(row==null || row.logId==null || row.logId<=previous)throw new IllegalStateException("Invalid receipt order");
            values.add(project(member,row));previous=row.logId;
        }
        Map<String,Object> page=new LinkedHashMap<String,Object>();page.put("contractVersion",1);page.put("items",values);
        page.put("nextCursor",rows.size()>limit?Long.toString(previous):null);return Collections.unmodifiableMap(page);
    }
    private Map<String,Object> project(long member,Row row) {
        if(!Long.valueOf(member).equals(row.memberId) || row.logId==null || row.logId<=0 || row.topicId==null || row.topicId<=0
                || row.couponId==null || row.couponId<=0 || row.historyId==null || row.historyId<=0 || row.grantedAt==null)
            throw new IllegalStateException("Invalid receipt correlation");
        Map<String,Object> result=new LinkedHashMap<String,Object>();result.put("grantLogId",row.logId.toString());
        result.put("topicId",row.topicId.toString());result.put("couponId",row.couponId.toString());result.put("historyId",row.historyId.toString());
        result.put("recording","RECORDED");result.put("source","TOPIC_COMPLETION_COUPON");result.put("grantedAt",row.grantedAt.getTime());
        result.put("useStatus",row.useStatus);result.put("usedAt",row.usedAt==null?null:row.usedAt.getTime());
        result.put("validFrom",row.validFrom==null?null:row.validFrom.getTime());result.put("validUntil",row.validUntil==null?null:row.validUntil.getTime());
        result.put("termsAvailability","NOT_PROVIDED");return Collections.unmodifiableMap(result);
    }
}
