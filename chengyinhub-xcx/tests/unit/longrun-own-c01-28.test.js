'use strict';
// NOT_RUN: authored only.
const {test}=require('node:test'); const assert=require('node:assert/strict'); const path=require('node:path');
function load(){let definition;const requests=[],modals=[];global.getApp=()=>({globalData:{},getUserID:()=> '11',sendRequest:r=>requests.push(r)});global.Page=p=>{definition=p;};global.wx={showToast(){},showModal:o=>modals.push(o)};
const file=path.resolve(__dirname,'../../pages/play/index.js');delete require.cache[file];require(file);
const page=Object.assign({},definition,{data:{activityId:9,topicId:8,mode:1,shownHints:[],game:{nodeId:31,hintLocked:true}},setData(p){Object.assign(this.data,p);}});return{page,requests,modals};}
test('stale hint confirmation sends no unlock for replacement scope',()=>{const h=load();h.page.unlockHint();h.page.data.activityId=10;h.modals[0].success({confirm:true});assert.deepEqual(h.requests,[]);});
test('old unlock response cannot alter game or clear newer hint intent',()=>{const h=load();h.page.unlockHint();h.modals[0].success({confirm:true});h.page.data.game={nodeId:32,hintLocked:true};h.page.data.hintUnlocking=false;h.page.unlockHint();const newer=h.page._legacyHintIntent;h.requests[0].success({code:'200',data:{hint1:'old'}});h.requests[0].complete();assert.equal(h.page._legacyHintIntent,newer);assert.equal(h.page.data.hintUnlocking,true);assert.equal(h.page.data['game.hint1'],undefined);});
