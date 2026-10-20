'use strict';
// NOT_RUN: authored source-only regressions.
const {test}=require('node:test');const assert=require('node:assert/strict');const path=require('node:path');
function load(){let definition,done;const opened=[];global.getApp=()=>({globalData:{},getUserID:()=> '11'});global.Page=p=>{definition=p;};global.wx={};const file=path.resolve(__dirname,'../../pages/play/index.js');delete require.cache[file];require(file);const node={nodeId:31};const page=Object.assign({},definition,{data:{activityId:9,topicId:8,chapter:{id:21},nextNode:{nodeId:31},nodes:[node]},_routeState:{sessionId:'s9'},_endThemeChapter:()=>new Promise(r=>{done=r;}),openSheet:n=>opened.push(n)});return{page,node,opened,done:()=>done(true)};}
test('old chapter-end continuation cannot open node after replacing chapter',async()=>{const h=load();const work=h.page.continueChapterStory();h.page.data.chapter={id:22};h.done();await work;assert.deepEqual(h.opened,[]);});
test('old chapter-end continuation cannot open same node in another scope',async()=>{const h=load();const work=h.page.continueChapterStory();h.page.data.activityId=10;h.done();await work;assert.deepEqual(h.opened,[]);});
test('changed next node suppresses obsolete original sheet',async()=>{const h=load();const work=h.page.continueChapterStory();h.page.data.nextNode={nodeId:32};h.done();await work;assert.deepEqual(h.opened,[]);});
test('original unchanged chapter opens its original next sheet',async()=>{const h=load();const work=h.page.continueChapterStory();h.done();await work;assert.deepEqual(h.opened,[h.node]);});
