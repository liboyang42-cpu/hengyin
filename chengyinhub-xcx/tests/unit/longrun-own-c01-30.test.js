'use strict';
// NOT_RUN: source-only authored regression.
// W8 起路线 sessionId 按服务端精确正整数 id 规范化(play-route-state normalizeRouteState → orient-score-view id),
// 非数字串一律视为未知局;夹具改用数字局号以继续断言同局版本地板。
const {test}=require('node:test'); const assert=require('node:assert/strict');const path=require('node:path');
function load(){let definition;global.getApp=()=>({globalData:{}});global.Page=p=>{definition=p;};global.wx={};const file=path.resolve(__dirname,'../../pages/play/index.js');delete require.cache[file];require(file);const writes=[];const page=Object.assign({},definition,{data:{nodes:[]},_routeState:{sessionId:'9',version:12,routeMode:'LINEAR'},_mergeRouteCatalogCurrent:()=>[],_routeNoticeForState:()=>'',setData(p,cb){writes.push(p);if(cb)cb();},rebuild(){},refreshThemeBackpack(){}});return{page,writes};}
test('same-session old route projection cannot regress version or trigger rebuild',()=>{const h=load();let accepted;const original=h.page._routeState;assert.equal(h.page._applyRouteState({sessionId:'9',version:10},v=>{accepted=v;}),false);assert.equal(h.page._routeState,original);assert.deepEqual(h.writes,[]);assert.equal(accepted,false);});
for(const version of [undefined,null,'',false])test('unknown legacy version cannot replace known same-session projection: '+String(version),()=>{const h=load();assert.equal(h.page._applyRouteState({sessionId:'9',version}),false);assert.equal(h.page._routeState.version,12);});
test('new session may start with its actual lower version',()=>{const h=load();assert.equal(h.page._applyRouteState({sessionId:'10',version:1}),true);assert.equal(h.page._routeState.version,1);});
test('legacy unversioned projection remains compatible before any explicit floor',()=>{const h=load();h.page._routeState={sessionId:'66',version:0,routeMode:'LINEAR'};assert.equal(h.page._applyRouteState({sessionId:'66'}),true);});
test('explicit zero establishes a floor without treating unknown version as zero',()=>{const h=load();h.page._routeState={};h.page._applyRouteState({sessionId:'77',version:0});assert.equal(h.page._applyRouteState({sessionId:'77',version:null}),false);assert.equal(h.page._routeState.version,0);});
