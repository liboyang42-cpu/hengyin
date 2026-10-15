const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const backpack = require('../../pages/play/utils/theme-backpack.js');
const rewards = require('../../pages/play/utils/theme-rewards.js');
const fixtures = require('./fixtures/theme-reward-preview.json');
const copy = value => JSON.parse(JSON.stringify(value));
const click = (id, value) => ({ currentTarget: { dataset: typeof id === 'number' ? { index: id } : { id } }, detail: { value } });
const messages = [];
global.wx = { showToast: ({ title }) => messages.push(title) };
const base = { sessionId:7,version:8,status:'ACTIVE',capacity:6,used:3,allowDiscard:true,
  contents:[{id:'food',name:'补给',kind:'ITEM',mode:'USE',stat:'HP',quantity:3,slotCost:1,delta:2}],
  quests:[{questKey:'q1',nodeId:41}],activeQuest:'q1',
  allowedActions:[{operation:'GAMEPLAY_PREVIEW',contentId:'q1',resolvesOperation:'QUEST_RESOLVE',choiceKey:'supply'}] };
function page(view, responder) {
  const calls = [];
  const p = { data:{...copy(backpack.data),topicId:9,nodes:[],themeBag:backpack.present(copy(view),[])},
    setData(patch) { for (const [key,value] of Object.entries(patch)) { const parts=key.split('.'); let at=this.data; for(const part of parts.slice(0,-1))at=at[part]; at[parts.at(-1)]=value; } },
    _sessionParams(){return {};},refreshRouteState(){return Promise.resolve(true);},_loadPlayerGameModule(){return Promise.resolve(true);} };
  Object.assign(p,backpack.methods((url,method,data)=>{calls.push({url,method,data:copy(data)});return Promise.resolve(responder(url,data,p));}));
  return {p,calls};
}
function fitted(version='w2-preview-fitted') {
  const pack=copy(fixtures.personal); pack.previewVersion=version; pack.overflow=false;pack.finalUsed=6;
  pack.discardRewards=[{itemId:'food',quantity:2}];pack.actualGrants=[{itemId:'food',quantity:3}];
  Object.assign(pack.rewardOwners[0],{overflow:false,finalUsed:6,discardRewards:pack.discardRewards,actualGrants:{food:3}});
  pack.rewardOwners[0].rewardSources[0].discarded=2;return pack;
}

test('W2 预览完整展示任务、章节、事件奖励和每个归属的服务端装载，不在客户端合计',()=>{
  const pack=rewards.preview(fixtures.personal,base);
  assert.deepEqual(pack.owners[0].sources.map(row=>row.sourceLabel),['任务奖励','第1章完成奖励','事件奖励']);
  assert.equal(pack.owners[0].sources[2].quantity,2);assert.equal(pack.owners[0].finalUsed,8);
  assert.equal(pack.owners[0].rewards[0].quantity,5);assert.equal(pack.owners[0].rewards[0].name,'补给');
  const team=rewards.preview(fixtures.teamFacility,{...base,scope:'TEAM',teamId:17});
  assert.deepEqual(team.owners.map(row=>[row.own,row.label,row.finalUsed]),[[true,'本队',6],[false,'队伍 18',4]]);
  assert.equal(rewards.preview({...fixtures.personal,previewVersion:undefined},base),null);
  assert.equal(rewards.preview({...fixtures.personal,overflow:undefined},base),null);
});

test('溢出的个人任务仍可通过 GAMEPLAY_PREVIEW 入口预览；放弃后重新预览并透传冻结版本',async()=>{
  const {p,calls}=page(base,(url,command)=>({code:200,data:url.endsWith('gameplay-preview')
    ? command.discardRewards ? fitted():copy(fixtures.personal):{...base,version:9}}));
  assert.equal(p.data.themeBag.actionRows[0].operation,'QUEST_RESOLVE');assert.equal(p.data.themeBag.actionRows[0].enabled,true);
  await p.onThemeGameplayAction(click(0));assert.equal(calls[0].url,'/api/play/theme/gameplay-preview');
  assert.deepEqual(calls[0].data,{sessionId:7,expectedVersion:8,operation:'QUEST_RESOLVE',contentId:'q1',choiceKey:'supply'});
  assert.equal(await p.confirmThemeRewardPreview(),false);assert.equal(calls.length,1);
  await p.changeThemeRewardDiscard(click('food',2));assert.deepEqual(calls[1].data.discardRewards,[{itemId:'food',quantity:2}]);
  assert.equal(await p.confirmThemeRewardPreview(),true);
  const command=calls.find(row=>row.url.endsWith('gameplay-action')).data;
  assert.equal(command.previewVersion,'w2-preview-fitted');assert.equal(command.choiceKey,'supply');
  assert.deepEqual(command.discardRewards,[{itemId:'food',quantity:2}]);assert.equal(command.discardOwned,undefined);
  assert.equal(p.data.themeRewardPreview,null);
});

test('多奖励方案先选择再预览，内部 choiceKey 原样透传',async()=>{
  const view={...base,allowedActions:[...base.allowedActions,{...base.allowedActions[0],choiceKey:'keep'}]};
  const {p,calls}=page(view,()=>({code:200,data:fixtures.personal}));await p.onThemeGameplayAction(click(0));
  assert.equal(calls.length,0);assert.equal(p.data.themeQuestChoice.choices.length,2);
  await p.selectThemeQuestChoice(click(1));assert.equal(calls[0].data.choiceKey,'keep');
});

test('FACILITY_USE 使用 W2 团队 result 包装，费用、放弃和预览版本同笔提交；不能替他队放弃',async()=>{
  const team={...base,scope:'TEAM',teamId:17,sessionId:70,activityId:5,revision:12,authorityEpoch:4,status:'RUNNING',
    availableActions:['FACILITY_USE'],quests:[],interactionTools:[{kind:'FACILITY',operationKey:'f'}]};
  const {p,calls}=page(team,(url,cmd)=>({code:200,data:url.endsWith('/preview')?copy(fixtures.teamFacility):{
    activityId:5,requestId:cmd.requestId,action:cmd.action,receiptId:1,revision:13,outcome:'APPLIED',result:{result:{noEffect:false}}}}));
  await p._prepareThemeGameplayAction({operation:'FACILITY_USE',contentId:'f',costOption:1,discardRewards:[{itemId:'food',quantity:2}]},{});
  assert.deepEqual(calls[0].data,{activityId:5,action:'FACILITY_USE',payload:{facilityKey:'f',costOption:1,discardRewards:[{itemId:'food',quantity:2}]}});
  assert.equal(await p.changeThemeRewardDiscard(click('other-team-item',1)),false);
  assert.equal(await p.confirmThemeRewardPreview(),false);
  await p.rereadThemeRewardPreview();
  await p.confirmThemeRewardPreview();
  assert.deepEqual(calls.at(-1).data.payload,{facilityKey:'f',costOption:1,discardRewards:[{itemId:'food',quantity:2}],previewVersion:'w2-facility-preview'});
  assert.equal(calls.at(-1).data.expectedRevision,12);assert.equal(calls.at(-1).data.authorityEpoch,4);
});

test('过期 previewVersion 必须重新读取，显示新版本且不自动再次结算',async()=>{
  let previews=0;
  const {p,calls}=page(base,(url)=>url.endsWith('gameplay-preview')?{code:200,data:fitted(++previews===1?'old':'new')}
    :url.endsWith('gameplay-action')?{code:409,msg:'装载预览已过期，请重新预览并确认'}:{code:200,data:{...base,version:9}});
  await p._readThemeRewardPreview({operation:'QUEST_RESOLVE',contentId:'q1',choiceKey:'supply'});
  assert.equal(await p.confirmThemeRewardPreview(),false);
  assert.equal(previews,2);assert.equal(calls.filter(row=>row.url.endsWith('gameplay-action')).length,1);
  assert.equal(calls.filter(row=>row.url.endsWith('gameplay-preview'))[1].data.expectedVersion,9);
  assert.equal(p.data.themeRewardPreview.pack.previewVersion,'new');assert.match(p.data.themeRewardPreview.notice,/再次确认/);
});

test('个人结果未知锁定原选择和 actionId，只读状态不再次结算或关闭预览',async()=>{
  let commands=0;
  const {p,calls}=page(base,(url)=>url.endsWith('gameplay-preview')?{code:200,data:fitted()}
    :url.endsWith('gameplay-action')?++commands===1?{netFail:true}:{code:200,data:{...base,version:9}}:{code:200,data:{...base,version:9}});
  await p._readThemeRewardPreview({operation:'QUEST_RESOLVE',contentId:'q1',discardRewards:[{itemId:'food',quantity:2}]});
  assert.equal(await p.confirmThemeRewardPreview(),false);assert.equal(p.data.themeBagUnknown,true);
  assert.equal(await p._readThemeRewardPreview({operation:'QUEST_RESOLVE',contentId:'q1',discardRewards:[]}),false);
  const frozen=copy(p._gameplayPending.command);
  assert.equal(await p.retryThemeBagAction(),false);
  const writes=calls.filter(row=>row.url.endsWith('gameplay-action'));assert.equal(writes.length,1);assert.deepEqual(p._gameplayPending.command,frozen);
  assert.ok(calls.some(row=>row.url==='/api/play/theme/state'));
  assert.deepEqual(p.data.themeRewardPreview.input.discardRewards,[{itemId:'food',quantity:2}]);assert.equal(p.data.themeBagUnknown,true);
});

const completed={...base,status:'COMPLETED',result:{success:true},pending:{'chapter:0:END:food':{itemId:'food',name:'补给',quantity:2,slotCost:1,slots:2}},
  allowedActions:[{operation:'RECEIVE',contentId:'chapter:0:END:food',claimOnly:true}]};

test('完成局补领把 discardOwned 和真实 pending source 同笔提交；禁止其他任务和单独丢弃',async()=>{
  const {p,calls}=page(completed,url=>({code:200,data:{...completed,pending:{},version:9}}));
  assert.equal(await p._themeCommand('DISCARD','food'),false);
  assert.equal(await p._themeGameplayCommand({operation:'FACILITY_USE',contentId:'f'}),false);
  assert.equal(p.data.themeBag.items[0].canUse,false);assert.equal(p.data.themeBag.actionRows[0].enabled,false);
  assert.equal(await p.openThemePendingClaim('invented'),false);assert.equal(calls.length,0);
  await p.onThemeBagAction({currentTarget:{dataset:{operation:'RECEIVE',id:'chapter:0:END:food'}}});
  assert.equal(calls.length,0);p.changeThemeOwnedDiscard(click('food',1));await p.confirmThemePendingClaim();
  assert.deepEqual(calls[0].data.discardOwned,[{itemId:'food',quantity:1}]);assert.equal(calls[0].data.contentId,'chapter:0:END:food');
  assert.equal(calls[0].data.operation,'RECEIVE');assert.equal(calls[0].data.previewVersion,undefined);
  assert.equal(p.data.themePendingClaim,null);assert.ok(messages.includes('待领奖励已领取'));
});

test('补领失败保留库存并明确显示服务端拒绝，校验丢弃整数/上限；允许调整后重试',async()=>{
  const {p,calls}=page(completed,url=>url.endsWith('/action')?{code:409,msg:'持有物资被托管锁定'}:{code:200,data:completed});
  await p.openThemePendingClaim('chapter:0:END:food');p.changeThemeOwnedDiscard(click('food',4));
  assert.equal(await p.confirmThemePendingClaim(),false);assert.equal(calls.length,0);
  p.changeThemeOwnedDiscard(click('food',1));assert.equal(await p.confirmThemePendingClaim(),false);
  assert.equal(p.data.themeBag.contents[0].quantity,3);assert.match(p.data.themePendingClaim.error,/锁定/);
  p.changeThemeOwnedDiscard(click('food',0));assert.equal(p.data.themePendingClaim.error,'');
});

test('团队活动仍运行但本队已结束时只显示领取；ITEM_RECEIVE 透传 discardOwned 并保留团队权限纪元',async()=>{
  const {p,calls}=page(base,(url,cmd)=>({code:200,data:{activityId:5,requestId:cmd.requestId,action:cmd.action,receiptId:4,revision:13,outcome:'APPLIED',result:{source:'chapter:0:END:food'}}}));
  p._applyThemeTeamView({...completed,teamId:17,scope:'TEAM',authorityEpoch:4},{activityId:5,sessionId:70,revision:12,status:'RUNNING',availableActions:['ITEM_RECEIVE','FACILITY_USE']});
  assert.equal(p.data.themeBag.claimOnly,true);assert.equal(await p._themeGameplayCommand({operation:'FACILITY_USE',contentId:'f'}),false);
  assert.equal(await p._themeChapterCommand('CHAPTER_END','0'),true);assert.equal(calls.length,0);
  await p.openThemePendingClaim('chapter:0:END:food');p.changeThemeOwnedDiscard(click('food',2));await p.confirmThemePendingClaim();
  assert.equal(calls[0].data.action,'ITEM_RECEIVE');assert.deepEqual(calls[0].data.payload,{contentId:'chapter:0:END:food',discardOwned:[{itemId:'food',quantity:2}]});
  assert.equal(calls[0].data.authorityEpoch,4);
});

test('背包分组、详情和固定确认复用玩家 token，无本地装载合计',()=>{
  const wxml=fs.readFileSync(require.resolve('../../pages/play/theme-backpack.wxml'),'utf8');
  const css=fs.readFileSync(require.resolve('../../pages/play/theme-backpack.wxss'),'utf8');
  assert.match(wxml,/themeBag\.used/);assert.match(wxml,/themeBag\.valueRows/);assert.match(wxml,/bindtap="openThemeItem"/);
  assert.match(wxml,/themeRewardPreview\.pack\.owners/);assert.match(wxml,/themePendingClaim\.owned/);
  assert.doesNotMatch(wxml,/#[0-9a-fA-F]{3,8}/);assert.match(css,/--cy-comp-sheet-player/);
  assert.deepEqual(backpack.present({...base,hpEnabled:true,hpName:'体力',hp:4,hpMax:8,attributes:[{key:'counter.x',label:'能量',value:2,max:5},{key:'counter.hidden',label:'隐藏',visible:false,value:8}]},[]).valueRows,
    [{key:'hp',name:'体力',value:4,max:8,zero:undefined},{key:'counter.x',name:'能量',value:2,max:5}]);
});

test('补领结果未知后即使回读 pending 已消失，仍重放原 actionId 和 discardOwned 核对结果',async()=>{
  let writes=0;
  const {p,calls}=page(completed,url=>url.endsWith('/action')?++writes===1?{netFail:true}:{code:200,data:{...completed,pending:{},version:9}}:{code:200,data:{...completed,pending:{},version:9}});
  await p.openThemePendingClaim('chapter:0:END:food');p.changeThemeOwnedDiscard(click('food',1));
  await p.confirmThemePendingClaim();p.changeThemeOwnedDiscard(click('food',2));
  assert.equal(p.data.themePendingClaim.owned[0].discard,1);
  await p.refreshThemeBackpack();assert.equal(await p.retryThemeBagAction(),true);
  const commands=calls.filter(row=>row.url.endsWith('/action'));assert.deepEqual(commands[0].data,commands[1].data);
  assert.equal(p.data.themePendingClaim,null);
});

test('奖励变化使旧放弃选择失效时，可明确重新读取全部奖励并重新选择',async()=>{
  const {p,calls}=page(base,(url,command)=>url.endsWith('gameplay-preview')&&command.discardRewards&&command.discardRewards.length
    ?{code:409,msg:'放弃数量超过本次奖励'}:{code:200,data:url.endsWith('gameplay-preview')?fixtures.personal:base});
  await p._readThemeRewardPreview({operation:'QUEST_RESOLVE',contentId:'q1',discardRewards:[{itemId:'food',quantity:9}]});
  assert.equal(p.data.themeRewardPreview.pack,null);assert.equal(await p.confirmThemeRewardPreview(),false);
  await p.rereadThemeRewardPreview();assert.deepEqual(calls.at(-1).data.discardRewards,[]);
  assert.match(p.data.themeRewardPreview.notice,/重新选择/);
});

test('团队任务预览未给 rewardOwners 时奖励属于本队，可放弃到服务器确认装载', async () => {
  const view = { ...base, scope:'TEAM', teamId:17, sessionId:70, activityId:5, revision:12, authorityEpoch:4, status:'RUNNING', availableActions:['QUEST_RESOLVE'] };
  const pack = { previewVersion:'task-v1', overflow:true, finalUsed:8, capacity:6, grants:{food:5}, rewardSources:[{source:'quest:q1',itemId:'food',quantity:5}] };
  const {p,calls} = page(view, (url, cmd) => ({code:200,data:url.endsWith('/preview') ? {expectedRevision:12,authorityEpoch:4,settlement:cmd.payload.discardRewards ? {...pack,previewVersion:'task-v2',overflow:false,finalUsed:6,discardRewards:cmd.payload.discardRewards}:pack} :
    {activityId:5,requestId:cmd.requestId,action:cmd.action,receiptId:1,revision:13,outcome:'APPLIED',result:{}}}));
  await p._readThemeRewardPreview({operation:'QUEST_RESOLVE',contentId:'q1'});
  assert.equal(p.data.themeRewardPreview.pack.owners[0].own,true);assert.equal(p.data.themeRewardPreview.pack.owners[0].label,'本队');
  await p.changeThemeRewardDiscard(click('food',2));
  assert.equal(p.data.themeRewardPreview.pack.owners[0].finalUsed,6);
  assert.equal(await p.confirmThemeRewardPreview(),true);
  assert.deepEqual(calls.find(row=>row.url.endsWith('/command')).data.payload.discardRewards,[{itemId:'food',quantity:2}]);
});

test('章节动作仅旧版个人局发送，通用个人/团队均由服务端自动发奖；团队恢复传 recoveryKey', async () => {
  for(const view of [{...base,runtimeScope:'PLAYER'}, {...base,scope:'TEAM'}]) {
    const {p,calls}=page(view,()=>{throw new Error('通用局不可发章节命令');});
    assert.equal(await p._themeChapterCommand('CHAPTER_START','0'),true);
    assert.equal(await p._themeChapterCommand('CHAPTER_END','0'),true);assert.equal(calls.length,0);
  }
  const old=page(base,()=>({code:200,data:base}));
  assert.equal(await old.p._themeChapterCommand('CHAPTER_START','0'),true);
  assert.equal(old.calls[0].data.operation,'CHAPTER_START');assert.equal(old.calls[0].url,'/api/play/theme/action');
  const team={...base,scope:'TEAM',teamId:17,activityId:5,sessionId:70,revision:12,authorityEpoch:4,status:'RUNNING'};
  const {p,calls}=page(team,(url,cmd)=>({code:200,data:{activityId:5,requestId:cmd.requestId,action:cmd.action,receiptId:1,revision:13,outcome:'APPLIED',result:{}}}));
  assert.equal(await p._themeGameplayCommand({operation:'RECOVER',contentId:'rest'}),true);
  assert.deepEqual(calls[0].data.payload,{recoveryKey:'rest'});
});
