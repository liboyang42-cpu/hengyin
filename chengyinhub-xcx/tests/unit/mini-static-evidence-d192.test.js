'use strict';
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),path=require('node:path');
const vm=require('node:vm');
const {createRequire}=require('node:module');
const root=path.resolve(__dirname,'../..');
const baseline=require('../lint/ui-integrity-baseline');
const {scanU4,scanU5}=require('../lint/ui-integrity');
const {stableSiteKey}=require('../lint/stable-site-key');
const {analyzeWxml}=require('../../scripts/state-visibility-gate');
const preview=require('../../utils/topic-route-preview');

function routeMapHarness() {
  const file=path.join(root,'pages/square/components/route-map/index.js');
  const requests=[],patches=[];
  const session={getRevision:()=>1};
  const app={getSession:()=>session,sendRequest:request=>requests.push(request)};
  let definition;
  vm.runInNewContext(fs.readFileSync(file,'utf8'),{
    require:createRequire(file),getApp:()=>app,Component:value=>{definition=value;},
  });
  const defaults=Object.fromEntries(Object.entries(definition.properties).map(([key,value])=>[key,value.value]));
  const host={...definition.methods,data:{...defaults,...definition.data},
    setData(values,done) { patches.push(JSON.parse(JSON.stringify(values))); Object.assign(this.data,values); if(done) done(); },
    triggerEvent() {},
  };
  return {host,requests,patches};
}

test('五处路线动态投影仅提交真实固定展示字段；每项登记缺失仍红',()=>{
  const fields=['mapLatitude','mapLongitude','routeIncludePoints','routeIsRoad','routeMarkers','routePolyline','routeReady','routeSourceText'];
  for(const input of [[],[{nodes:[{id:1,name:'point',latitude:'31',longitude:'121'}]}]])
    assert.deepEqual(Object.keys(preview.build(input)).sort(),fields);
  const h=routeMapHarness();
  h.host.loadRoute(7);
  const projectionFields=[...fields,'mapInstances'].sort();
  assert.deepEqual(Object.keys(h.patches[0]).sort(),projectionFields);
  assert.deepEqual(h.patches[0].mapInstances,[]);
  h.requests[0].success({code:200,data:{id:7,chaptersList:[{
    routeReady:false,mapInstances:'untrusted',enablePoi:false,serverOnly:true,
    nodes:[{id:1,name:'point',latitude:31,longitude:121,serverOnly:true}],
  }]}});
  const view=h.patches.at(-1);
  assert.deepEqual(Object.keys(view).sort(),projectionFields,'server/chapter keys must not become setData keys');
  assert.equal(view.routeReady,true);
  assert.deepEqual(view.mapInstances,[{epoch:h.host._routeReadEpoch}]);
  h.host.buildRouteMap([]);
  assert.deepEqual(Object.keys(h.patches.at(-1)).sort(),projectionFields);
  assert.deepEqual(h.patches.at(-1).mapInstances,[]);
  const files=['pages/square/components/route-map/index.js','pages/topic/index/index.js'].map(name=>path.join(root,name));
  const approved=new Map(Object.entries(baseline.u4Dynamic));
  const scanned=scanU4(files,new Map(Object.entries(baseline.u4Debt)),approved);
  assert.equal(scanned.violations.length,0);
  const targets=scanned.dynamicReports.map(row=>row.key);
  assert.equal(new Set(targets).size,5,'four route-map projections and the topic projection must all be audited');
  for(const target of targets) {
    const missing=new Map(approved);
    missing.delete(target);
    assert.ok(scanU4(files,new Map(Object.entries(baseline.u4Debt)),missing).violations.some(row=>row.key===target));
  }
  for(const name of ['pages/square/components/route-map/index.wxml','pages/topic/index/index.wxml']) {
    const source=fs.readFileSync(path.join(root,name),'utf8');
    for(const field of fields.filter(field=>field!=='routeIsRoad' && !(name==='pages/topic/index/index.wxml' && field==='routeSourceText'))) assert.ok(source.includes(field)||fs.readFileSync(path.join(root,name.replace('.wxml','.js')),'utf8').includes('this.data.'+field),field);
  }
});

test('健康封面shade是已有CSS装饰；真实空错误态与裸错误文案仍红',()=>{
  const wxml=fs.readFileSync(path.join(root,'pages/roam/index.wxml'),'utf8');
  const css=fs.readFileSync(path.join(root,'pages/roam/index.wxss'),'utf8');
  const decoration=/<view wx:if="\{\{item.cover && !entryCoverErrors\[item.cover\]\}\}" class="entry-card__shade entry-card__shape" aria-hidden="true"><\/view>\s*<view class="entry-card__copy">([\s\S]*?)<\/view>/g;
  function assertCovers(source) {
    const matches=[...source.matchAll(decoration)];
    assert.equal(matches.length,3,'only the three healthy-cover decorations are annotated');
    assert.deepEqual(matches.map(match=>(match[1].match(/class="entry-card__title"[^>]*>\{\{(item\.(?:name|title))\}\}/)||[])[1]),
      ['item.name','item.title','item.title'],'same-card title must remain visible');
  }
  assertCovers(wxml);
  assert.match(css,/\.entry-card__shade\{[^}]*position:absolute;[^}]*inset:0;[^}]*background:linear-gradient\(/);
  assert.deepEqual(analyzeWxml(wxml),[]);
  const unclassified=wxml.replaceAll(' entry-card__shape','');
  assert.notEqual(unclassified,wxml);
  assert.equal(analyzeWxml(unclassified).filter(row=>row.rule==='BLANK_STATE_BRANCH').length,3);
  assert.throws(()=>assertCovers(wxml.replaceAll('class="entry-card__title"','class="removed-title"')),/same-card title/);
  for(const blank of ['<view wx:if="{{newLoadError}}"></view>',
    '<view wx:if="{{newLoadError}}" aria-hidden="true"></view>',
    '<view wx:if="{{newLoadError}}" class="shapeshifter-panel"></view>'])
    assert.ok(analyzeWxml(wxml+blank).some(row=>row.rule==='BLANK_STATE_BRANCH'));
  assert.ok(analyzeWxml(wxml+'<text wx:if="{{newLoadError}}">{{newLoadError}}</text>').some(row=>row.rule==='RAW_ERROR_TEXT'));
});

test('原席位回查保留完整回调与禁自动toast，退役flag不得借旧理由重新静默',(t)=>{
  const name='chengyinhub-xcx/utils/registration-seat.js';
  const file=path.join(root,'utils/registration-seat.js');
  const approved=new Map(Object.entries(baseline.u5Silent));
  assert.equal(scanU5([file],approved).violations.length,0);
  const source=fs.readFileSync(file,'utf8');
  const line=source.split('\n').findIndex(row=>row.includes("hideLoading: true, autoErrorToast: false"))+1;
  assert.equal(approved.has(stableSiteKey(name,source,line)),false);
  assert.doesNotMatch(source,/silentError:\s*true/);
  assert.match(source,/autoErrorToast:\s*false/);
  const injected=source.replace('hideLoading: true, autoErrorToast: false','hideLoading: true, silentError: true, autoErrorToast: false');
  assert.notEqual(injected,source);
  const originalRead=fs.readFileSync;
  t.mock.method(fs,'readFileSync',function(target,...args){return target===file?injected:originalRead.call(this,target,...args);});
  assert.ok(scanU5([file],approved).violations.some(row=>row.key===stableSiteKey(name,injected,line)));
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

test('首页两处动态投影等量重锚；漏任一登记与新调用点仍红',()=>{
  const file=path.join(root,'pages/index/index.js');
  const approved=new Map(Object.entries(baseline.u4Dynamic));
  const prefix='chengyinhub-xcx/pages/index/index.js#site:';
  const targets=['6ac92b01d7055b71ff2cefb81dfaa2c937ece2d0429a4c1171d7f47818fc237d',
    '5f31ebc1ddf09fc0d3ef78a909d93491460bb6349c58875c85b093b75759c162'].map(key=>prefix+key+':1');
  for(const old of ['24e1efab5e0ca1a6a4829cea880778b64709e9b7fbc9324beadfa9ed521d9a97',
    'b8d2fe63c3fd7b1cd1e7699ae12ed543a6b8359dbda05165981ce8eda855fcef']) assert.equal(approved.has(prefix+old+':1'),false);
  assert.equal(approved.size,59);
  assert.equal(baseline.ratchet.u4Dynamic,59);
  const digest=require('node:crypto').createHash('sha256').update([...approved.keys()].sort().join('\n')).digest('hex');
  assert.equal(baseline.ratchet.keyDigest.u4Dynamic,digest);
  assert.deepEqual(scanU4([file],new Map(Object.entries(baseline.u4Debt)),approved).violations,[]);
  for(const key of targets) {
    assert.ok(approved.has(key));
    const missing=new Map(approved); missing.delete(key);
    assert.ok(scanU4([file],new Map(Object.entries(baseline.u4Debt)),missing).violations.some(row=>row.key===key));
  }
  const source=fs.readFileSync(file,'utf8');
  const injected=source+'\nPage({load(){this.setData(serverPayload);}});';
  const originalRead=fs.readFileSync;
  const mock=require('node:test').mock.method(fs,'readFileSync',function(target,...args){return target===file?injected:originalRead.call(this,target,...args);});
  try { assert.ok(scanU4([file],new Map(Object.entries(baseline.u4Debt)),approved).violations.some(row=>!approved.has(row.key))); }
  finally { mock.mock.restore(); }
});

test('首页真实caller只投影三个固定列表或bottomTopicList下标，服务端键与迟回包不得写顶层',()=>{
  const file=path.join(root,'pages/index/index.js'),source=fs.readFileSync(file,'utf8');
  function mount(text=source) {
    let definition,member='TEST-member'; const requests=[],patches=[];
    const app={globalData:{features:{},authorization:'TEST-source-only'},getUserID:()=>member,getUserType:()=>1,
      getPageSize:()=>10,getImgUrl:value=>value||'',sendRequest:request=>{requests.push(request);return {abort(){}};}};
    const wx={getStorageSync:()=>'',getAccountInfoSync:()=>({miniProgram:{envVersion:'release'}})};
    vm.runInNewContext(text,{require:createRequire(file),getApp:()=>app,Page:value=>{definition=value;},wx,console});
    const page={...definition,data:JSON.parse(JSON.stringify(definition.data)),
      setData(patch,done){
        patches.push(JSON.parse(JSON.stringify(patch)));
        for(const [key,value] of Object.entries(patch)) {
          const parts=key.replace(/\[(\d+)\]/g,'.$1').split('.');let target=this.data;
          for(const part of parts.slice(0,-1)) target=target[part]||(target[part]={});
          target[parts.at(-1)]=value;
        }
        if(done) done();
      }};
    page.data.reducedMotion=true;
    page.ensureCountdownTimerRunning=()=>{};
    page._nearbyThemeOwner='TEST-member|TEST-source-only';
    return {page,requests,patches,changeMember(){member='TEST-other';}};
  }
  const response={code:200,data:{rows:[{id:7,name:'TEST',serverOnly:true}],items:[{id:7,name:'TEST',serverOnly:true}],serverInjected:'must not become a key'}};
  for(const name of ['recommendedTopicList','nearbyTopicList','upcomingActivityList']) {
    const h=mount();h.page.getListData(name,'/api/topic/list',{});h.requests[0].success(response);
    const projection=h.patches.find(patch=>Object.hasOwn(patch,name));
    assert.deepEqual(Object.keys(projection).sort(),[name,'listErr.'+name].sort());
    assert.equal(projection[name][0].id,7);
    assert.equal(h.page.data.serverInjected,undefined);
    assert.equal(h.page.data.serverOnly,undefined);
  }
  const stale=mount();stale.page.getListData('nearbyTopicList','/api/roam/hangout/nearby',{});
  stale.page.getListData('nearbyTopicList','/api/roam/hangout/nearby',{});
  const before=stale.patches.length;stale.requests[0].success(response);assert.equal(stale.patches.length,before);
  stale.changeMember();stale.requests[1].success(response);assert.equal(stale.patches.length,before);
  const bottom=mount();bottom.page.data.bottomTopicList=[{id:1}];bottom.page.loadBottomTopicList(2);
  bottom.requests[0].success({code:200,data:{rows:[{id:2},{id:3}],total:30,serverInjected:true}});
  const append=bottom.patches.find(patch=>Object.hasOwn(patch,'bottomTopicList[1]'));
  assert.deepEqual(Object.keys(append).sort(),['bottomTopicPageNo','hasMoreBottomTopic','bottomTopicList[1]','bottomTopicList[2]'].sort());
  assert.equal(bottom.page.data.serverInjected,undefined);
  const wxml=fs.readFileSync(path.join(root,'pages/index/index.wxml'),'utf8');
  for(const field of ['nearbyTopicList','upcomingActivityList','bottomTopicList','hasMoreBottomTopic','listErr']) assert.ok(wxml.includes(field),field);
  assert.match(source,/this\.data\.recommendedTopicList/);assert.ok(wxml.includes('recoHero'));
  assert.ok(/that\.data\.bottomTopicPageNo \+ 1/.test(source));
  const bad=mount(source.replace('dataObj[listName] = list;','dataObj[listName] = list; dataObj.serverInjected = list;'));
  bad.page.getListData('recommendedTopicList','/api/topic/list',{});bad.requests[0].success(response);
  const projection=bad.patches.find(patch=>Object.hasOwn(patch,'recommendedTopicList'));
  assert.throws(()=>assert.deepEqual(Object.keys(projection).sort(),['recommendedTopicList','listErr.recommendedTopicList'].sort()));
});
