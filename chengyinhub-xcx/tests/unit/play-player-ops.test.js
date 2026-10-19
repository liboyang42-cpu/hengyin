const test = require('node:test');
const assert = require('node:assert/strict');
const ops = require('../../pages/play/utils/theme-player-ops.js');
const fixture = require('./fixtures/player-ops-view.json');
const manualFixture = require('./fixtures/player-manual-view.json');
const clone = value => JSON.parse(JSON.stringify(value));
const event = dataset => ({ currentTarget: { dataset } });
const receipt = (command, result = {}, extra = {}) => ({ code: 200, data: { receiptId: 51, activityId: command.activityId, requestId: command.requestId, action: command.action, revision: 13, outcome: 'APPLIED', result, ...extra } });
function page(reply) {
  const calls = [], host = { data: { ...clone(ops.data), themeBag: clone(fixture), nodes: [{ nodeId: 41, name: '集合站' }], current: { nodeId: 41 } },
    refreshes: 0, refreshThemeBackpack() { this.refreshes++; return Promise.resolve(true); }, setData(patch) { Object.assign(this.data, patch); } };
  Object.assign(host, ops.methods(async (url, method, data) => { calls.push({ url, method, data }); return reply ? reply(url, data) : receipt(data); }));
  return { host, calls };
}
test('playerView availableActions 是三种操作唯一入口，非队长/旧服/个人局不猜资格', () => {
  assert.deepEqual(ops.rows(fixture).map(row => row.action), ['ARRIVAL_ISSUE', 'TEAM_EXIT']);
  assert.deepEqual(ops.rows(manualFixture).map(row => row.action), ['MANUAL_SIGN']);
  for (const action of fixture.availableActions) assert.deepEqual(ops.rows({ ...fixture, availableActions: [action] }).map(row => row.action), [action]);
  for (const view of [{ ...fixture, availableActions: [] }, { ...fixture, availableActions: undefined }, { ...fixture, scope: 'PERSONAL' }]) assert.deepEqual(ops.rows(view), []);
});
test('issueArrival: questKey 与 operationKind/operationKey 互斥，站名仅来自路线', () => {
  const targets = ops.arrivalTargets(fixture, [{ nodeId: 41, name: '集合站' }]);
  assert.deepEqual(targets[0], { label: '集合站', payload: { questKey: 'q1' }, nodeId: 41 });
  assert.deepEqual(targets[1].payload, { operationKind: 'PLAN', operationKey: 'leave' });
  assert.equal(ops.arrivalTargets(fixture, [])[0].label, '站点资料尚未加载');
});
test('ARRIVAL_ISSUE 只发送精确 frozen body，回执产生凭证并重读、不自行签到完成', async () => {
  const { host, calls } = page((_, command) => receipt(command, { nonce: 'a'.repeat(43), expiresAt: 1791586800000, teamId: 8, locationKey: 'gather', version: 2 }));
  host.openPlayerOperation(event({ action: 'ARRIVAL_ISSUE' }));
  assert.equal(await host.submitPlayerOperation(), true);
  assert.deepEqual(calls[0].data.payload, { questKey: 'q1' });
  assert.equal(calls[0].data.action, 'ARRIVAL_ISSUE'); assert.equal(calls[0].data.authorityEpoch, 4); assert.equal(calls[0].data.expectedRevision, 12);
  assert.equal(host.data.playerArrivalProof.nonce.length, 43); assert.equal(host.refreshes, 1); assert.equal(host.data.themeBag.quests[0].trustedPass, false);
});
test('MANUAL_SIGN sequence 来自本人只读投影，payload 严格为受控业务命令', async () => {
  const { host, calls } = page((_, command) => receipt(command, { signatureId: '51' }));
  host.data.themeBag = clone(manualFixture); host.openPlayerOperation(event({ action: 'MANUAL_SIGN' }));
  host.inputPlayerOperationTime({ detail: { value: '2026-10-09 14:00:00' } });
  assert.equal(await host.submitPlayerOperation(), true);
  assert.deepEqual(calls[0].data.payload, { sequence: 3, occurredAt: new Date('2026-10-09T14:00:00').getTime(), command: { action: 'QUEST_START', payload: { questKey: 'q1' } } });
  assert.equal(host.data.playerOperationForm, null); assert.equal(host.refreshes, 1);
  const state = ops.manualRows(manualFixture.run.manual);
  assert.deepEqual(state.map(row => row.statusText), ['待核验', '已核验', '未通过']);
  host.data.themeBag.run.manual = undefined; host.openPlayerOperation(event({action:'MANUAL_SIGN'}));
  assert.equal(await host.submitPlayerOperation(), false); assert.equal(calls.length, 1);
});
test('TEAM_EXIT 必须说明原因并二次确认，取消不写，变化后重新确认，成功按 view 更新', async () => {
  const { host, calls } = page(); host.openPlayerOperation(event({action:'TEAM_EXIT'}));
  assert.equal(await host.submitPlayerOperation(), false); assert.equal(calls.length, 0);
  host.inputPlayerOperationReason({detail:{value:'本队提前离场'}}); await host.submitPlayerOperation();
  assert.equal(host.data.playerExitConfirm, true); assert.equal(calls.length, 0);
  host.cancelPlayerExit(); assert.equal(calls.length, 0);
  await host.submitPlayerOperation(); host.data.themeBag.authorityEpoch = 5;
  assert.equal(await host.confirmPlayerExit(), false); assert.equal(calls.length, 0);
  host.openPlayerOperation(event({action:'TEAM_EXIT'})); host.inputPlayerOperationReason({detail:{value:'本队提前离场'}}); await host.submitPlayerOperation(); assert.equal(await host.confirmPlayerExit(), true);
  assert.deepEqual(calls[0].data.payload,{teamId:8,reason:'本队提前离场'}); assert.equal(host.refreshes, 1);
});
test('操作消失/预览/另一未知命令锁定时不写；读入失败保持所有输入', async () => {
  const {host,calls}=page(()=>({code:500,msg:'实际到场尚未核验'})); host.openPlayerOperation(event({action:'TEAM_EXIT'})); host.inputPlayerOperationReason({detail:{value:'先离场'}});
  host.data.themeBag.availableActions=[]; assert.equal(await host.submitPlayerOperation(),false); assert.equal(calls.length,0);
  host.data.themeBag.availableActions=['TEAM_EXIT']; host.data.isPreview=true; assert.equal(await host.submitPlayerOperation(),false);
  host.data.isPreview=false; host._planPending={}; assert.equal(await host.submitPlayerOperation(),false); host._planPending=null;
  await host.submitPlayerOperation(); await host.confirmPlayerExit(); assert.equal(host.data.playerOperationForm.reason,'先离场'); assert.match(host.data.playerOperationError,/尚未核验/);
});
test('epoch conflict 展示服务端冲突并 reread，绝不用新 epoch silently retry', async () => {
  const {host,calls}=page(()=>({code:500,msg:'执行权已变化，请刷新',data:{reasonCode:'GAME_AUTHORITY_CONFLICT'}}));
  host.openPlayerOperation(event({action:'ARRIVAL_ISSUE'})); assert.equal(await host.submitPlayerOperation(),false); assert.equal(calls.length,1); assert.equal(host.refreshes,1); assert.match(host.data.playerOperationError,/已变化/);
});
for(const replayed of [false,true]) test((replayed?'replayed':'unknown')+' → original receipt only，pending 可核对不能重写',async()=>{
  let command; const {host,calls}=page((url,body)=>{if(url.endsWith('/command')){command=body;return replayed?receipt(body,{}, {replayed:true}):{netFail:true};}return {code:200,data:{outcome:'PENDING'}};});
  host.openPlayerOperation(event({action:'ARRIVAL_ISSUE'})); await host.submitPlayerOperation(); assert.ok(host._playerOpsPending); assert.equal(host.data.playerOperationUnknown,true);
  await host.checkPlayerOperationReceipt(); await host.submitPlayerOperation(); assert.equal(calls.filter(row=>row.method==='POST').length,1); assert.equal(calls[1].data.requestId,command.requestId);
});
test('不匹配的回执不展示凭证且保留原请求；会话切换的结果不污染新局',async()=>{
  let command;const {host,calls}=page((url,body)=>{if(url.endsWith('/command')){command=body;return {netFail:true};}return receipt(command,{nonce:'wrong'},{action:'TEAM_EXIT'});});
  host.openPlayerOperation(event({action:'ARRIVAL_ISSUE'})); await host.submitPlayerOperation(); assert.equal(host.data.playerArrivalProof,null); assert.ok(host._playerOpsPending); assert.match(host.data.playerOperationError,/不匹配/); assert.equal(calls.length,2);
});
test('run.exitedTeams 唯一退出状态来源，TEAM_EXIT receipt 不伪造 completed',()=>{
  assert.equal(ops.exitText(fixture),'');assert.equal(ops.exitText({...fixture,run:{exitedTeams:{8:51}}}),'本队已退出本局');
});
test('人工任务费用组/结算选项只签作者实际选择，不自动支付或结算',()=>{
  const view=clone(manualFixture);view.quests[0].costOptions=[{label:'数值',hpCost:2},{label:'物资',resourceCosts:[{itemId:'tool',quantity:1}]}];view.quests[0].choices=[{choiceKey:'safe',label:'安全方案'}];
  const commands=ops.manualCommands(view,[{nodeId:41,name:'集合站'}]);
  assert.deepEqual(commands.map(row=>row.command),[{action:'QUEST_START',payload:{questKey:'q1',costOption:0}},{action:'QUEST_START',payload:{questKey:'q1',costOption:1}},{action:'QUEST_RESOLVE',payload:{questKey:'q1',choiceKey:'safe'}},{action:'QUEST_CANCEL',payload:{questKey:'q1'}}]);
});
test('实际时间非法/旧连续序号不提交，核对失败保留现场记录输入',async()=>{
  const {host,calls}=page();host.data.themeBag=clone(manualFixture);host.openPlayerOperation(event({action:'MANUAL_SIGN'}));
  for(const value of ['', '2026-02-30 14:00:00','2026-10-09 25:00:00']){host.inputPlayerOperationTime({detail:{value}});assert.equal(await host.submitPlayerOperation(),false);}
  host.inputPlayerOperationTime({detail:{value:'2026-10-09 14:00:00'}});host.data.themeBag.run.manual.nextSequence=4;assert.equal(await host.submitPlayerOperation(),false);assert.equal(calls.length,0);assert.match(host.data.playerOperationError,/已变化/);
});
test('页面 epoch/会话切换后原写结果不污染新局；只读原回执才解未知锁',async()=>{
  let resolve,command;const {host,calls}=page((url,body)=>{if(url.endsWith('/command')){command=body;return new Promise(done=>{resolve=done;});}return receipt(command,{nonce:'a'.repeat(43),expiresAt:1791586800000});});
  host.openPlayerOperation(event({action:'ARRIVAL_ISSUE'}));const sent=host.submitPlayerOperation();host._themeEpoch=1;host.data.themeBag.sessionId=99;host._applyPlayerOpsView(host.data.themeBag);
  resolve(receipt(command,{nonce:'wrong'}));assert.equal(await sent,false);assert.equal(host.data.playerArrivalProof,null);assert.ok(host._playerOpsPending);
  await host.checkPlayerOperationReceipt();assert.equal(host.data.playerArrivalProof,null);assert.equal(host._playerOpsPending,null);assert.equal(calls.filter(row=>row.method==='POST').length,1);
});
test('PLAYER session view 的真实编辑接线保留 run/manual/currentChapterId 并刷新只读操作行',()=>{
  const bag = require('../../pages/play/utils/theme-backpack.js'); const {host}=page();
  Object.assign(host,bag.methods(()=>Promise.resolve({code:500})));host._stopThemePoll=()=>{};host._startThemePoll=()=>{};
  const server=clone(manualFixture), projection={sessionId:7,activityId:5,revision:12,status:'RUNNING',currentChapterId:22,availableActions:['MANUAL_SIGN']};
  server.currentChapterId=22;host._applyThemeTeamView(server,projection);
  assert.deepEqual(host.data.themeBag.run,server.run);assert.equal(host.data.themeBag.currentChapterId,22);
  assert.deepEqual(host.data.playerOperationRows.map(row=>row.action),['MANUAL_SIGN']);assert.equal(host.data.playerManualRows[0].statusText,'待核验');
  host._applyThemeTeamView({...server,run:{exitedTeams:{8:51}}},{...projection,availableActions:[]});assert.deepEqual(host.data.playerOperationRows,[]);assert.equal(host.data.playerExitText,'本队已退出本局');
});
test('unknown → 匹配 APPLIED receipt 只读确认凭证，解锁一次且不会重写',async()=>{
  let command;const {host,calls}=page((url,body)=>{if(url.endsWith('/command')){command=body;return{netFail:true};}return receipt(command,{nonce:'a'.repeat(43),expiresAt:1791586800000,teamId:8,locationKey:'gather',version:2});});
  host.openPlayerOperation(event({action:'ARRIVAL_ISSUE'}));assert.equal(await host.submitPlayerOperation(),true);assert.equal(host._playerOpsPending,null);assert.equal(host._themeSending,false);assert.equal(host.data.playerOperationUnknown,false);assert.equal(host.data.playerArrivalProof.stationName,'集合站');assert.equal(calls.filter(row=>row.method==='POST').length,1);
});
test('X028 暂停/中止/取消/人工账只按服务端 run 状态统一提示，运行中不提示',()=>{
  assert.equal(ops.runNotice({...fixture,run:{operationStatus:'RUNNING',authorityMode:'ONLINE'}}),'');
  assert.equal(ops.runNotice({...fixture,run:undefined}),'');
  assert.match(ops.runNotice({...fixture,run:{operationStatus:'PAUSED',authorityMode:'ONLINE'}}),/暂停.*冻结/);
  assert.match(ops.runNotice({...fixture,run:{operationStatus:'INTERRUPTED',authorityMode:'ONLINE'}}),/中止.*不计结局/);
  assert.match(ops.runNotice({...fixture,run:{operationStatus:'CANCELLED',authorityMode:'ONLINE'}}),/取消/);
  assert.match(ops.runNotice({...fixture,run:{operationStatus:'PAUSED',authorityMode:'MANUAL'}}),/人工账/);
  const {host}=page();host._applyPlayerOpsView({...clone(fixture),availableActions:[],run:{operationStatus:'PAUSED',authorityMode:'ONLINE'}});
  assert.match(host.data.playerRunNotice,/暂停/);assert.deepEqual(host.data.playerOperationRows,[]);
  host._applyPlayerOpsView({...clone(fixture),run:{operationStatus:'RUNNING',authorityMode:'ONLINE'}});assert.equal(host.data.playerRunNotice,'');
  const fs=require('node:fs'),path=require('node:path');
  const entry=fs.readFileSync(path.join(__dirname,'../../pages/play/theme-player-ops-entry.wxml'),'utf8');
  assert.match(entry,/wx:if="\{\{playerRunNotice \|\|/);assert.match(entry,/\{\{playerRunNotice \|\| playerExitText/);
});
test('F0351 离开页面与退出本局分名：团队/个人/旧玩法分别写明进度、资源、计时与费用后果，退出不等于退款',()=>{
  const team=ops.exitGuide(fixture),personal=ops.exitGuide({...fixture,scope:'PLAYER'}),legacy=ops.exitGuide(null);
  assert.equal(team.mode,'TEAM');assert.equal(personal.mode,'PERSONAL');assert.equal(legacy.mode,'LEGACY');
  for(const guide of [team,personal,legacy]){assert.equal(guide.leave.title,'离开页面');assert.match(guide.leave.lines.join(),/保留.*同一局/);assert.match(guide.leave.lines.join(),/不产生扣费或退款/);assert.match(guide.exit.lines.join(),/退款/);}
  assert.match(team.exit.lines.join(),/不可撤销/);assert.match(team.exit.lines.join(),/预留与锁定释放/);assert.match(team.exit.lines.join(),/计时不停/);assert.match(team.exit.lines.join(),/不等于退款/);
  assert.match(personal.exit.title,/没有单独的退出动作/);assert.match(legacy.exit.title,/没有单独的退出动作/);
  assert.equal(ops.data.playerExitGuide.mode,'LEGACY');
  const {host}=page();host._applyPlayerOpsView(clone(fixture));assert.equal(host.data.playerExitGuide.mode,'TEAM');
  const fs=require('node:fs'),path=require('node:path');
  const entry=fs.readFileSync(path.resolve(__dirname,'../../pages/play/theme-player-ops-entry.wxml'),'utf8'),sheet=fs.readFileSync(path.resolve(__dirname,'../../pages/play/theme-player-ops.wxml'),'utf8');
  assert.match(entry,/离开与退出/);assert.match(sheet,/只是离开页面不会退出本局/);assert.match(sheet,/退出游玩不等于退款/);
});
