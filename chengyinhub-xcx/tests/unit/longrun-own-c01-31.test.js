'use strict';
// NOT_RUN; authored source-only regression.
const {test}=require('node:test');const assert=require('node:assert/strict');const path=require('node:path');
function load(){let definition;global.getApp=()=>({globalData:{}});global.Page=p=>{definition=p;};global.wx={};const file=path.resolve(__dirname,'../../pages/play/index.js');delete require.cache[file];require(file);return Object.assign({},definition,{_routeState:{sessionId:'s9',version:12,routeMode:'LINEAR'}});}
test('shared projection admission rejects stale direct-entry state',()=>{const p=load();assert.equal(p._acceptRouteStateProjection({sessionId:'s9',version:10}),false);assert.equal(p._acceptRouteStateProjection({sessionId:'s9',version:12}),true);assert.equal(p._acceptRouteStateProjection({sessionId:'s10',version:1}),true);});
test('explicit-zero provenance survives same object while unknown legacy zero is not invented',()=>{const p=load();p._routeState={sessionId:'s9',version:0};assert.equal(p._acceptRouteStateProjection({sessionId:'s9'}),true);p._versionedRouteState=p._routeState;assert.equal(p._acceptRouteStateProjection({sessionId:'s9'}),false);});
