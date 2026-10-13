const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const records = require('../../utils/theme-records.js');
const fixture = require('./fixtures/theme-records.json');
function page(responder, play = true, clearResponder = () => ({code:200})) {
  const requests = [], navigations = [];
  global.getApp = () => ({getUserID:()=>7});
  global.wx = { removeStorageSync() {}, redirectTo(o) { navigations.push(o.url); }, navigateTo(o) { navigations.push(o.url); } };
  const instance = { data: { ...structuredClone(records.data), topicId:9, id:9, topicName:'末日寒冬', themeBag:structuredClone(fixture.completed) },
    setData(patch) { Object.assign(this.data, patch); } };
  Object.assign(instance, records.methods(async (...args) => { requests.push(args); return args[0]==='/api/play/run-session/clear'?clearResponder(...args):responder(...args); }, play));
  return { instance, requests, navigations };
}
test('ordinary record reads stay quiet but authorization and unknown replay failures stay visible', async () => {
  const ordinary = page(() => ({ code: 500 }));
  assert.equal(await ordinary.instance.openThemeHistory(), false);
  assert.equal(ordinary.instance.data.themeRecordsReadFailure, true);
  const denied = page(() => ({ code: 403 }));
  assert.equal(await denied.instance.openThemeHistory(), false);
  assert.equal(denied.instance.data.themeRecordsReadFailure, false);
  const replay = page(() => ({ code: 500 }));
  replay.instance._themeReplayAttempt = structuredClone(fixture.completed);
  assert.equal(await replay.instance.checkThemeReplay(), false);
  assert.equal(replay.instance.data.themeRecordsReadFailure, false);
  assert.ok(replay.instance.data.themeRecordsError);
});
for (const allowed of [false, undefined, 'true']) test('重玩只接受明确 true，拒绝 '+String(allowed), async () => {
  const {instance, requests} = page(() => ({code:200,data:{...fixture.completed,replayAllowed:allowed}}));
  instance.data.themeBag.replayAllowed=allowed;
  assert.equal(await instance.replayThemeSession(), false);
  assert.equal(requests.filter(r=>r[1]==='POST').length,0);
});
test('重玩重新核对许可、JSON提交后读取新局，用新页面而非旧暂停计时', async () => {
  const {instance,requests,navigations}=page((url,method,data)=>({code:200,data:method==='POST'||data.sessionId===73||data.sessionId===undefined?fixture.replayed:fixture.completed}));
  assert.equal(await instance.replayThemeSession(),true);
  assert.deepEqual(requests.map(r=>[r[0],r[1],r[2].sessionId]),[
    ['/api/play/theme/state','GET',72],['/api/play/theme/replay','POST',72],['/api/play/theme/state','GET',73],['/api/play/theme/state','GET',undefined],['/api/play/run-session/clear','POST',undefined]]);
  assert.deepEqual(requests[1][2],{sessionId:72});
  assert.equal(requests[1][3]['content-type'],'application/json');
  assert.deepEqual(navigations,['/pages/play/index?topicId=9']);
  assert.equal(instance.data.themeBag.sessionId,72);
});
for(const unknown of [true,false]) test('重玩'+(unknown?'未知':'拒绝')+'只读回，不盲重发',async()=>{
  const {instance,requests,navigations}=page((url,method)=>method==='POST'?{code:unknown?'fail':409,netFail:unknown}:{code:200,data:fixture.completed});
  assert.equal(await instance.replayThemeSession(),false);
  assert.equal(await instance.replayThemeSession(),false);
  assert.equal(requests.filter(r=>r[0]==='/api/play/theme/replay').length,1);
  assert.equal(requests.at(-1)[0],'/api/play/theme/state');
  assert.equal(requests.at(-1)[2].sessionId,undefined);
  assert.equal(navigations.length,0);
});
test('未知重玩已落地时读回最新新局再进入，不重发',async()=>{
  let reads=0;
  const {instance,requests,navigations}=page((url,method)=>method==='POST'?{code:'fail',netFail:true}:{code:200,data:++reads===1?fixture.completed:fixture.replayed});
  assert.equal(await instance.replayThemeSession(),true);
  assert.equal(requests.filter(r=>r[0]==='/api/play/theme/replay').length,1);
  assert.equal(navigations.length,1);
});
test('历史依服务端顺序、最后一条游标翻页，并保留每局结果',async()=>{
  const first=Array.from({length:20},(_,i)=>({...fixture.completed,sessionId:72-i}));
  const {instance,requests}=page((_url,_method,data)=>({code:200,data:data.beforeSessionId?[{...fixture.completed,sessionId:51}]:first}));
  await instance.openThemeHistory(); await instance.loadMoreThemeHistory();
  assert.equal(requests[0][0],'/api/play/theme/history');
  assert.deepEqual(requests[0][2],{topicId:9,limit:20});
  assert.deepEqual(requests[1][2],{topicId:9,limit:20,beforeSessionId:53});
  assert.deepEqual(instance.data.themeHistoryRows.map(r=>r.sessionId),first.map(r=>r.sessionId).concat(51));
  assert.equal(instance.data.themeHistoryRows[0].resultTitle,'回到暖屋');
  assert.equal(instance.data.themeHistoryMore,false);
});
test('结果卡只请求选中的已结束局、用白名单回包及主题数值名',async()=>{
  const {instance,requests}=page(url=>({code:200,data:url.endsWith('/card')?fixture.card:fixture.completed}));
  instance.data.themeHistoryRows=[records.historyRow(fixture.completed)];
  await instance.openThemeResultCard({currentTarget:{dataset:{id:72}}});
  assert.deepEqual(requests[0].slice(0,3),['/api/play/theme/card','GET',{sessionId:72}]);
  assert.equal(instance.data.themeResultCard.title,'回到暖屋');
  assert.deepEqual(instance.data.themeResultCard.valueRows[0],{name:'体温',value:3});
  assert.equal(instance.data.themeResultCard.sessionId,72);
});
test('仍进行中的历史行不能请求结果卡；历史读取失败保留已读页',async()=>{
  const {instance,requests}=page(()=>({code:403}));
  instance.data.themeHistoryRows=[records.historyRow({...fixture.replayed,result:null})];
  assert.equal(await instance.openThemeResultCard({currentTarget:{dataset:{id:73}}}),false);
  assert.equal(requests.length,0);
  instance.data.themeHistoryMore=true;
  await instance.loadMoreThemeHistory();
  assert.equal(instance.data.themeHistoryRows.length,1);
  assert.ok(instance.data.themeRecordsError);
});
test('玩家两入口使用同一模块和独立列表/卡片，所有交互有按压反馈',()=>{
  for(const file of ['pages/play/index.js','pages/topic/index/index.js']) assert.match(fs.readFileSync(file,'utf8'),/themeRecords\.methods/);
  const markup=fs.readFileSync('components/cy/theme-records/index.wxml','utf8');
  for(const tag of markup.match(/<[^>]+bind(?:tap|:close)=[^>]+>/g)||[]) if(tag.includes('bindtap')) assert.match(tag,/hover-class="cy-pressed"/);
  assert.doesNotMatch(markup,/服务器|expectedVersion|runtimeScope/);
});
test('许可失效时只读一次，不提交重玩',async()=>{
  const {instance,requests}=page(()=>({code:200,data:{...fixture.completed,replayAllowed:false}}));
  assert.equal(await instance.replayThemeSession(),false);assert.equal(requests.length,1);
  assert.equal(instance.data.themeRecordsState.replayAllowed,false);
});
test('重复点击仅提交一次，新局确认后才清旧计时快照',async()=>{
  let release;const hold=new Promise(resolve=>{release=resolve;});
  const {instance,requests}=page(async (_url,method,data)=>{if(method==='POST')await hold;return{code:200,data:method==='POST'||data.sessionId===73||data.sessionId===undefined?fixture.replayed:fixture.completed};});
  let cleared=0;instance._pauseSessionClock=()=>{cleared++;};
  const first=instance.replayThemeSession();await new Promise(setImmediate);
  assert.equal(await instance.replayThemeSession(),false);assert.equal(cleared,0);
  release();assert.equal(await first,true);assert.equal(cleared,1);assert.equal(requests.filter(r=>r[0]==='/api/play/theme/replay').length,1);
});
test('页面卸载后的许可回读不发送重玩；关闭历史后不回填旧列表',async()=>{
  let release;const hold=new Promise(resolve=>{release=resolve;});
  const {instance,requests,navigations}=page(async()=>{await hold;return{code:200,data:fixture.completed};});
  const first=instance.replayThemeSession();instance._themeRecordsDisposed=true;release();
  assert.equal(await first,false);assert.equal(requests.length,1);assert.equal(navigations.length,0);
  let releaseHistory;const hist=page(()=>new Promise(resolve=>{releaseHistory=resolve;})).instance;
  const read=hist.openThemeHistory();hist.closeThemeHistory();releaseHistory({code:200,data:[fixture.completed]});
  assert.equal(await read,false);assert.equal(hist.data.themeHistoryRows.length,0);
});
test('结果卡请求失败可单独重读，重读不触发写入',async()=>{
  let cardReads=0;
  const {instance,requests}=page(url=>url.endsWith('/card')?++cardReads===1?{code:403}:{code:200,data:fixture.card}:{code:200,data:fixture.completed});
  assert.equal(await instance.openThemeResultCard(),false);assert.equal(await instance.retryThemeResultCard(),true);
  assert.equal(requests.filter(r=>r[1]==='POST').length,0);
});

for (const entry of [true,false]) test((entry?'游玩页':'主题入口')+'计时清理未知后只读核对，不跳转、不重复清理写入',async()=>{
  const {instance,requests,navigations}=page((url,method,data)=>url==='/api/play/run-session'?{code:200,data:{runState:'PAUSED',elapsedSeconds:99,savedAt:1}}:{code:200,data:method==='POST'||!data.sessionId||data.sessionId===73?fixture.replayed:fixture.completed},entry,()=>({code:'fail',netFail:true}));
  assert.equal(await instance.replayThemeSession(),false);
  assert.equal(await instance.checkThemeReplay(),false);
  assert.equal(requests.filter(r=>r[0]==='/api/play/theme/replay').length,1);
  assert.equal(requests.filter(r=>r[0]==='/api/play/run-session/clear').length,1);
  assert.equal(requests.filter(r=>r[0]==='/api/play/run-session').length,2);
  assert.equal(navigations.length,0);
});
test('计时清理虽丢回包但墓碑读回已确认，两入口都可安全进入',async()=>{
  let marker;
  const {instance,navigations}=page((url,method,data)=>url==='/api/play/run-session'?{code:200,data:{runState:'ENDED',savedAt:marker}}:{code:200,data:method==='POST'||data.sessionId===73||data.sessionId===undefined?fixture.replayed:fixture.completed},false,(_u,_m,data)=>{marker=data.savedAt;return{code:'fail',netFail:true};});
  assert.equal(await instance.replayThemeSession(),true);assert.equal(navigations.length,1);
});

test('幂等返回历史子局却不是最新局时，不清计时、不误进入另一局',async()=>{
  const {instance,requests,navigations}=page((_url,method,data)=>({code:200,data:method==='POST'||data.sessionId===73?fixture.replayed:data.sessionId===72?fixture.completed:{...fixture.replayed,sessionId:74,playInstanceId:'replay:73'}}));
  assert.equal(await instance.replayThemeSession(),false);
  assert.equal(requests.filter(r=>r[0]==='/api/play/run-session/clear').length,0);
  assert.equal(navigations.length,0);
});

test('两入口的混入处理器都定位到实际模块定义，不留下台账零行号',()=>{
  const path=require('node:path');
  const {extractOwnHandlerEntries}=require('../../scripts/lib/xcx-scan.js');
  for(const file of ['pages/play/index.js','pages/topic/index/index.js']) {
    const entries=extractOwnHandlerEntries(path.resolve(file));
    for(const name of ['openThemeHistory','loadMoreThemeHistory','openThemeResultCard','replayThemeSession','checkThemeReplay']) {
      const entry=entries.find(item=>item.name===name);
      assert.ok(entry && entry.line>0 && entry.file.endsWith('/utils/theme-records.js'),file+':'+name);
    }
  }
});


test('主题详情真实历史请求失败进入错误面板，重试成功清错而不叠自动提示', async () => {
  const previous = { getApp: global.getApp, Page: global.Page, wx: global.wx };
  const modulePath = require.resolve('../../pages/topic/index/index.js');
  const requests = []; let definition;
  global.getApp = () => ({ globalData: { statusBarHeight: 20, navBarHeight: 44 }, sendRequest: options => requests.push(options) });
  global.Page = config => { definition = config; };
  global.wx = {};
  try {
    delete require.cache[modulePath]; require(modulePath);
    const instance = { ...definition, data: structuredClone(definition.data), setData(patch) { Object.assign(this.data, patch); } };
    instance.data.id = 9;
    for (const callback of ['fail', 'successStatusAbnormal']) {
      const failed = instance.openThemeHistory(), request = requests.at(-1);
      assert.equal(request.url, '/api/play/theme/history'); assert.equal(request.method, 'GET');
      assert.equal(request.autoErrorToast, false); assert.equal(request.silentError, undefined);
      request[callback]({}); assert.equal(await failed, false);
      assert.match(instance.data.themeRecordsError, /暂未加载.*重试/); assert.equal(instance.data.themeRecordsBusy, false);
      const retry = instance.loadMoreThemeHistory(); requests.at(-1).success({ code: 200, data: [] });
      assert.equal(await retry, true); assert.equal(instance.data.themeRecordsError, '');
    }
    assert.match(fs.readFileSync('pages/topic/index/index.wxml', 'utf8'), /error="{{themeRecordsError}}"/);
  } finally {
    delete require.cache[modulePath]; Object.assign(global, previous);
  }
});
