'use strict';
// NOT_RUN: source-only authored regression.
const { test } = require('node:test'); const assert = require('node:assert/strict'); const path = require('node:path');
function load() {
 let definition; const requests = [], applied = []; global.getApp = () => ({globalData:{}, getUserID:()=> '11',sendRequest:r=>requests.push(r)}); global.Page = p=>{definition=p;}; global.wx={};
 const file=path.resolve(__dirname,'../../pages/play/index.js'); delete require.cache[file]; require(file);
 const page=Object.assign({},definition,{data:{activityId:9,topicId:8},_routeState:{sessionId:'s9'},_applyRouteState:r=>applied.push(r)});
 return {page,requests,applied};
}
test('direct thought claim cannot send after its cipher returns in another session',async()=>{
 const h=load(); let done; h.page._weRunCipher=()=>new Promise(resolve=>{done=resolve;}); h.page._claimThoughts(['fixture']);
 await new Promise(resolve=>setImmediate(resolve)); h.page.data.activityId=10; h.page._routeState={sessionId:'s10'}; done({code:'fixture'});
 await new Promise(resolve=>setImmediate(resolve)); assert.deepEqual(h.requests,[]);
});
test('old thought receipt cannot project state into new session',async()=>{
 const h=load(); h.page._weRunCipher=()=>Promise.resolve(null); h.page._claimThoughts(['fixture']); await new Promise(resolve=>setImmediate(resolve));
 h.page.data.activityId=10; h.page._routeState={sessionId:'s10'}; h.requests[0].success({code:200,data:{sessionId:'s9'}});
 await new Promise(resolve=>setImmediate(resolve)); assert.deepEqual(h.applied,[]);
});
test('claim de-duplication is scoped to the original session',()=>{
 const h=load(); const claims=[]; h.page._syncThoughts=v=>claims.push(v); h.page._claimThoughts(['same']); h.page._claimThoughts(['same']);
 h.page.data.activityId=10; h.page._routeState={sessionId:'s10'}; h.page._claimThoughts(['same']); assert.deepEqual(claims,[['same'],['same']]);
});
