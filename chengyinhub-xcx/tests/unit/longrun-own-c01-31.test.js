'use strict';
// NOT_RUN; authored source-only regression.
// W8 起路线 sessionId 按服务端精确正整数 id 规范化(play-route-state normalizeRouteState → orient-score-view id),
// 非数字串一律视为未知局;夹具改用数字局号以继续断言同局版本地板。
const {test}=require('node:test');const assert=require('node:assert/strict');const path=require('node:path');
function load(){let definition;global.getApp=()=>({globalData:{}});global.Page=p=>{definition=p;};global.wx={};const file=path.resolve(__dirname,'../../pages/play/index.js');delete require.cache[file];require(file);return Object.assign({},definition,{_routeState:{sessionId:'9',version:12,routeMode:'LINEAR'}});}
test('shared projection admission rejects stale direct-entry state',()=>{const p=load();assert.equal(p._acceptRouteStateProjection({sessionId:'9',version:10}),false);assert.equal(p._acceptRouteStateProjection({sessionId:'9',version:12}),true);assert.equal(p._acceptRouteStateProjection({sessionId:'10',version:1}),true);});
test('explicit-zero provenance survives same object while unknown legacy zero is not invented',()=>{const p=load();p._routeState={sessionId:'9',version:0};assert.equal(p._acceptRouteStateProjection({sessionId:'9'}),true);p._versionedRouteState=p._routeState;assert.equal(p._acceptRouteStateProjection({sessionId:'9'}),false);});
