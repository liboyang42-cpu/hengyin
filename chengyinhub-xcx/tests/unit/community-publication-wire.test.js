const test=require('node:test'),assert=require('node:assert/strict');
const {createClient}=require('../../utils/community-post-api');
const intent=require('../../utils/publish/publish-intent');
const tick=()=>new Promise(r=>setImmediate(r));
function harness(existingStore){let actor=990005;const store=existingStore||{},calls=[],results=[],errors=[];
 const wx={getStorageSync:k=>store[k],setStorageSync(k,v){store[k]=JSON.parse(JSON.stringify(v))},removeStorageSync:k=>delete store[k]};
 const app={getUserID:()=>actor,sendRequest(o){calls.push(o);return{abort(){}}}};
 const client=createClient(app,wx);let approvals=0;
 const invoke=(method,args={})=>client[method]({scope:'square:list',current:()=>actor===990005,autoErrorToast:false,selectGuideline:()=>{approvals++;return Promise.resolve(true)},...args,success:r=>results.push(r),fail:e=>errors.push(e)});
 return{store,wx,client,calls,results,errors,invoke,setActor(v){actor=v},approvals:()=>approvals};
}
const guideline={id:7,title:'真实规则',content_json:JSON.stringify({rules:[{title:'规则一'}]})};
function bundle(body,version=0,lifecycle='DRAFT',postId=59){return{post:{id:postId,authorId:990005,version,lifecycle,
 ...JSON.parse(JSON.stringify(body)),safetyLabelsJson:JSON.stringify(body.safetyLabels),likeCount:0,commentCount:0,bookmarkCount:0,viewerLiked:0,viewerBookmarked:0},media:(body.mediaIds||[]).map((id,i)=>({id,media_type:'IMAGE',object_key:'/image'+i})),references:(body.references||[]).map(r=>({...r,snapshot_json:'{}'})),mentions:body.mentionedMemberIds||[],topicCodes:body.topicCodes||[],viewerIsAuthor:true,viewerCanComment:false,viewerFollowing:false}}
async function approve(h){await tick();assert.equal(h.calls[0].url,'/api/v1/community/guidelines/active');h.calls[0].success({code:200,data:guideline});await tick();assert.equal(h.calls[1].url,'/api/v1/community/guidelines/ack');h.calls[1].success({code:200});await tick()}
function editBody(){return {communityId:null,postType:'MOMENT',title:null,body:'原文',audience:'PRIVATE',commentPolicy:'OFF',replyApprovalEnabled:1,slowModeSeconds:30,locationPrecision:'EXACT',cityCode:null,poiName:'私密位置',approximateGeohash:'wtw3sjq6g',disclosureType:'NONE',safetyLabels:['CONTENT_WARNING'],topicCodes:['CITY'],mentionedMemberIds:[990012],references:[{referenceType:'TOPIC',referenceId:3}],guidelineVersionId:7,clientRequestId:'original-create-key',mediaIds:[]}}
test('create reply lost: cold retry uses exact frozen full body and key without refreshing guidelines',async()=>{
 const h=harness();h.invoke('publishNew',{form:{body:'正文',mediaPaths:[]}});await approve(h);const create=h.calls[2];assert.equal(create.url,'/api/v1/community/posts');const frozen=JSON.parse(JSON.stringify(create.data));create.fail({errMsg:'timeout'});await tick();assert.equal(h.errors[0].unknown,true);
 const cold=harness(h.store);cold.invoke('publishNew',{form:{body:'正文',mediaPaths:[]}});await tick();assert.equal(cold.calls.length,1);assert.equal(cold.calls[0].url,'/api/v1/community/posts');assert.deepEqual(cold.calls[0].data,frozen);assert.equal(cold.approvals(),0);
 cold.calls[0].success({code:200,data:bundle(frozen)});await tick();const publish=cold.calls[1];assert.equal(publish.url,'/api/v1/community/posts/59/publish');assert.equal(publish.data.expectedVersion,0);publish.success({code:200,data:bundle(frozen,1,'CHECKING')});await tick();assert.equal(cold.results[0].data.post.lifecycle,'CHECKING');assert.equal(intent.pending(cold.wx,'square:list','990005'),null);
});
test('publish unknown retains draft and exact publish command while authoritative readback runs first',async()=>{
 const h=harness();h.invoke('publishNew',{form:{body:'正文',mediaPaths:[]}});await approve(h);const body=JSON.parse(JSON.stringify(h.calls[2].data));h.calls[2].success({code:200,data:bundle(body)});await tick();const pub=JSON.parse(JSON.stringify(h.calls[3].data));h.calls[3].fail({errMsg:'timeout'});await tick();const pending=intent.pending(h.wx,'square:list','990005');assert.equal(pending.draftId,59);assert.deepEqual(pending.publishBody,pub);
 const cold=harness(h.store);cold.invoke('publishNew',{form:{body:'正文',mediaPaths:[]}});await tick();assert.equal(cold.calls[0].url,'/api/v1/community/posts/59');cold.calls[0].success({code:200,data:bundle(body,1,'CHECKING')});await tick();assert.equal(cold.calls[1].url,'/api/v1/community/posts/59/publish');assert.deepEqual(cold.calls[1].data,pub);cold.calls[1].success({code:200,data:bundle(body,1,'CHECKING')});await tick();assert.equal(cold.results[0].code,200);
});
test('storage failure prevents the first write and missing receipt cannot fabricate media',async()=>{
 const h=harness();h.wx.setStorageSync=()=>{throw new Error('full')};h.invoke('publishNew',{form:{body:'正文',mediaPaths:[]}});await tick();assert.equal(h.calls.length,0);assert.equal(h.results[0].code,507);
 const bad=harness();bad.invoke('publishNew',{form:{body:'正文',mediaPaths:['/old-url']}});await tick();assert.equal(bad.calls.length,0);assert.equal(bad.results[0].code,409);
});
test('unknown media registration cold replay preserves receipt, upload key and returned media id',async()=>{
 const h=harness();h.client.captureUpload({url:'/image0',uploadReceipt:'synthetic-unit-receipt',byteSize:64,mimeType:'image/png'});h.invoke('publishNew',{form:{body:'正文',mediaPaths:['/image0']}});await approve(h);const media=JSON.parse(JSON.stringify(h.calls[2].data));assert.equal(h.calls[2].url,'/api/v1/community/media/register');h.calls[2].fail({errMsg:'timeout'});await tick();
 const cold=harness(h.store);cold.invoke('publishNew',{form:{body:'正文',mediaPaths:['/image0']}});await tick();assert.equal(cold.calls[0].url,'/api/v1/community/media/register');assert.deepEqual(cold.calls[0].data,media);cold.calls[0].success({code:200,data:{id:11}});await tick();assert.deepEqual(cold.calls[1].data.mediaIds,[11]);cold.calls[1].success({code:200,data:bundle(cold.calls[1].data)});await tick();cold.calls[2].success({code:200,data:bundle(cold.calls[1].data,1,'PUBLISHED')});await tick();assert.equal(cold.results[0].code,200);
});
test('EXACT PUBLIC and legacy raw location stop before writes; no silent location removal',async()=>{
 for(const form of [{body:'正文',audience:'PUBLIC',locationPrecision:'EXACT',approximateGeohash:'wtw3sjq6g'}, {body:'正文',longitude:0,latitude:0}]){const h=harness();h.invoke('publishNew',{form});await tick();assert.equal(h.calls.length,0);assert.equal(h.results[0].code,409);assert.equal(intent.pending(h.wx,'square:list','990005').input.body,'正文')}
});
test('PATCH full snapshot preserves private location, policy, topics, mentions and unknown is reconciled read-only',async()=>{
 const h=harness(), original=editBody();h.invoke('saveEdit',{postId:59,changes:{body:'新文'}});await tick();h.calls[0].success({code:200,data:bundle(original)});await tick();const patch=h.calls[1];assert.equal(patch.method,'PATCH');assert.equal(patch.data.expectedVersion,0);for(const key of ['audience','commentPolicy','locationPrecision','approximateGeohash','topicCodes','mentionedMemberIds','references','slowModeSeconds'])assert.deepEqual(patch.data[key],original[key]);const frozen=JSON.parse(JSON.stringify(patch.data));patch.fail({errMsg:'timeout'});await tick();
 const cold=harness(h.store);cold.invoke('saveEdit',{postId:59,changes:{body:'新文'}});await tick();assert.equal(cold.calls[0].method,'GET');cold.calls[0].success({code:200,data:bundle(frozen,1)});await tick();assert.equal(cold.calls.length,1);assert.equal(cold.results[0].code,200);
});
test('PATCH unchanged is not automatically retried; explicit same-body retry retains CAS version',async()=>{
 const h=harness(), original=editBody();h.invoke('saveEdit',{postId:59,changes:{body:'新文'}});await tick();h.calls[0].success({code:200,data:bundle(original)});await tick();const frozen=JSON.parse(JSON.stringify(h.calls[1].data));h.calls[1].fail({errMsg:'timeout'});await tick();
 const cold=harness(h.store);cold.invoke('saveEdit',{postId:59,changes:{body:'新文'}});await tick();cold.calls[0].success({code:200,data:bundle(original)});await tick();assert.equal(cold.calls.length,1);assert.equal(cold.results[0].retrySameWire,true);
 const retry=harness(h.store);retry.invoke('saveEdit',{postId:59,changes:{body:'新文'},retryUnknown:true});await tick();retry.calls[0].success({code:200,data:bundle(original)});await tick();assert.deepEqual(retry.calls[1].data,frozen);retry.calls[1].success({code:200,data:bundle(frozen,1)});await tick();assert.equal(retry.results[0].code,200);
});
test('PATCH higher version conflict and missing topic projection retain input and never overwrite',async()=>{
 for(const missing of [false,true]){const h=harness(),original=editBody();h.invoke('saveEdit',{postId:59,changes:{body:'新文'}});await tick();const old=bundle(original);if(missing)delete old.topicCodes;h.calls[0].success({code:200,data:old});await tick();if(missing){assert.equal(h.calls.length,1);assert.equal(h.results[0].code,502);continue}h.calls[1].fail({errMsg:'timeout'});await tick();const cold=harness(h.store);cold.invoke('saveEdit',{postId:59,changes:{body:'新文'},retryUnknown:true});await tick();cold.calls[0].success({code:200,data:bundle({...original,body:'另一处修改'},3)});await tick();assert.equal(cold.calls.length,1);assert.equal(cold.results[0].conflict,true)}
});
test('cancel after create dispatch preserves unknown durable wire without claiming server rollback',async()=>{
 const h=harness();const task=h.invoke('publishNew',{form:{body:'正文',mediaPaths:[]}});await approve(h);assert.equal(h.calls[2].method,'POST');task.abort();await tick();const pending=intent.pending(h.wx,'square:list','990005');assert.equal(pending.status,'unknown');assert.ok(pending.createBody);assert.equal(h.results.length,0);
});
test('late create response cannot replace a newer intent or silently publish different contents',async()=>{
 const h=harness();h.invoke('publishNew',{form:{body:'正文',mediaPaths:[]}});await approve(h);const sent=JSON.parse(JSON.stringify(h.calls[2].data));const newer=intent.begin(h.wx,{scope:'square:list',memberId:'990005',payload:['CREATE',null,{body:'新输入',mediaPaths:[]}]});h.calls[2].success({code:200,data:bundle(sent)});await tick();assert.equal(h.calls.length,3);assert.equal(intent.pending(h.wx,'square:list','990005').key,newer.key);assert.equal(h.results[0].code,507);
 const mismatch=harness();mismatch.invoke('publishNew',{form:{body:'正文',mediaPaths:[]}});await approve(mismatch);const original=mismatch.calls[2].data;mismatch.calls[2].success({code:200,data:bundle({...original,body:'另一处已修改'})});await tick();assert.equal(mismatch.calls.length,3);assert.equal(mismatch.results[0].conflict,true);assert.equal(intent.pending(mismatch.wx,'square:list','990005').draftId,59);
});
test('editing image selection preserves invisible FILE media and unchanged relations',async()=>{
 const h=harness(),original=editBody();original.mediaIds=[10,11,12];const before=bundle(original);before.media[1].media_type='FILE';before.media[1].object_key='/file';h.invoke('saveEdit',{postId:59,changes:{body:'新文',mediaPaths:['/image0','/image2']}});await tick();h.calls[0].success({code:200,data:before});await tick();assert.deepEqual(h.calls[1].data.mediaIds,[10,11,12]);const after=bundle(h.calls[1].data,1);after.media[1].media_type='FILE';h.calls[1].success({code:200,data:after});await tick();assert.equal(h.results[0].code,200);
});
test('changed input while create unknown requires explicit recovery and preserves old key/full wire',async()=>{const h=harness();h.invoke('publishNew',{form:{body:'原输入',mediaPaths:[]}});await approve(h);const body=structuredClone(h.calls[2].data);h.calls[2].fail({errMsg:'lost'});await tick();const old=intent.pending(h.wx,'square:list','990005');h.invoke('publishNew',{form:{body:'新输入',mediaPaths:[],audience:'PRIVATE'}});await tick();assert.equal(h.calls.length,3);assert.equal(h.results.at(-1).requiresRecovery,true);assert.deepEqual(intent.pending(h.wx,'square:list','990005'),old);h.invoke('publishNew',{form:{body:'新输入',mediaPaths:[],audience:'PRIVATE'},recoverPrevious:true});await tick();assert.deepEqual(h.calls[3].data,body);h.calls[3].success({code:200,data:bundle(body)});await tick();h.calls[4].success({code:200,data:bundle(body,1,'CHECKING')});await tick();assert.equal(h.results.at(-1).recoveredPrevious,true);assert.equal(h.results.at(-1).data.post.body,'原输入');assert.equal(intent.pending(h.wx,'square:list','990005'),null)});
test('ACK unknown cold recovery retains the actual frozen upload receipt and original body',async()=>{const h=harness();const receipt={url:'/image0',uploadReceipt:'synthetic-unit-receipt',byteSize:64,mimeType:'image/png'};h.client.captureUpload(receipt);h.invoke('publishNew',{form:{body:'原输入',mediaPaths:['/image0']}});await tick();h.calls[0].success({code:200,data:guideline});await tick();const ack=structuredClone(h.calls[1].data);h.calls[1].fail({errMsg:'lost'});await tick();const before=intent.pending(h.wx,'square:list','990005');assert.equal(before.mediaBodies['/image0'].uploadReceipt,receipt.uploadReceipt);const cold=harness(h.store);cold.invoke('publishNew',{form:{body:'新输入',mediaPaths:[]},recoverPrevious:true});await tick();assert.deepEqual(cold.calls[0].data,ack);cold.calls[0].success({code:200});await tick();assert.deepEqual(cold.calls[1].data,before.mediaBodies['/image0']);cold.calls[1].success({code:200,data:{id:10}});await tick();assert.equal(cold.calls[2].data.body,'原输入');assert.deepEqual(cold.calls[2].data.mediaIds,[10])});
test('old PATCH applied recovery returns local old-result metadata without saving new input',async()=>{const h=harness(),original=editBody();h.invoke('saveEdit',{postId:59,changes:{body:'旧修改'}});await tick();h.calls[0].success({code:200,data:bundle(original)});await tick();const patch=structuredClone(h.calls[1].data);h.calls[1].fail({errMsg:'lost'});await tick();const old=intent.pending(h.wx,'square:list','990005');h.invoke('saveEdit',{postId:59,changes:{body:'新输入'}});await tick();assert.equal(h.results.at(-1).requiresRecovery,true);assert.deepEqual(intent.pending(h.wx,'square:list','990005'),old);h.invoke('saveEdit',{postId:59,changes:{body:'新输入'},recoverPrevious:true});await tick();h.calls[2].success({code:200,data:bundle(patch,1)});await tick();assert.equal(h.calls.length,3);assert.equal(h.results.at(-1).recoveredPrevious,true);assert.equal(h.results.at(-1).data.post.body,'旧修改')});

async function deletedIntent(withMedia=false){
 const h=harness(),form={body:'保留正文',audience:'PRIVATE',references:[{referenceType:'TOPIC',referenceId:3}],mediaPaths:withMedia?['/old-image']:[]};
 if(withMedia)h.client.captureUpload({url:'/old-image',uploadReceipt:'old-receipt',byteSize:64,mimeType:'image/png'});
 h.invoke('publishNew',{form});await approve(h);
 if(withMedia){h.calls[2].success({code:200,data:{id:11}});await tick()}
 const create=h.calls.at(-1),wire=structuredClone(create.data);create.fail({errMsg:'lost'});await tick();
 h.invoke('publishNew',{form});await tick();assert.deepEqual(h.calls.at(-1).data,wire);
 h.calls.at(-1).success({code:200,data:{intentDiscarded:true}});await tick();
 assert.equal(h.results.at(-1).requiresRestart,true);return{h,form,wire,pending:structuredClone(intent.pending(h.wx,'square:list','990005'))};
}
test('deleted receipt never automatically discards; reused image URL or receipt stops before new writes',async()=>{
 for(const sameReceipt of [false,true]){
  const {h,form,wire,pending}=await deletedIntent(true),replacement=sameReceipt?'/fresh-url':'/old-image';
  if(sameReceipt)h.client.captureUpload({url:replacement,uploadReceipt:'old-receipt',byteSize:64,mimeType:'image/png'});
  const input={...form,mediaPaths:[replacement]},before=h.calls.length;
  h.invoke('publishNew',{form:input,restartDiscarded:true});await tick();assert.deepEqual(h.calls.at(-1).data,wire);
  h.calls.at(-1).success({code:200,data:{intentDiscarded:true}});await tick();
  assert.equal(h.calls.length,before+1);assert.equal(h.results.at(-1).requiresReupload,true);
  const actual=intent.pending(h.wx,'square:list','990005');assert.equal(actual.key,pending.key);assert.deepEqual(actual.input,form);assert.deepEqual(actual.createBody,wire);
 }
});
test('confirmed deleted restart registers a fresh SDK receipt and creates a new key while preserving content and PRIVATE',async()=>{
 const {h,form,wire,pending}=await deletedIntent(true),input={...form,mediaPaths:['/fresh-image']};
 h.client.captureUpload({url:'/fresh-image',uploadReceipt:'fresh-receipt',byteSize:80,mimeType:'image/png'});
 const start=h.calls.length;h.invoke('publishNew',{form:input,restartDiscarded:true});await tick();
 assert.deepEqual(h.calls[start].data,wire);h.calls[start].success({code:200,data:{intentDiscarded:true}});await tick();
 assert.equal(h.calls[start+1].url,'/api/v1/community/guidelines/active');h.calls[start+1].success({code:200,data:guideline});await tick();h.calls[start+2].success({code:200});await tick();
 const media=h.calls[start+3];assert.equal(media.url,'/api/v1/community/media/register');assert.equal(media.data.uploadReceipt,'fresh-receipt');assert.equal(media.data.objectKey,'/fresh-image');media.success({code:200,data:{id:17}});await tick();
 const create=h.calls[start+4];assert.notEqual(create.data.clientRequestId,pending.key);assert.equal(create.data.body,form.body);assert.equal(create.data.audience,'PRIVATE');assert.deepEqual(create.data.references,form.references);assert.deepEqual(create.data.mediaIds,[17]);
 const trace=h.store[intent.storageKey('square:list','990005')+':previous'];assert.equal(trace.key,pending.key);assert.equal(trace.status,'discarded');
 create.success({code:200,data:bundle(create.data,0,'DRAFT',60)});await tick();h.calls[start+5].success({code:200,data:bundle(create.data,1,'CHECKING',60)});await tick();assert.equal(h.results.at(-1).code,200);assert.notEqual(h.results.at(-1).recoveredPrevious,true);
});
test('deleted restart requires a durable discarded trace before creating a new intent',async()=>{
 const {h,form,wire,pending}=await deletedIntent(),set=h.wx.setStorageSync;
 h.wx.setStorageSync=(key,value)=>{if(key.endsWith(':previous'))throw new Error('full');set(key,value)};
 const before=h.calls.length;h.invoke('publishNew',{form,restartDiscarded:true});await tick();assert.deepEqual(h.calls.at(-1).data,wire);h.calls.at(-1).success({code:200,data:{intentDiscarded:true}});await tick();
 assert.equal(h.calls.length,before+1);assert.equal(h.results.at(-1).code,507);assert.equal(intent.pending(h.wx,'square:list','990005').key,pending.key);
});
for(const failure of ['remove-throws','remove-noop','readback-throws'])test('successful publish cleanup '+failure+' returns 507 and never starts a replacement create',async()=>{
 const h=harness();h.invoke('publishNew',{form:{body:'同文新意图',mediaPaths:[]}});await approve(h);const body=structuredClone(h.calls[2].data);h.calls[2].success({code:200,data:bundle(body)});await tick();
 const get=h.wx.getStorageSync;
 if(failure==='remove-throws')h.wx.removeStorageSync=()=>{throw new Error('storage')};
 else if(failure==='remove-noop')h.wx.removeStorageSync=()=>{};
 else{h.wx.removeStorageSync=()=>{};let readCount=0;h.wx.getStorageSync=key=>{if(++readCount===2)throw new Error('readback');return get(key)}}
 h.calls[3].success({code:200,data:bundle(body,1,'CHECKING')});await tick();assert.equal(h.results.at(-1).code,507);assert.equal(h.results.at(-1).draftId,59);
 h.wx.getStorageSync=get;const pending=intent.pending(h.wx,'square:list','990005');assert.equal(pending.key,body.clientRequestId);assert.deepEqual(pending.createBody,body);assert.equal(pending.draftId,59);
 const before=h.calls.length;h.invoke('publishNew',{form:{body:'同文新意图',mediaPaths:[]}});await tick();assert.equal(h.calls.length,before+1);assert.equal(h.calls.at(-1).url,'/api/v1/community/posts/59');assert.equal(h.calls.filter(c=>c.url==='/api/v1/community/posts').length,1);
});
