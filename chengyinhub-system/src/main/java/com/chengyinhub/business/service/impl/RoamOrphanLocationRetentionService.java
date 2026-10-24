package com.chengyinhub.business.service.impl;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.RoamMapper;
import com.chengyinhub.business.mapper.RoamOrphanLocationRetentionMapper;
import com.chengyinhub.business.support.RoamLocationRetention;
import org.springframework.stereotype.Service;
import java.util.Date;
import java.util.List;
import java.util.Map;
@Service
public class RoamOrphanLocationRetentionService {
 private final RoamMapper roam;
 private final RoamOrphanLocationRetentionMapper orphan;
 @org.springframework.beans.factory.annotation.Autowired
 private org.springframework.transaction.PlatformTransactionManager transactionManager;
 public RoamOrphanLocationRetentionService(RoamMapper roam,RoamOrphanLocationRetentionMapper orphan) {this.roam=roam;this.orphan=orphan;}
 public int clearTiles(Date cutoff,int limit) {return readAndClear("tile",cutoff,limit);}
 public int clearPois(Date cutoff,int limit) {return readAndClear("poi",cutoff,limit);}
 public int clearShops(Date cutoff,int limit) {return readAndClear("shop",cutoff,limit);}
 private int readAndClear(String kind,Date cutoff,int limit) {
  if(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
   throw new IllegalStateException("Orphan roam cleanup candidates require a transaction-free read");
  List<Map<String,Object>> candidates="tile".equals(kind)?orphan.tiles(cutoff,limit):"poi".equals(kind)?orphan.pois(cutoff,limit):orphan.shops(cutoff,limit);
  if(candidates.isEmpty())return 0;
  // Candidate reads must not pin an RR snapshot before waiting on the real owner mutex.
  return new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
   java.util.SortedSet<Long> members=new java.util.TreeSet<>();
   for(Map<String,Object> candidate:candidates)members.add(((Number)candidate.get("memberId")).longValue());
   for(Long member:members)roam.lockClaimOwner(member);
   java.util.SortedMap<Long,java.util.SortedSet<Long>> initialSessions=new java.util.TreeMap<>();
   for(Map<String,Object> candidate:candidates)if(candidate.get("sessionId") instanceof Number) {
    Long member=((Number)candidate.get("memberId")).longValue(),sessionId=((Number)candidate.get("sessionId")).longValue();
    initialSessions.computeIfAbsent(member,k -> new java.util.TreeSet<>()).add(sessionId);
   }
   Map<String,RoamSession> sessions=new java.util.HashMap<>();
   for(Map.Entry<Long,java.util.SortedSet<Long>> entry:initialSessions.entrySet())for(Long sessionId:entry.getValue()) {
    Long member=entry.getKey();RoamSession seen=roam.selectSessionById(sessionId);
    // Never acquire a foreign session lock while holding only this fact's owner lock.
    if(seen!=null && member.equals(seen.getMemberId()))
     sessions.put(member+":"+sessionId,roam.selectOwnedSessionForUpdate(sessionId,member));
   }
   // Lock every initial owned session before any fact/key write; no later row expands this batch.
   roam.requireClaimKey();int cleared=0;
   for(Map<String,Object> candidate:candidates) {
    Long id=((Number)candidate.get("id")).longValue(),member=((Number)candidate.get("memberId")).longValue();
    Long sessionId=candidate.get("sessionId") instanceof Number?((Number)candidate.get("sessionId")).longValue():null;
    RoamSession session=sessionId==null?null:sessions.get(member+":"+sessionId);
    Map<String,Object> row="tile".equals(kind)?roam.selectCleanupTileForUpdate(id,member,sessionId)
      :"poi".equals(kind)?roam.selectCleanupPoiForUpdate(id,member,sessionId):roam.selectCleanupShopForUpdate(id,member,sessionId);
    if(row==null)continue; // Initial owner/session association changed, or another cleanup finished.
    // Anonymous aggregate row locks follow the same shop order in every cleanup transaction.
    if("shop".equals(kind) && (!java.util.Objects.equals(String.valueOf(candidate.get("sourceType")),String.valueOf(row.get("sourceType")))
      || !java.util.Objects.equals(String.valueOf(candidate.get("sourceId")),String.valueOf(row.get("sourceId")))))continue;
    Date collectedAt=(Date)row.get("collectedAt");
    if(collectedAt==null) {
     if(session!=null && Integer.valueOf(RoamSession.STATUS_ACTIVE).equals(session.getStatus()))continue;
    } else if(collectedAt.after(cutoff) || session!=null)continue;
    if("shop".equals(kind))roam.recordAnonymousShopVisitor(member,((Number)row.get("sourceType")).intValue(),
      ((Number)row.get("sourceId")).longValue());
    String fingerprint=RoamLocationRetention.claimFingerprint(kind,member,row.get("location"));
    int changed="tile".equals(kind)?roam.clearSessionTileLocation(id,member,fingerprint)
      :"poi".equals(kind)?roam.clearSessionPoiLocation(id,member,fingerprint):roam.clearSessionShopLocation(id,member,fingerprint);
    if(changed!=1)throw new IllegalStateException("Orphan roam location cleanup receipt missing");
    cleared++;
   }
   return cleared;
  });
 }
}
