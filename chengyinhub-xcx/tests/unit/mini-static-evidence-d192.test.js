'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const root=path.resolve(__dirname,'../..');
const baseline=require('../lint/ui-integrity-baseline');
const {scanU4,scanU5}=require('../lint/ui-integrity');
const {analyzeWxml}=require('../../scripts/state-visibility-gate');
const preview=require('../../utils/topic-route-preview');

test('五处路线动态投影仅提交真实固定展示字段；每项登记缺失仍红',()=>{
  const fields=['mapLatitude','mapLongitude','routeIncludePoints','routeIsRoad','routeMarkers','routePolyline','routeReady','routeSourceText'];
  for(const input of [[],[{nodes:[{id:1,name:'point',latitude:'31',longitude:'121'}]}]])
    assert.deepEqual(Object.keys(preview.build(input)).sort(),fields);
  const files=['pages/square/components/route-map/index.js','pages/topic/index/index.js'].map(name=>path.join(root,name));
  const approved=new Map(Object.entries(baseline.u4Dynamic));
  assert.equal(scanU4(files,new Map(Object.entries(baseline.u4Debt)),approved).violations.length,0);
  const target='chengyinhub-xcx/pages/square/components/route-map/index.js:45';
  approved.delete(target);
  assert.ok(scanU4(files,new Map(Object.entries(baseline.u4Debt)),approved).violations.some(row=>row.key===target));
  for(const name of ['pages/square/components/route-map/index.wxml','pages/topic/index/index.wxml']) {
    const source=fs.readFileSync(path.join(root,name),'utf8');
    for(const field of fields.filter(field=>field!=='routeIsRoad')) assert.ok(source.includes(field)||fs.readFileSync(path.join(root,name.replace('.wxml','.js')),'utf8').includes('this.data.'+field),field);
  }
});

test('原席位回查保留完整回调与禁自动toast，退役flag不得借旧理由重新静默',(t)=>{
  const file=path.join(root,'utils/registration-seat.js'),key='chengyinhub-xcx/utils/registration-seat.js:62';
  const approved=new Map(Object.entries(baseline.u5Silent));
  assert.equal(scanU5([file],approved).violations.length,0);
  const source=fs.readFileSync(file,'utf8');
  assert.equal(approved.has(key),false);
  assert.doesNotMatch(source,/silentError:\s*true/);
  assert.match(source,/autoErrorToast:\s*false/);
  const injected=source.replace('hideLoading: true, autoErrorToast: false','hideLoading: true, silentError: true, autoErrorToast: false');
  assert.notEqual(injected,source);
  const originalRead=fs.readFileSync;
  t.mock.method(fs,'readFileSync',function(target,...args){return target===file?injected:originalRead.call(this,target,...args);});
  assert.ok(scanU5([file],approved).violations.some(row=>row.key===key));
  assert.match(source,/if \(!isCurrent\(\)\) return null/);
  assert.match(source,/url: '\/api\/verify\/seat\/receipt'/);
  assert.match(source,/fail: \(\) => done\(null\)/);
  assert.doesNotMatch(source,/url: '\/api\/registration\/scan/);
});

test('节点声音错误使用既有错误组件，恢复动作仅查询原请求；换回裸文本仍红',()=>{
  const wxml=fs.readFileSync(path.join(root,'pages/topic/components/cy/node-npc-form/index.wxml'),'utf8');
  const component=/<cy-inline-error[^>]*voiceError[^>]*bind:action="refreshVoice"\s*\/>/;
  assert.match(wxml,component);
  assert.ok(!analyzeWxml(wxml).some(row=>row.rule==='RAW_ERROR_TEXT'));
  const broken=wxml.replace(component,'<text wx:if="{{voiceError}}">{{voiceError}}</text>');
  assert.ok(analyzeWxml(broken).some(row=>row.rule==='RAW_ERROR_TEXT'));
  const json=JSON.parse(fs.readFileSync(path.join(root,'pages/topic/components/cy/node-npc-form/index.json'),'utf8'));
  assert.equal(json.usingComponents['cy-inline-error'],'/components/cy/inline-error/index');
});
