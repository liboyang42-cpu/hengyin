package com.chengyinhub.business.service.ai;

import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import org.mockito.invocation.Invocation;
import org.springframework.beans.BeanUtils;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.chengyinhub.business.domain.NpcProfile;
import com.chengyinhub.business.domain.NpcVoiceEnrollment;
import com.chengyinhub.business.mapper.NpcProfileMapper;
import com.chengyinhub.business.mapper.NpcVoiceEnrollmentMapper;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Existing caller fixtures keep their real service and original provider/profile mocks.
 * Models accepted profile UPDATEs at commit, with detached reads and durable intent storage.
 * No provider output, permission, rowcount, state or SQL is fabricated as a production fallback.
 * This is a unit transaction/mapper double, not MySQL/Spring transaction acceptance.
 */
public final class NpcVoiceEnrollmentFixture
{
    private final Map<String,NpcVoiceEnrollment> rows=new HashMap<>();
    private final ReentrantLock lock=new ReentrantLock();
    private Map<String,NpcVoiceEnrollment> backup;
    private Set<Invocation> before;
    private long sequence;
    public final NpcVoiceEnrollmentMapper intents=mock(NpcVoiceEnrollmentMapper.class);
    public final NpcVoiceEnrollmentService service;

    public NpcVoiceEnrollmentFixture(NpcProfileMapper profiles,VoiceCloneClient provider,
            int scopeType,Long scopeId,Supplier<NpcProfile> originalProfileRead)
    {
        PlatformTransactionManager manager=new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                if(definition.getPropagationBehavior()!=TransactionDefinition.PROPAGATION_REQUIRES_NEW)
                    throw new AssertionError("durable operation must use REQUIRES_NEW");
                lock.lock();backup=new HashMap<>();rows.forEach((h,r)->backup.put(h,copy(r)));
                before=new HashSet<>(mockingDetails(profiles).getInvocations());
                TransactionSynchronizationManager.setActualTransactionActive(true);return new SimpleTransactionStatus();
            }
            public void commit(TransactionStatus status) {
                try {
                    List<Invocation> writes=mockingDetails(profiles).getInvocations().stream().filter(i->!before.contains(i))
                            .filter(i->i.getMethod().getName().equals(scopeType==2?"updateMerchantNpcVoice":"updateNodeNpcVoice"))
                            .sorted(Comparator.comparingInt(Invocation::getSequenceNumber)).toList();
                    if(!writes.isEmpty()) {
                        // Service reaches commit only after checking exact rowcount=1. Copy its post-CAS version.
                        NpcProfile applied=writes.get(writes.size()-1).getArgument(0);
                        NpcProfile target=originalProfileRead.get();
                        if(target==null||!Objects.equals(target.getId(),applied.getId()))throw new AssertionError("missing original fixture row");
                        BeanUtils.copyProperties(applied,target);
                        target.setVoiceRequestId(null);target.setVoicePreviousIntentId(null);target.setVoiceSelected(null);
                        target.setVoiceIntentId(null);target.setVoiceIntentState(null);target.setVoiceReasonCode(null);
                    }
                } finally {TransactionSynchronizationManager.setActualTransactionActive(false);lock.unlock();}
            }
            public void rollback(TransactionStatus status) {
                rows.clear();rows.putAll(backup);TransactionSynchronizationManager.setActualTransactionActive(false);lock.unlock();
            }
        };
        when(intents.lockProfile(anyLong(),anyInt(),anyLong())).thenAnswer(i->{
            NpcProfile actual=originalProfileRead.get();
            if(actual==null||!Objects.equals(actual.getId(),i.getArgument(0))
                    ||!Objects.equals(actual.getScopeType(),i.getArgument(1))
                    ||!Objects.equals(actual.getScopeId(),i.getArgument(2)))return null;
            return copy(actual);
        });
        when(intents.find(anyLong(),anyString())).thenAnswer(i->copy(rows.get(i.getArgument(0)+"|"+i.getArgument(1))));
        when(intents.findRequest(anyLong(),anyString())).thenAnswer(i->copy(rows.get(i.getArgument(0)+"|"+i.getArgument(1))));
        when(intents.findById(anyLong(),anyLong())).thenAnswer(i->rows.values().stream()
                .filter(r->r.getNpcId().equals(i.getArgument(0))&&r.getId().equals(i.getArgument(1)))
                .findFirst().map(NpcVoiceEnrollmentFixture::copy).orElse(null));
        when(intents.findLatest(anyLong())).thenAnswer(i->rows.values().stream()
                .filter(r->r.getNpcId().equals(i.getArgument(0))).max(Comparator.comparing(NpcVoiceEnrollment::getId))
                .map(NpcVoiceEnrollmentFixture::copy).orElse(null));
        when(intents.history(anyLong(),anyLong())).thenAnswer(i->rows.values().stream()
                .filter(r->r.getNpcId().equals(i.getArgument(0))&&r.getActorMemberId().equals(i.getArgument(1)))
                .sorted(Comparator.comparing(NpcVoiceEnrollment::getId).reversed()).limit(20)
                .map(NpcVoiceEnrollmentFixture::copy).toList());
        when(intents.findUnresolved(anyLong())).thenAnswer(i->rows.values().stream()
                .filter(r->r.getNpcId().equals(i.getArgument(0)))
                .filter(r->r.getState().equals("IN_FLIGHT")||r.getState().equals("UNKNOWN"))
                .min(Comparator.comparing(NpcVoiceEnrollment::getId)).map(NpcVoiceEnrollmentFixture::copy).orElse(null));
        when(intents.insert(any())).thenAnswer(i->{NpcVoiceEnrollment row=i.getArgument(0);String identity=key(row);
            if(rows.containsKey(identity))throw new AssertionError("duplicate original identity");row.setId(++sequence);rows.put(identity,copy(row));return 1;});
        when(intents.claim(anyLong())).thenAnswer(i->{NpcVoiceEnrollment row=rows.values().stream()
                .filter(r->r.getId().equals(i.getArgument(0))).findFirst().orElseThrow();
            if(!row.getState().equals("PREPARED"))return 0;row.setState("IN_FLIGHT");return 1;});
        when(intents.complete(any())).thenAnswer(i->{NpcVoiceEnrollment row=i.getArgument(0);
            rows.put(key(row),copy(row));return 1;});
        service=new NpcVoiceEnrollmentService(intents,profiles,provider,manager);
    }

    private static String key(NpcVoiceEnrollment row){return row.getNpcId()+"|"+(row.getRequestId().startsWith("sample:")?row.getSampleSha256():row.getRequestId());}
    public int intentCount(){return rows.size();}
    public String state(Long npcId,String canonicalSample){NpcVoiceEnrollment row=rows.get(npcId+"|"+NpcVoiceEnrollmentService.fingerprint(canonicalSample));return row==null?null:row.getState();}
    private static NpcProfile copy(NpcProfile value){NpcProfile result=new NpcProfile();BeanUtils.copyProperties(value,result);return result;}
    private static NpcVoiceEnrollment copy(NpcVoiceEnrollment value){if(value==null)return null;NpcVoiceEnrollment result=new NpcVoiceEnrollment();BeanUtils.copyProperties(value,result);return result;}
}
