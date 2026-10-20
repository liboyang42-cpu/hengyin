package com.chengyinhub.business.service.ai;

import com.chengyinhub.business.domain.AiQuotaReservationRecord;
import com.chengyinhub.business.mapper.AiQuotaReservationMapper;
import com.chengyinhub.common.utils.uuid.IdUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.Arrays;
import java.util.List;

/** 原日配额的非资金恢复；未知提供方结果不会因超时被自动判为退款。 */
@Service
public class AiQuotaRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(AiQuotaRecoveryService.class);
    private final AiQuotaReservationMapper mapper;
    private final RedisTemplate<Object,Object> redis;
    private final TransactionTemplate transaction;

    // 2=已取消/已退；3=原计数不存在/代次变化；-3=收据过期且计数仍在，无法证明曾否退款。
    private static final DefaultRedisScript<Long> COMPENSATE = new DefaultRedisScript<>(
        "local r=redis.call('GET',KEYS[3]); if r=='0' then return 2 end; " +
        "local raw=redis.call('GET',KEYS[1]); " +
        "if not r then if raw then return -3 end; " +
        "redis.call('SET',KEYS[3],'0','EX',172800); return 3 end; " +
        "local sep=string.find(r,'|',1,true); if not sep then return redis.error_reply('invalid quota receipt') end; " +
        "local gen=string.sub(r,1,sep-1); " +
        "if ARGV[2]~='' and gen~=ARGV[2] then return -3 end; " +
        "if not raw or redis.call('GET',KEYS[2])~=gen then redis.call('SET',KEYS[3],'0','EX',172800); return 3 end; " +
        "if not (raw=='0' or string.match(raw,'^[1-9]%d*$')) then return redis.error_reply('invalid quota counter') end; " +
        "local used=tonumber(raw); if not used or used>9007199254740991 then return redis.error_reply('quota counter out of range') end; " +
        "if used>0 then redis.call('DECR',KEYS[1]) end; redis.call('SET',KEYS[3],'0','EX',172800); return 2;", Long.class);

    public AiQuotaRecoveryService(AiQuotaReservationMapper mapper, RedisTemplate<Object,Object> redis,
                                 PlatformTransactionManager transactionManager) {
        this.mapper=mapper; this.redis=redis;
        transaction=new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void prepare(Long memberId,String bizType,String pool,String key,String receipt) {
        AiQuotaReservationRecord row=new AiQuotaReservationRecord();
        row.setMemberId(memberId); row.setBizType(bizType); row.setPool(pool); row.setCounterKey(key);
        row.setOriginalDay(key.substring(key.length()-8)); row.setReceiptKey(receipt);
        row.setNonce(receipt.substring(receipt.length()-32)); row.setRequestLeaseToken(row.getNonce());
        transaction.execute(status -> { if(mapper.insertPrepared(row)!=1) throw unavailable(); return null; });
    }

    /** 只能读取这个 nonce 的收据；绝不从当前 counter:generation 补造原代次。 */
    public void reserved(String receipt,Long used) {
        Object raw=redis.execute(new DefaultRedisScript<String>("return redis.call('GET',KEYS[1])",String.class),
                new org.springframework.data.redis.serializer.StringRedisSerializer(),
                new org.springframework.data.redis.serializer.StringRedisSerializer(),Arrays.<Object>asList(receipt));
        String value=raw==null ? "" : String.valueOf(raw);
        int split=value.lastIndexOf('|');
        if(split<=0 || !value.substring(split+1).equals(String.valueOf(used))) throw unavailable();
        String generation=value.substring(0,split);
        transaction.execute(status -> { if(mapper.recordReserved(receipt,generation,used)!=1) throw unavailable(); return null; });
    }

    public void stage(String receipt,String stage) {
        if(receipt==null) return;
        if(!"INPUT_REVIEW_STARTED".equals(stage) && !"MODEL_STARTED".equals(stage)) throw new IllegalArgumentException("invalid quota stage");
        transaction.execute(status -> { if(mapper.advanceStage(receipt,stage)!=1) throw unavailable(); return null; });
    }

    public void retain(String receipt,String reason) { outcome(receipt,"RETAINED",reason); }

    public void requireRefund(String receipt,String reason) {
        outcome(receipt,"REFUND_DUE",reason); // 独立事务已提交，之后 Redis 故障仍有原身份待办。
        AiQuotaReservationRecord row=mapper.selectByReceipt(receipt);
        if(row!=null) reconcile(row);
    }

    private void outcome(String receipt,String state,String reason) {
        if(receipt==null) return;
        if(reason==null || !reason.matches("[A-Z_]{1,64}")) throw new IllegalArgumentException("invalid decision code");
        transaction.execute(status -> {
            if(mapper.recordOutcome(receipt,state,reason)!=1) {
                AiQuotaReservationRecord row=mapper.selectByReceipt(receipt);
                if(row==null || !(state.equals(row.getState()) || "REFUNDED".equals(row.getState())
                        || "RESOLVED_NO_ACTIVE_RESERVATION".equals(row.getState()))) throw unavailable();
            }
            return null;
        });
    }

    /** 一页固定小批；每条失败推迟其自身，后续行仍被处理。无需安装新调度器。 */
    public void runDue() {
        List<AiQuotaReservationRecord> due=mapper.selectDue(100);
        for(AiQuotaReservationRecord row:due) {
            try { reconcile(row); }
            catch(RuntimeException failure) { log.warn("AI quota recovery pending id={} code={}",row.getId(),failure.getClass().getSimpleName()); }
        }
    }

    private void reconcile(AiQuotaReservationRecord candidate) {
        String owner=IdUtils.simpleUUID();
        AiQuotaReservationRecord row=transaction.execute(status -> {
            if(mapper.claim(candidate.getId(),candidate.getFenceVersion(),owner)!=1) return null;
            return mapper.selectById(candidate.getId());
        });
        if(row==null) return;
        try {
            if(!"REFUND_DUE".equals(row.getState()) && !"PRE_RESERVE".equals(row.getStage())) {
                complete(row,owner,"UNCERTAIN","PROVIDER_OUTCOME_UNKNOWN"); return;
            }
            Long reply=redis.execute(COMPENSATE,new org.springframework.data.redis.serializer.StringRedisSerializer(),
                    new org.springframework.data.redis.serializer.GenericToStringSerializer<Long>(Long.class),Arrays.<Object>asList(row.getCounterKey(),
                    row.getCounterKey()+":generation",row.getReceiptKey()),row.getStage(),
                    row.getGeneration()==null ? "" : row.getGeneration());
            if(reply==null) throw unavailable();
            if(reply==-3L) complete(row,owner,"UNCERTAIN","RECEIPT_EVIDENCE_EXPIRED");
            else if(reply==3L) complete(row,owner,"RESOLVED_NO_ACTIVE_RESERVATION",null);
            else if(reply==2L) complete(row,owner,"REFUNDED",null);
            else throw unavailable();
        } catch(RuntimeException failure) {
            transaction.execute(status -> mapper.retry(row.getId(),owner,row.getFenceVersion(),"RECOVERY_UNAVAILABLE"));
            throw failure;
        }
    }

    private void complete(AiQuotaReservationRecord row,String owner,String state,String error) {
        transaction.execute(status -> mapper.complete(row.getId(),owner,row.getFenceVersion(),state,error));
    }

    private static IllegalStateException unavailable() { return new IllegalStateException("AI quota recovery unavailable"); }
}
