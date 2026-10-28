'use strict';
const { test, beforeEach } = require('node:test');
const assert = require('node:assert/strict');
const { project } = require('../../pages/play/utils/play-board-view.js');
let spec, requests=[];
const app={getUserID:()=>7,globalData:{features:{}},isDevEnv:()=>false,sendRequest:o=>requests.push(o)};
global.getApp=()=>app;global.Page=x=>{spec=x};global.Behavior=x=>x;
global.wx={getStorageSync:()=>'',getSystemInfoSync:()=>({statusBarHeight:20})};
require('../../pages/play/index.js');
const row=(patch={})=>({rank:'1',scoreCenti:'9007199254740993',elapsedMs:null,reachedEnding:true,mine:false,rowKey:'row-a',...patch});
const flush=async()=>{for(let i=0;i<8;i++)await Promise.resolve();};
function page(){return {...spec,_routeState:{sessionId:'51'},data:{...structuredClone(spec.data),mode:1,topicId:'17',activityId:0},
  setData(patch){for(const [k,v] of Object.entries(patch)){const keys=k.split('.');let obj=this.data;for(const key of keys.slice(0,-1))obj=obj[key];obj[keys.at(-1)]=v;}}};}
beforeEach(()=>{requests=[];});
test('complete score model preserves server rank order, precise centi and unknown elapsed',()=>{
 const data={mode:'SCORE',list:[row(),row({rank:'1',rowKey:'row-b',scoreCenti:'-101',elapsedMs:'9223372036854775807'})],me:row({rank:'77',rowKey:'mine',mine:true})};
 const view=project(data);assert.deepEqual(view.list.map(r=>r.rowKey),['row-a','row-b']);assert.equal(view.list[0].rank,'1');
 assert.equal(view.list[0].scoreText,'90071992547409.93 分');assert.equal(view.list[0].elapsedText,'用时待确认');
 assert.equal(view.list[1].elapsedText,'9223372036854775 秒');assert.equal(view.me.rank,'77');
});
test('unsafe numeric totals and duplicate identities are rejected; route ending is distinct from score',()=>{
 assert.equal(project({mode:'SCORE',list:[row({scoreCenti:9007199254740992})]}),null);
 assert.equal(project({mode:'SCORE',list:[row(),row()]}),null);
 assert.equal(project({mode:'ROUTE',list:[row({reachedEnding:false})]}).list[0].scoreText,'尚未到达结局');
});
test('real Page uses owned session full read model and ignores a late old-run response',async()=>{
 const p=page();p.openBoard();assert.equal(requests[0].url,'/api/orient/scoring/session/51/leaderboard');assert.equal(requests[0].method,'GET');
 requests[0].success({code:200,data:{sessionId:'51',mode:'SCORE',list:[row()]}});await flush();
 assert.equal(p.data.board.list[0].scoreText,'90071992547409.93 分');
 p.openBoard();p._routeState.sessionId='52';requests[1].success({code:200,data:{sessionId:'51',mode:'SCORE',list:[row()]}});await flush();
 assert.equal(p.data.board.loading,true);assert.equal(p.data.board.list.length,0);
});
test('only explicit feature-off falls back; missing or foreign model cannot masquerade as legacy',async()=>{
 const p=page();p.openBoard();requests[0].success({code:404,errorCode:'ORIENT_SCORING_DISABLED'});await flush();
 assert.equal(requests[1].url,'/api/play/leaderboard');requests[1].success({code:200,data:{list:[{memberId:7,score:2,rank:1}]}});await flush();
 assert.equal(p.data.board.list[0].scoreText,'2 关');
 for(const response of [{code:500},{code:200,data:{list:[]}},{code:200,data:{sessionId:'88',mode:'SCORE',list:[row()]}}]){
  requests=[];p.openBoard();requests[0].success(response);await flush();assert.equal(requests.length,1);assert.ok(p.data.board.error);
 }
});
