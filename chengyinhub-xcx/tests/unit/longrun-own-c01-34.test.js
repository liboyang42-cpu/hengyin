'use strict';
// 原请求身份与 UNKNOWN 恢复的真实 Page 回归。
const {test}=require('node:test');const assert=require('node:assert/strict');const path=require('node:path');
function load(){let definition;const requests=[],disk=new Map();global.getApp=()=>({globalData:{},getUserID:()=> '11',sendRequest:r=>requests.push(r)});global.Page=p=>{definition=p;};global.wx={showToast(){},getStorageSync:k=>disk.get(k),setStorageSync:(k,v)=>disk.set(k,JSON.parse(JSON.stringify(v)))};const file=path.resolve(__dirname,'../../pages/play/index.js');delete require.cache[file];delete require.cache[require.resolve('../../pages/play/utils/play-route-state')];require(file);const page=Object.assign({},definition,{data:{activityId:9,topicId:8,mode:1,game:{nodeId:31},answerInput:'original'},_routeState:{sessionId:'99',version:4},setData(p){Object.assign(this.data,p);}});return{page,requests};}
test('original answer and action key survive UNKNOWN and recovery only reads receipt',async()=>{
 const h=load();h.page.submitGame();await new Promise(r=>setImmediate(r));
 assert.equal(h.requests[0].url,'/api/play/action-context');
 h.requests[0].success({code:200,data:{routeSessionId:'99',expectedRouteVersion:4}});await new Promise(r=>setImmediate(r));
 const sent=h.requests[1];assert.equal(sent.data.answer,'original');sent.fail({errMsg:'fixture'});await new Promise(r=>setImmediate(r));
 h.page.data.answerInput='replacement';h.page.retryGameSubmission();await new Promise(r=>setImmediate(r));assert.equal(h.requests.length,2);
 const reading=h.page._readBasicRouteIntent('answer',31);const query=h.requests[2];assert.equal(query.method,'GET');assert.equal(query.url,'/api/play/action-receipt');assert.equal(query.data.routeActionId,sent.data.routeActionId);assert.equal(query.data.answer,undefined);
 query.success({code:520});await reading;assert.equal(h.page._gameWriteUnknown,true);
});
test('attempt from another game cannot be retried',()=>{const h=load();const old=h.page._legacyGameContext();h.page.recordGameNetworkFailure('fixture',{type:'photo',nodeId:31,pic:'fixture',viewContext:old});h.page.data.game={nodeId:32};h.page.retryGameSubmission();assert.deepEqual(h.requests,[]);});
test('HTTP unknown never dispatches a retry',()=>{const h=load();h.page._gameWriteUnknown=true;h.page._lastGameAttempt={type:'answer',viewContext:h.page._legacyGameContext()};h.page.retryGameSubmission();assert.deepEqual(h.requests,[]);});

test('retry preserves original session, version, epoch and input after state refresh',()=>{
 const h=load(),original={routeActionId:'same-action',routeSessionId:'9007199254740993',expectedRouteVersion:4,runtimeInstance:'run-original',authorityEpoch:2,answer:'original'};
 h.page._routeState={sessionId:'new-session',version:9};
 assert.deepEqual(h.page._retriedRouteWriteBody(original,true),original);
});
test('UNKNOWN retains the exact attempt and cannot dispatch another write',()=>{
 const h=load(),attempt={type:'answer',nodeId:31,payload:{routeActionId:'original',routeSessionId:'71',expectedRouteVersion:4,answer:'original'}};
 h.page._lastGameAttempt=attempt;h.page._hasGenericProofFlow=()=>false;
 h.page.recordGameHttpUnknown('unknown',31);
 assert.equal(h.page._lastGameAttempt,attempt);assert.equal(h.page._gameWriteUnknown,true);
 h.page.retryGameSubmission();assert.deepEqual(h.requests,[]);
});
