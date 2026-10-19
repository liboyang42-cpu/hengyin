const test = require('node:test');
const assert = require('node:assert/strict');
// zeroHp/resultCard are actual backend MySQL-test readbacks; the shared transport envelope is a request stub.
const recorded = name => require('../../../docs/delivery/gg-r5-zeroview-fixtures/' + name + '.json');
const fixtures = {
  personal: recorded('personal-steps-awaiting-baseline'),
  shared: { sessionId: 17, activityId: 5, revision: 12, status: 'RUNNING', availableActions: [], player: recorded('team-steps-member-disabled') },
};
const clone = value => JSON.parse(JSON.stringify(value));
const pagePath = require.resolve('../../pages/play/index.js');

function load(view = fixtures.personal) {
  const requests = [], wxCalls = [];
  let definition, responder;
  global.getApp = () => ({ globalData: { features: {} }, sendRequest(options) {
    requests.push(options);
    Promise.resolve().then(() => responder(options)).then(result => options.success(result), () => options.fail());
  } });
  global.Page = config => { definition = config; };
  global.wx = {
    showToast() {},
    login(options) { wxCalls.push('login'); options.success({ code: 'test-login-code' }); },
    getWeRunData(options) { wxCalls.push('werun'); options.success({ encryptedData: 'test-cipher', iv: 'test-iv' }); },
    openSetting(options) { options.success({ authSetting: { 'scope.werun': true } }); },
  };
  delete require.cache[pagePath]; require(pagePath);
  const page = { ...definition, data: { ...clone(definition.data), topicId: 9, nodes: [{ nodeId: 41, name: '主挑战' }] }, _themeHidden: true };
  page.setData = function(patch) { for (const [key, value] of Object.entries(patch)) {
    const parts = key.split('.'); let target = this.data;
    for (const part of parts.slice(0, -1)) target = target[part] || (target[part] = {});
    target[parts.at(-1)] = value;
  } };
  page._applyThemeView(clone(view));
  page.refreshThemeBackpack = async () => true;
  return { page, requests, wxCalls, respond(fn) { responder = fn; } };
}

test('真实 Page 读取个人和共享 player zeroHp，只认会员 allowedActions', async () => {
  const { page, requests, wxCalls } = load();
  assert.equal(page.data.zeroHpView.progressText, '0 / 100');
  assert.equal(page.data.zeroHpView.metaText, '恢复到 4 · 已恢复 0 / 2 次 · 用尽后：本局结束');
  assert.equal(page.data.zeroHpView.canSteps, true);
  page._applyThemeTeamView(clone(fixtures.shared.player), clone(fixtures.shared));
  assert.equal(page.data.zeroHpView.memberText, '原队长');
  assert.equal(page.data.zeroHpView.canSteps, false);
  assert.equal(await page.syncZeroHpSteps(), false);
  assert.equal(requests.length, 0); assert.deepEqual(wxCalls, []);
  const missing = clone(fixtures.shared.player); delete missing.zeroHp.allowedActions;
  page._applyThemeTeamView(missing, { ...fixtures.shared, availableActions: ['ZERO_HP_STEPS'] });
  assert.equal(page.data.zeroHpView.canSteps, false);
});

test('微信运动拒绝授权保留恢复进度，设置及重试入口可再次同步', async () => {
  const env = load();
  wx.getWeRunData = options => options.fail();
  assert.equal(await env.page.syncZeroHpSteps(), false);
  assert.match(env.page.data.zeroHpError, /授权微信运动/);
  assert.equal(env.page.data.zeroHpPermissionDenied, true);
  assert.equal(env.page.data.zeroHpView.progress, 0); assert.equal(env.requests.length, 0);
  env.page.openZeroHpWeRunSettings();
  assert.equal(env.page.data.zeroHpPermissionDenied, false);
  wx.getWeRunData = options => options.success({ encryptedData: 'test-cipher', iv: 'test-iv' });
  env.respond(() => ({ code: 200, data: { ...fixtures.personal, version: 9 } }));
  assert.equal(await env.page.syncZeroHpSteps(), true);
});

test('恢复入口不重发其他待核对任务，权限回读变更后不发旧成员数据', async () => {
  const env = load();
  env.page._gameplayPending = { input: { operation: 'QUEST_START' } };
  assert.equal(await env.page.syncZeroHpSteps(), false);
  assert.match(env.page.data.zeroHpError, /核对上一条/); assert.equal(env.wxCalls.length, 0);
  env.page._gameplayPending = null;
  let run;
  wx.getWeRunData = options => { run = options; };
  const pending = env.page.syncZeroHpSteps();
  env.page._applyThemeView({ ...fixtures.personal, zeroHp: { ...fixtures.personal.zeroHp, allowedActions: [] } });
  run.success({ encryptedData: 'late-cipher', iv: 'test-iv' });
  assert.equal(await pending, false); assert.equal(env.requests.length, 0);
});

for (const failure of [{ code: 'fail', netFail: true }, { code: 502, httpFail: true }]) {
  test('未确认请求保留同 actionId 和密文重试，不再次获取一次性 code ' + failure.code, async () => {
    const env = load(), receipt = { ...clone(fixtures.personal), version: 9, zeroHp: { ...fixtures.personal.zeroHp, phase: 'RECOVERING', progress: 0 } };
    let calls = 0;
    env.respond(() => ++calls === 1 ? failure : { code: 200, data: receipt });
    assert.equal(await env.page.syncZeroHpSteps(), false);
    const pending = clone(env.page._gameplayPending.command);
    assert.equal(env.page.data.themeBagUnknown, true);
    assert.equal(await env.page.retryThemeBagAction(), true);
    assert.deepEqual(clone(env.requests[1].data), pending);
    assert.equal(env.requests[0].url, '/api/play/theme/gameplay-action');
    assert.deepEqual(env.wxCalls, ['login', 'werun']);
    assert.deepEqual(pending.weRun, { episodeId: fixtures.personal.zeroHp.episodeId, code: 'test-login-code', encryptedData: 'test-cipher', iv: 'test-iv' });
    assert.equal('steps' in pending, false); assert.equal('steps' in pending.weRun, false);
    assert.equal(JSON.stringify(env.page.data).includes('test-cipher'), false);
    assert.equal(env.page.data.zeroHpView.progress, 0);
  });
}

for (const msg of ['微信运动解密失败，请重新同步', '微信运动数据已过期，请重新同步', '步数数据不能倒退']) {
  test('服务器拒绝保留进度和原文，重新授权用新 actionId：' + msg, async () => {
    const env = load({ ...fixtures.personal, zeroHp: { ...fixtures.personal.zeroHp, phase: 'RECOVERING', progress: 420 } });
    env.respond(() => ({ code: 409, msg }));
    assert.equal(await env.page.syncZeroHpSteps(), false);
    assert.equal(env.page.data.themeBagError, msg); assert.equal(env.page.data.zeroHpView.progress, 420);
    assert.equal(env.page._gameplayPending, null);
    await env.page.syncZeroHpSteps();
    assert.notEqual(env.requests[0].data.actionId, env.requests[1].data.actionId);
  });
}

test('服务端恢复成功关闭 episode，HP/used 原样回读，重复收据不叠加', async () => {
  const env = load(), restored = { ...clone(fixtures.personal), version: 9, hp: 5,
    zeroHp: { ...fixtures.personal.zeroHp, phase: 'HEALTHY', used: 2, progress: 2000 } };
  env.respond(() => ({ code: 200, data: restored }));
  assert.equal(await env.page.syncZeroHpSteps(), true);
  assert.equal(env.page.data.themeBag.hp, 5); assert.equal(env.page.data.zeroHpView, null);
  env.page._applyThemeView(restored); env.page._applyThemeView(restored);
  assert.equal(env.page.data.themeBag.hp, 5); assert.equal(env.page.data.themeBag.zeroHp.used, 2);
  assert.equal(await env.page.syncZeroHpSteps(), false);
});

test('旧回执重放后刷新断网，不能覆盖已确认的新进度或恢复后的 HP', async () => {
  const env = load(); let calls = 0;
  env.respond(() => ++calls === 1 ? { netFail: true } : { code: 200, data: { ...clone(fixtures.personal), version: 9 } });
  await env.page.syncZeroHpSteps();
  env.page._applyThemeView({ ...clone(fixtures.personal), version: 10, hp: 5, zeroHp: { ...fixtures.personal.zeroHp, phase: 'HEALTHY', used: 2, progress: 2000 } });
  env.page.refreshThemeBackpack = async () => false;
  assert.equal(await env.page.retryThemeBagAction(), true);
  assert.equal(env.page.data.themeBag.version, 10); assert.equal(env.page.data.themeBag.hp, 5);
  assert.equal(env.page.data.zeroHpView, null);
});

test('shared 步数命令有 requestId/revision/epoch，网络重试同请求且只读取服务端进度', async () => {
  const env = load(), team = clone(fixtures.shared);
  team.player.zeroHp.allowedActions = ['ZERO_HP_STEPS', 'ZERO_HP_SYNC'];
  team.player.zeroHp.disabledReason = '';
  env.page._applyThemeTeamView(team.player, team);
  let calls = 0;
  env.respond(options => ++calls === 1 ? { netFail: true } : { code: 200, data: {
    receiptId: 2, activityId: 5, action: 'ZERO_HP_STEPS', requestId: options.data.requestId, revision: 13, outcome: 'APPLIED', replayed: true, result: {} } });
  env.page.refreshThemeBackpack = async () => { env.page._applyThemeTeamView({ ...team.player, zeroHp: { ...team.player.zeroHp, progress: 600 } }, { ...team, revision: 13 }); return true; };
  assert.equal(await env.page.syncZeroHpSteps(), false);
  assert.equal(await env.page.retryThemeBagAction(), true);
  assert.deepEqual(env.requests[1].data, env.requests[0].data);
  const command = env.requests[0].data;
  assert.equal(env.requests[0].url, '/api/game/session/command');
  assert.equal(command.expectedRevision, 12); assert.equal(command.authorityEpoch, team.player.authorityEpoch);
  assert.deepEqual(Object.keys(command.payload), ['weRun']);
  assert.equal(env.page.data.zeroHpView.progress, 600);
});

test('基线成员退出，原成员同步禁用，当前队长按新回读重立基线且不清 used', () => {
  const { page } = load(), team = clone(fixtures.shared);
  page._applyThemeTeamView(team.player, team);
  const next = recorded('team-steps-baseline-left-new-captain');
  page._applyThemeTeamView(next, { ...team, revision: 13 });
  assert.equal(page.data.zeroHpView.memberText, '新队长'); assert.equal(page.data.zeroHpView.canSteps, true);
  assert.equal(page.data.zeroHpView.progress, next.zeroHp.progress); assert.equal(page.data.zeroHpView.used, next.zeroHp.used);
});

test('九个真实 MySQL 回读摘录进入真实 Page，HP/恢复字段原样保留', () => {
  for (const name of ['personal-steps-awaiting-baseline', 'personal-steps-recovering', 'team-wait', 'team-steps-awaiting-baseline', 'team-steps-recovering', 'team-steps-member-disabled', 'team-steps-baseline-left-new-captain', 'team-healthy', 'team-hp-zero-result-card']) {
    const env = load(), view = clone(recorded(name));
    if (name.startsWith('personal')) env.page._applyThemeView(view);
    else env.page._applyThemeTeamView(view, fixtures.shared);
    assert.equal(env.page.data.themeBag.hp, view.hp, name);
    assert.deepEqual(env.page.data.themeBag.zeroHp, view.zeroHp, name);
    if (view.zeroHp.phase === 'HEALTHY') assert.equal(env.page.data.zeroHpView, null);
    else {
      assert.equal(env.page.data.zeroHpView.canSteps, view.zeroHp.allowedActions.includes('ZERO_HP_STEPS'), name);
      assert.equal(env.page.data.zeroHpView.canSync, view.zeroHp.allowedActions.includes('ZERO_HP_SYNC'), name);
      assert.equal(env.page.data.zeroHpView.disabledReason, view.zeroHp.disabledReason, name);
    }
    assert.equal(env.requests.length, 0, name);
  }
});

test('普通计步入口在归零时按会员恢复授权路由，绝不 SUBMIT_STEPS；恢复后普通入口仍可用', async () => {
  const env = load(), ordinary = [];
  env.page.selectComponent = () => ({ action: (...args) => ordinary.push(args) });
  env.page.setData({ 'playKit.kit': { type: 'walk' } });
  env.respond(() => ({ code: 200, data: recorded('personal-steps-recovering') }));
  env.page._submitPlayKitAction('sync', {});
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(env.requests[0].data.operation, 'ZERO_HP_STEPS');
  assert.equal(ordinary.length, 0);
  env.page._applyThemeTeamView(fixtures.shared.player, fixtures.shared);
  const before = env.wxCalls.length;
  assert.equal(await env.page._submitSteps(), false);
  assert.equal(env.wxCalls.length, before); assert.equal(ordinary.length, 0);
  env.page._applyThemeTeamView(recorded('team-healthy'), fixtures.shared);
  env.page._submitSteps();
  assert.equal(ordinary.length, 1); assert.equal(ordinary[0][0], 'SUBMIT_STEPS');
});

test('跨日与超时消息仅用服务器回读，CHALLENGE 失败与 thenPolicy 不由本地推断', () => {
  const env = load(), message = '步数基线已跨日，请重新同步微信运动';
  env.page._applyThemeView({ ...fixtures.personal, zeroHp: { ...fixtures.personal.zeroHp, message, phase: 'AWAITING_BASELINE' } });
  assert.equal(env.page.data.zeroHpView.message, message); assert.equal(env.page.data.zeroHpView.progress, fixtures.personal.zeroHp.progress);
  env.page._applyThemeView({ ...fixtures.personal, zeroHp: { ...fixtures.personal.zeroHp, policy: 'CHALLENGE', effectivePolicy: 'CHALLENGE', phase: 'RECOVERING', progress: 2, required: 3, serverNow: 1000, deadlineAt: 2000 } });
  assert.equal(env.page.data.zeroHpView.progressText, '2 / 3');
  env.page._applyThemeView({ ...fixtures.personal, zeroHp: { ...fixtures.personal.zeroHp, policy: 'CHALLENGE', effectivePolicy: 'BLOCK_HP_COST', phase: 'BLOCKED', thenPolicy: 'BLOCK_HP_COST', allowedActions: [], message: '挑战超时，暂停消耗任务' } });
  assert.equal(env.page.data.zeroHpView.title, '暂停消耗任务');
  assert.equal(env.page.data.zeroHpView.thenText, '暂停消耗任务');
  assert.equal(env.page.data.zeroHpView.message, '挑战超时，暂停消耗任务');
  assert.equal(env.page.data.themeBag.hp, 0);
});

test('真实共享归零结果卡可单独触发既有结果屏，不要求原始 result 字段', () => {
  const env = load(); env.page._themeHidden = false;
  env.page._applyThemeTeamView(recorded('team-hp-zero-result-card'), fixtures.shared);
  assert.equal(env.page.data.themeResultCard.title, recorded('team-hp-zero-result-card').resultCard.title);
  assert.equal(env.page.data.themeBackpackOpen, false); assert.equal(env.requests.length, 0);
});

test('次数用尽使用服务器 effectivePolicy，挑战 v2 用完整 PASS 次数而非 hits', () => {
  const { page } = load();
  page._applyThemeView({ ...fixtures.personal, zeroHp: { ...fixtures.personal.zeroHp, phase: 'BLOCKED', used: 2, effectivePolicy: 'BLOCK_HP_COST', allowedActions: [] } });
  assert.equal(page.data.zeroHpView.title, '暂停消耗任务');
  assert.equal(page.data.zeroHpView.usedText, '已恢复 2 / 2 次');
  assert.equal(page.data.zeroHpView.canSteps, false);
  page._applyThemeView({ ...fixtures.personal, awaitingChallenge: true, challenge: { nodeId: 41, progress: 9, goal: 10 },
    zeroHp: { ...fixtures.personal.zeroHp, policy: 'CHALLENGE', effectivePolicy: 'CHALLENGE', phase: 'RECOVERING', progress: 2, required: 3 } });
  assert.equal(page.data.zeroHpView.progressText, '2 / 3');
  page.openThemeChallenge({ currentTarget: { dataset: { id: 41 } } });
  assert.equal(page.data.advancedPlay.node.nodeId, 41);
});

test('WAIT 只按 serverNow 估算，到点同 episode 同步一次，不在客户端恢复 HP', async () => {
  const env = load({ ...fixtures.personal, zeroHp: { ...fixtures.personal.zeroHp, policy: 'WAIT', effectivePolicy: 'WAIT', phase: 'RECOVERING', serverNow: 5000, deadlineAt: 6500, allowedActions: ['ZERO_HP_SYNC'] } });
  let localNow = 987654321, calls = 0;
  const now = Date.now; Date.now = () => localNow;
  try {
    env.page._applyZeroHpView(env.page.data.themeBag);
    env.respond(() => { calls++; return { netFail: true }; });
    env.page._tickZeroHpClock(); assert.match(env.page.data.zeroHpCountdown, /2秒/);
    localNow += 2000; env.page._tickZeroHpClock();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(calls, 1); assert.equal(env.page.data.themeBag.hp, 0);
    env.page._tickZeroHpClock(); assert.equal(calls, 1);
    assert.equal(env.requests[0].data.operation, 'ZERO_HP_SYNC');
    await env.page.retryThemeBagAction();
    assert.equal(env.requests[0].data.actionId, env.requests[1].data.actionId);
  } finally { Date.now = now; }
});

test('页面离开时清计时器，迟到微信回调不发送旧 episode', async () => {
  const env = load(); let login;
  wx.login = options => { login = options; };
  const pending = env.page.syncZeroHpSteps();
  env.page._themeEpoch = 1;
  login.success({ code: 'late-code' });
  assert.equal(await pending, false); assert.equal(env.requests.length, 0);
  const clear = global.clearInterval, cleared = [];
  global.clearInterval = id => cleared.push(id);
  try { env.page._zeroHpTimer = 77; env.page._stopZeroHpTimer(); assert.deepEqual(cleared, [77]); assert.equal(env.page._zeroHpTimer, null); }
  finally { global.clearInterval = clear; }
});

test('THEME_FAIL 个人走既有 card API，共享直接使用服务器白名单 resultCard', async () => {
  const env = load(); env.page._themeHidden = false;
  const failed = { ...clone(fixtures.personal), status: 'ENDED', result: { reason: 'HP_ZERO', success: false, title: '本局结束', hp: 99 },
    zeroHp: { ...fixtures.personal.zeroHp, effectivePolicy: 'THEME_FAIL', phase: 'BLOCKED', allowedActions: [] } };
  env.respond(options => ({ code: 200, data: options.url.endsWith('/card') ? { title: '本局结束', success: false, reason: 'HP_ZERO' } : failed }));
  env.page._applyThemeView(failed);
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(env.requests[0].url, '/api/play/theme/card');
  assert.equal(env.page.data.themeResultCard.title, '本局结束');
  assert.deepEqual(env.page.data.themeResultCard.valueRows, []);
  env.page._applyThemeView(failed);
  assert.equal(env.requests.filter(row => row.url.endsWith('/card')).length, 1);

  const teamEnv = load(); teamEnv.page._themeHidden = false;
  const team = clone(fixtures.shared);
  team.player.result = failed.result;
  team.player.resultCard = { title: '本队结束', success: false, reason: 'HP_ZERO', hp: 0, hpName: '体温' };
  team.player.zeroHp = failed.zeroHp;
  teamEnv.page._applyThemeTeamView(team.player, team);
  assert.equal(teamEnv.page.data.themeResultCard.title, '本队结束');
  assert.deepEqual(teamEnv.page.data.themeResultCard.valueRows, [{ name: '体温', value: 0 }]);
  assert.equal(teamEnv.requests.length, 0);
  assert.equal(teamEnv.page.data.themeRecordsState, null);
});

test('微信登录失败、取数网络失败或缺少密文时不提交、不损失进度', async () => {
  for (const failure of ['login', 'network', 'cipher']) {
    const env = load();
    if (failure === 'login') wx.login = options => options.fail();
    if (failure === 'network') wx.getWeRunData = options => options.fail({ errMsg: 'getWeRunData:fail network error' });
    if (failure === 'cipher') wx.getWeRunData = options => options.success({ iv: 'test-iv' });
    assert.equal(await env.page.syncZeroHpSteps(), false);
    assert.match(env.page.data.zeroHpError, /重试/);
    assert.equal(env.page.data.zeroHpPermissionDenied, false);
    assert.equal(env.requests.length, 0); assert.equal(env.page.data.zeroHpView.progress, 0);
  }
});

test('真实 Page onHide/onUnload 清恢复与轮询 timer，回到页面重启显示时钟', () => {
  const env = load(), page = env.page, cleared = [], clear = global.clearInterval;
  global.clearInterval = id => cleared.push(id);
  for (const method of ['_stopStoryDust', '_clearPackTimers', 'pauseAudio', '_stopShopNpcVoice', '_stopLeadPoll', '_pauseSessionClock', '_persistRunSession', 'destroyAudio', '_stopTracking', '_destroySessionClock', '_clearTransientUiTimers', '_cancelRunHold']) page[method] = () => {};
  try {
    page._zeroHpTimer = 'zero-hide'; page._themeTimer = 'poll-hide'; page.onHide();
    page._zeroHpTimer = 'zero-unload'; page._themeTimer = 'poll-unload'; page.onUnload();
    assert.deepEqual(cleared, ['poll-hide', 'zero-hide', 'poll-unload', 'zero-unload']);
    let restarted = false; page._startZeroHpTimer = () => { restarted = true; };
    page._startThemePoll = () => {};
    page.data.loading = true; page.onShow(); assert.equal(restarted, true);
    page._stopThemePoll();
  } finally { global.clearInterval = clear; }
});
