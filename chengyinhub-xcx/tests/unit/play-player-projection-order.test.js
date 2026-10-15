// 玩家端游戏局投影读取：晚到的旧请求或更低 revision 的投影不能覆盖已经展示的新状态。
const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const PAGE = path.resolve(__dirname, '../../pages/play/index.js');
const CLIENT = path.resolve(__dirname, '../../utils/game-session-client.js');
const tick = () => new Promise(resolve => setImmediate(resolve));

function projection(revision, extra = {}) {
  return {
    status: 'ready',
    data: Object.assign({
      perspective: 'PLAYER', sessionId: 31, activityId: 100, revision, status: 'RUNNING',
      availableActions: [], player: { teamId: 5, nodes: [], role: {}, story: {} },
    }, extra),
  };
}

function harness() {
  let definition;
  const pendingLoads = [];
  const app = { getUserID: () => 7, getAuthorization: () => 'token', sendRequest() {} };
  global.getApp = () => app; global.getCurrentPages = () => [];
  global.Page = value => { definition = value; };
  global.wx = { getStorageSync() { return null; }, removeStorageSync() {}, setStorageSync() {},
    nextTick(fn) { if (fn) fn(); }, showToast() {} };
  delete require.cache[PAGE]; delete require.cache[CLIENT];
  require(PAGE);
  require(CLIENT).loadProjection = () => new Promise(resolve => { pendingLoads.push(resolve); });
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
  return { page, pendingLoads };
}

test('F-L3-07：先发后到的旧请求不能覆盖后发先到的新投影', async () => {
  const { page, pendingLoads } = harness();
  page._loadPlayerGameModule();
  page._loadPlayerGameModule();
  assert.equal(pendingLoads.length, 2);
  pendingLoads[1](projection(9));
  await tick();
  assert.equal(page.data.gameModule.revision, 9);
  pendingLoads[0](projection(7));
  await tick();
  assert.equal(page.data.gameModule.revision, 9, '旧请求晚到必须丢弃');
});

test('F-L3-07：同一局更低 revision 的投影不回退已展示状态，更高 revision 正常推进', async () => {
  const { page, pendingLoads } = harness();
  page._loadPlayerGameModule();
  pendingLoads[0](projection(12));
  await tick();
  assert.equal(page.data.gameModule.revision, 12);
  page._loadPlayerGameModule();
  pendingLoads[1](projection(10));
  await tick();
  assert.equal(page.data.gameModule.revision, 12, '同 session 的更低 revision 不得覆盖');
  assert.equal(page.data.gameModule.loading, false);
  page._loadPlayerGameModule();
  pendingLoads[2](projection(13));
  await tick();
  assert.equal(page.data.gameModule.revision, 13);
});
