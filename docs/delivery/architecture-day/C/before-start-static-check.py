#!/usr/bin/env python3
"""This patch's mechanical/source checks only. Never invokes Java, DB, HTTP or CI."""
import hashlib,json,re,subprocess
from pathlib import Path
from xml.etree import ElementTree as ET
ROOT=Path(__file__).resolve().parents[4]
BASE='def951bef36a94417d90259906f6bda6abe6efb3'
SYS='chengyinhub-system/src/main/'
OWNER=SYS+'java/com/chengyinhub/business/service/support/CommittedOfficialNoticeService.java'
TEAM=SYS+'java/com/chengyinhub/business/service/impl/TeamGroupServiceImpl.java'
def read(p):return (ROOT/p).read_text()
def old(p):return subprocess.check_output(['git','show',BASE+':'+p],cwd=ROOT,text=True)
def block(s,pos):
 a=s.index('{',pos);state='code';depth=0;i=a
 while i<len(s):
  c=s[i];pair=s[i:i+2]
  if state=='line':
   if c=='\n':state='code'
  elif state=='comment':
   if pair=='*/':state='code';i+=1
  elif state in ('"',"'"):
   if c=='\\':i+=1
   elif c==state:state='code'
  elif pair=='//':state='line';i+=1
  elif pair=='/*':state='comment';i+=1
  elif c in ('"',"'"):state=c
  elif c=='{':depth+=1
  elif c=='}':
   depth-=1
   if depth==0:return s[a:i+1]
  i+=1
 raise AssertionError(pos)
def method(s,name):
 m=re.search(r'^    (?:public|private|protected)[^\n=;{]*\b'+name+r'\([^)]*\)\s*\{',s,re.M)
 assert m,name
 return block(s,m.end()-1)
source=read(OWNER)
assert method(source,'enqueueOfficial')==method(old(OWNER),'enqueueOfficial'),'original broadcast enqueue drift'
fp=method(source,'fingerprint');original=method(old(OWNER),'fingerprint')
assert fp[fp.index('        return CanonicalDigest.sha256Hex(JSON.toJSONString(Arrays.asList(row.getEventKey()'):]==original[original.index('        return CanonicalDigest.sha256Hex'):]
assert source.index('im.deliverSystemNotification(')<source.index('claim(id,CommittedOfficialNoticeWx.OFFICIAL_BROADCAST)')
assert 'if(CommittedOfficialNoticeWx.BEFORE_START.equals(frozen.getNoticeType())) return;' in method(source,'deliver')
start=method(source,'beforeStart');assert start.index('mapper.selectByKeyForUpdate(key)')<start.index('beforeStartWx.prepareBeforeStart(')<start.index('mapper.insert(requested)')
assert start.index('!CommittedOfficialNoticeWx.PENDING.equals(stored.getWxState())')<start.index('claim(stored.getId(),CommittedOfficialNoticeWx.BEFORE_START)')
assert 'beforeStartWx' not in method(source,'readBeforeStart') and 'claim(' not in method(source,'readBeforeStart')
assert 'return "before-start:"+ticketId+":"+recipient;' in method(source,'beforeStartKey')
assert 'beforeStartWindow(validateBeforeStartPayload(row).startsAt())' in method(source,'claim')
assert method(source,'sendAndFinalize').index('outsideTransaction()')<method(source,'sendAndFinalize').index('sendPreparedBeforeStart(')
xmlpath=SYS+'resources/mapper/business/CommittedOfficialNoticeWxMapper.xml'
xml=ET.fromstring(read(xmlpath));before=ET.fromstring(old(xmlpath))
for statement in ['claimUnknown','accepted','selectByKeyForUpdate','selectByIdForUpdate']:
 def node(tree):return next(n for n in tree if n.get('id')==statement)
 assert ET.tostring(node(xml))==ET.tostring(node(before)),statement+' changed'
for statement in ['maxPendingId','selectPendingPage']:
 n=next(n for n in xml if n.get('id')==statement)
 assert "notice_type='OFFICIAL_BROADCAST'" in ''.join(n.itertext())
for n in xml.findall('select'):
 assert n.get('useCache')=='false' and n.get('flushCache')=='true',n.get('id')
wxpath=SYS+'java/com/chengyinhub/business/service/impl/WxSubscribeMsgServiceImpl.java'
for name in ['notifyRefund','notifySignupSuccess','notifyCoopInvited','notifyClubInterested','notifyCoopStatus','notifyRecruit','notifyCoopSettle','notifyOfficialBroadcast','notifyRoamRecall','sendToMember','wxDate5','nickOf','openidOf']:
 assert method(read(wxpath),name)==method(old(wxpath),name),name+' drift'
oldsend=re.search(r'    private boolean send\(String openid,[\s\S]*?Long memberId, String templateKey, String businessEvent\) \{',old(wxpath))
newsend=re.search(r'    private boolean send\(String openid,[^}]*?boolean frozenPage\) \{',read(wxpath))
assert oldsend and newsend
original_core=block(old(wxpath),oldsend.end()-1)
new_core=block(read(wxpath),newsend.end()-1)
assert new_core.replace('(frozenPage ? page : (StringUtils.isEmpty(page) ? defaultPage : page))','(StringUtils.isEmpty(page) ? defaultPage : page)')==original_core,'original subscription wire renderer drift'
notifypath=SYS+'java/com/chengyinhub/business/service/impl/NotifyServiceImpl.java'
for name in ['notifyBeforeStart','notifyOfficialBroadcast','notifyRefund','fanout']:
 assert method(read(notifypath),name)==method(old(notifypath),name),'legacy '+name+' drift'
# Apply in memory against the pinned original, then identify the exact working-tree state.
patch=Path(__file__).with_name('before-start-team-group.review.patch').read_text()
def shared_patch_state(baseline,current,patch):
 assert re.findall(r'^\+\+\+ b/(.*)$',patch,re.M)==[TEAM]
 updated=baseline
 for hunk in re.split(r'^@@[^\n]*\n',patch,flags=re.M)[1:]:
  lines=hunk.splitlines(True)
  a=''.join(line[1:] for line in lines if line.startswith((' ','-')))
  b=''.join(line[1:] for line in lines if line.startswith((' ','+')))
  assert updated.count(a)==1,'shared hunk ambiguity'
  updated=updated.replace(a,b,1)
 if current==baseline:return 'unapplied',updated
 if current==updated:return 'applied',updated
 raise AssertionError('shared file is partially applied or has unreviewed root/A drift')
shared_state,updated=shared_patch_state(old(TEAM),read(TEAM),patch)
check=['git','apply','--check']
if shared_state=='applied':check.append('--reverse')
subprocess.run(check+[str(Path(__file__).with_name('before-start-team-group.review.patch'))],cwd=ROOT,check=True)
# The changed closure is only this method plus its immediately preceding documentation.
def without_closure(s):
 pos=s.index('    public BeforeStartNotifyResult notifyBeforeStartBatch()')
 a=s.rfind('    /**',0,pos);body=block(s,pos);b=s.index('{',pos)+len(body)
 return s[:a]+s[b:]
assert without_closure(updated)==without_closure(old(TEAM)),'A cancellation/helper drift'
producer=method(updated,'notifyBeforeStartBatch')
assert 'notifyService.notifyBeforeStart(t.getId(), r.getMemberId(), t.getName(), t.getStartTime())' in producer
assert 'redisCache.setCacheObject' not in producer
assert 'selectTeamFormedStartingSoon(now, to)' in producer and 'selectPaidByTicket(t.getId())' in producer
assert 'getCacheObject(key)' in producer and '24L * 3600 * 1000' in producer
assert 'captureCancellation' in updated and 'observe' in updated
canonical='chengyinhub-admin/sql/migration_20261024_d_committed_official_notice_wx.sql'
assert read(canonical)==old(canonical)
protected_tests=['CommittedOfficialNoticeMySqlIT','BeforeStartDate5FormatContractTest','NotifyFanoutContractTest']
for name in protected_tests:
 p='chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/'+name+'.java'
 assert read(p)==old(p),name+' changed'
p='chengyinhub-admin/src/test/java/com/chengyinhub/business/task/BeforeStartNotifyTaskFailureSignalTest.java'
assert read(p)==old(p)
counts={}
for name in ['BeforeStartPreparedWxTest','BeforeStartNoticeMySqlIT','BeforeStartNotifyRetryContractTest']+protected_tests:
 p='chengyinhub-admin/src/test/java/com/chengyinhub/business/service/impl/'+name+'.java'
 counts[name]=len(re.findall(r'@Test\b',read(p)))
print(json.dumps({'baseline':BASE,'sourceChecks':'PASS (not Java/DB execution)','sharedPatch':shared_state+'; all other TeamGroup/A code byte-identical','broadcastEnqueueAndLegacyHash':'unchanged','canonicalDMigration':'unchanged','testDeclarationsNotExecuted':counts},ensure_ascii=False,indent=2))
