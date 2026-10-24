// V6b 验收（SC-CITY-PROGRESS 客户端 / F-L3-07）：_loadPlayerGameModule 发出后，页面切到另一场活动或换了账号，
// 旧回包必须整个丢弃；同一局更低 revision 不得回退。play-player-projection-order.test.js 已覆盖令牌和 revision，
// 这里补「activityId / memberId 快照」两条：负控把 currentRequest 里的 activityId 或 memberId 比较删掉，对应用例必红。
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const PAGE = path.resolve(__dirname, '../../pages/play/index.js');
const CLIENT = path.resolve(__dirname, '../../utils/game-session-client.js');
const tick = () => new Promise(resolve => setImmediate(resolve));

function projection(activityId, revision) {
  return {
    status: 'ready',
    data: {
      perspective: 'PLAYER', sessionId: activityId * 10, activityId, revision, status: 'RUNNING',
      availableActions: [], player: { teamId: 5, nodes: [], role: {}, story: {} },
    },
  };
}

function harness() {
  let definition;
  const loads = [];
  const identity = { member: 7 };
  const app = { getUserID: () => identity.member, getAuthorization: () => 'token', sendRequest() {} };
  global.getApp = () => app; global.getCurrentPages = () => [];
  global.Page = value => { definition = value; };
  global.wx = { getStorageSync() { return null; }, removeStorageSync() {}, setStorageSync() {},
    nextTick(fn) { if (fn) fn(); }, showToast() {} };
  delete require.cache[PAGE]; delete require.cache[CLIENT];
  require(PAGE);
  require(CLIENT).loadProjection = (perspective, activityId) => new Promise(resolve => { loads.push({ activityId, resolve }); });
  const page = {
    data: { ...definition.data, activityId: 100, nodes: [], gameModule: { ...(definition.data.gameModule || {}) } },
    setData(patch, cb) {
      for (const [key, value] of Object.entries(patch)) {
        const parts = key.split('.'); let at = this.data;
        for (const part of parts.slice(0, -1)) { if (!at[part] || typeof at[part] !== 'object') at[part] = {}; at = at[part]; }
        at[parts.at(-1)] = value;
      }
      if (cb) cb();
    },
  };
  for (const [name, value] of Object.entries(definition)) if (typeof value === 'function') page[name] = () => {};
  page._loadPlayerGameModule = definition._loadPlayerGameModule;
  page._playerPendingKey = () => '';
  return { page, loads, identity };
}

test('V6b SC-CITY-PROGRESS：请求发出后切到另一场活动，旧活动的投影不写入当前页', async () => {
  const { page, loads } = harness();
  page._loadPlayerGameModule();
  assert.equal(loads[0].activityId, 100);
  page.data.activityId = 200; // 同一页实例被复用到另一场，但还没重新拉
  loads[0].resolve(projection(100, 9));
  await tick();
  assert.notEqual(page.data.gameModule.sessionId, 1000, '旧活动的 session 不得写进新活动页面');
  assert.notEqual(page.data.gameModule.revision, 9);
});

test('V6b SC-CITY-PROGRESS：请求发出后换了账号，上一个账号的投影不写入当前页', async () => {
  const { page, loads, identity } = harness();
  page._loadPlayerGameModule();
  identity.member = 8;
  loads[0].resolve(projection(100, 9));
  await tick();
  assert.notEqual(page.data.gameModule.revision, 9, '旧账号的投影必须丢弃');
});

test('V6b SC-CITY-PROGRESS：同活动同账号的正常回包照常生效（对照组）', async () => {
  const { page, loads } = harness();
  page._loadPlayerGameModule();
  loads[0].resolve(projection(100, 9));
  await tick();
  assert.equal(page.data.gameModule.revision, 9);
  assert.equal(page.data.gameModule.sessionId, 1000);
});
