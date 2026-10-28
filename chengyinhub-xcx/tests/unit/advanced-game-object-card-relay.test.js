/* 拍物成卡:判过这一次的回包带着那张卡(后端 view.objectCard),必须经 cy-advanced-game 一路抛给页面。
 *
 * 2026-09-24 审查实证:emitSession 只往上抛 sessionId / version / playKit / vars 四个键,
 * objectCard 在这一层被丢掉 —— 页面拿到的恒为 undefined,当场那一屏永远只说「这张过了」,
 * 而契约测试直接把 objectCard 喂给 pickPlayKit,绕过了这一层,所以一直绿着。
 * 这里走真 action → 真 hydrate → 真 emitSession,只桩网络。
 *
 * 另一条:提交明确没成(4xx 拒绝 / 409 状态已更新)时抛 actionfail,
 * 页面据此让拍物成卡那一屏回到取景 —— 否则玩家对着「处理中」干等 45 秒。
 * 结果未知(5xx 通用错误 / 响应丢失)只按原请求号读 /api/play/advanced/receipt,
 * 查不到不等于没写入:保持 unknown 并在组件上常驻核对入口,不冒充确定失败
 * (与 advanced-game-receipt-contract.test.js 同一契约)。 */
const assert = require('node:assert/strict')
const test = require('node:test')

const COMPONENT_PATH = require.resolve('../../pages/play/components/advanced-game/index.js')
let definition
{
  const previousComponent = global.Component
  const previousGetApp = global.getApp
  global.Component = (options) => { definition = options }
  global.getApp = () => ({ sendRequest() {}, getUserID: () => 7 })
  delete require.cache[COMPONENT_PATH]
  require(COMPONENT_PATH)
  global.Component = previousComponent
  global.getApp = previousGetApp
}

const STATE = {
  sessionId: 42, version: 3, status: 'RUNNING', draws: [],
  config: { random: { enabled: false, drawCount: 0, items: [] }, branch: { enabled: false, steps: [] },
    multiplayer: { enabled: false, roles: [], requiredTurns: 1 }, leaderboard: { enabled: false }, timer: { enabled: false } },
  multiplayer: { roles: {}, members: [], completedUnitIds: [], turnIndex: 0 },
}

function createGame(queue) {
  // 两侧原请求均通过真实存储合同；照片只接受本站媒体引用。
  const events = []
  const disk = new Map()
  global.wx = { getStorageSync: key => disk.get(key), setStorageSync: (key, value) => disk.set(key, JSON.parse(JSON.stringify(value))), removeStorageSync: key => disk.delete(key) }
  const instance = {
    properties: {},
    data: Object.assign(JSON.parse(JSON.stringify(definition.data)), { node: { nodeId: 7 }, state: JSON.parse(JSON.stringify(STATE)) }),
    setData(update, cb) { Object.assign(this.data, update); if (cb) cb() },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    syncTimer() {}, stopTimer() {}, loadLeaderboard() {},
  }
  Object.assign(instance, definition.methods)
  instance.calls = []
  instance.request = function (url, method, data) {
    instance.calls.push({ url, method, data })
    const next = queue.shift()
    if (!next) return Promise.reject(new Error('unexpected request'))
    return next.err ? Promise.reject(next.err) : Promise.resolve(next.res)
  }
  instance.events = events
  return instance
}

const view = (version, extra) => Object.assign(JSON.parse(JSON.stringify(STATE)), { version }, extra || {})
const CARD = { id: 61, title: '半个牛油果', cutoutUrl: 'https://cdn/c.png', cutoutBox: [0.1, 0.2, 0.5, 0.5] }
const flush = () => new Promise(setImmediate)

test('原照片版本被明确拒绝时仍抛 actionfail，不把确定未执行当 UNKNOWN', async () => {
  const rejected = createGame([{ res: { code: 409, msg: '原版本已过期', data: { intentNotExecuted: true } } }])
  await rejected.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' })
  assert.deepEqual(rejected.events.filter(e => e.name === 'actionfail').map(e => e.detail), [{ action: 'SUBMIT_PHOTO_CHECK', sessionId: 42 }])
  assert.equal(rejected.data.unknown, false)
  assert.equal(rejected.calls.filter(c => c.method === 'POST').length, 1)
})

test('★判过的回包带着卡:session 事件把 objectCard 原样抛给页面', async () => {
  const game = createGame([{ res: { code: 200, data: view(4, { objectCard: CARD }) } }])
  await game.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' })
  const session = game.events.filter((e) => e.name === 'session').pop()
  assert.ok(session, '没有抛 session 事件')
  assert.deepEqual(session.detail.objectCard, CARD, 'objectCard 在 emitSession 这一层被丢了:当场那一屏永远出不了卡')
})

test('回包没带卡(老模板 / 没判过 / 回读):objectCard 为 null,不是 undefined', async () => {
  const game = createGame([{ res: { code: 200, data: view(4) } }])
  await game.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' })
  assert.equal(game.events.filter((e) => e.name === 'session').pop().detail.objectCard, null)
})

test('★提交被服务端明确拒绝(4xx):抛 actionfail,带上动作名', async () => {
  const game = createGame([{ res: { code: 400, msg: '照片不符合要求', data: { intentNotExecuted: true } } }])
  await game.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' })
  assert.deepEqual(game.events.filter((e) => e.name === 'actionfail').map((e) => e.detail), [{ action: 'SUBMIT_PHOTO_CHECK', sessionId: 42 }])
  assert.equal(game.data.unknown, false)
  assert.equal(game.data.acting, false)
})

test('状态已更新(409 带权威视图):换上视图也抛 actionfail(这一步没提交)', async () => {
  const game = createGame([{ res: { code: 409, msg: '状态已更新，请刷新后重试', data: { intentNotExecuted: true, view: view(5) } } }])
  await game.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' })
  await flush()
  assert.equal(game.events.filter((e) => e.name === 'actionfail').length, 1)
  assert.equal(game.data.state.version, 5)
})

test('结果未知(5xx 通用错误 / 响应丢失):只读原请求回执;查不到或读失败都保持 unknown,不冒充失败', async () => {
  for (const first of [{ res: { code: 500, msg: '服务繁忙' } }, { err: new Error('timeout') }]) {
    const notFound = createGame([first, { res: { code: 404, msg: '尚未查到原请求回执，结果仍待确认' } }])
    await notFound.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' })
    await flush(); await flush()
    const original = notFound.calls[0].data
    assert.deepEqual(notFound.calls[1], { url: '/api/play/advanced/receipt', method: 'GET',
      data: { sessionId: 42, idempotencyKey: original.idempotencyKey } })
    assert.equal(notFound.data.unknown, true, '查不到原回执不代表没写入')
    assert.match(notFound.data.unknownText, /未查到不代表未提交/, '组件上常驻核对提示，那一屏不是无声转圈')
    assert.equal(notFound.events.filter((e) => e.name === 'actionfail').length, 0)

    const readbackDown = createGame([first, { err: new Error('timeout') }])
    await readbackDown.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' })
    await flush(); await flush()
    assert.equal(readbackDown.data.unknown, true, '回执也读不到,保持锁定')
    assert.equal(readbackDown._unknownCommand.version, 3)
    assert.equal(readbackDown.events.filter((e) => e.name === 'actionfail').length, 0)
  }
})

test('负控:响应丢失但原请求回执证明已写进去 → 不抛 actionfail(那是成功)', async () => {
  const receipt = (data) => ({ code: 200, data: { sessionId: '42', idempotencyKey: data.idempotencyKey, view: view(4) } })
  const game = createGame([{ err: new Error('timeout') }])
  const request = game.request
  game.request = function (url, method, data) {
    if (url === '/api/play/advanced/receipt') { game.calls.push({ url, method, data }); return Promise.resolve(receipt(data)) }
    return request.call(this, url, method, data)
  }
  await game.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' })
  await flush(); await flush()
  assert.equal(game.events.filter((e) => e.name === 'actionfail').length, 0)
  assert.equal(game.data.unknown, false)
  assert.equal(game.data.state.version, 4)
})

for (const withCard of [true, false]) {
  test(`迟到成功动作回执不回退快照，保留有效成功${withCard ? '及当次卡片' : '且不制造卡片'}`, async () => {
    const game = createGame([]);
    let finish;
    game.request = () => new Promise(resolve => { finish = resolve; });
    const old = game.action('SUBMIT_PHOTO_CHECK', { imageUrl: 'media-asset:701' });
    game.hydrate(view(5, { vars: { stage: 'latest' }, readyForBase: true,
      playKit: { photoCheck: { title: '原玩法', passed: true } } }));
    const current = game.data.state;
    game.events.length = 0;
    finish({ code: 200, data: view(4, Object.assign({ vars: { stage: 'old' }, readyForBase: false }, withCard ? { objectCard: CARD } : {})) });
    assert.equal(await old, true);
    assert.equal(game.data.state, current);
    assert.equal(game.data.acting, false);
    assert.equal(game.data.unknown, false);
    assert.equal(game.events.filter(e => e.name === 'actionfail').length, 0);
    const sessions = game.events.filter(e => e.name === 'session');
    assert.equal(sessions.length, withCard ? 1 : 0);
    if (withCard) {
      assert.deepEqual(sessions[0].detail.objectCard, CARD);
      assert.equal(sessions[0].detail.version, 5);
      assert.deepEqual(sessions[0].detail.vars, { stage: 'latest' });
      assert.equal(sessions[0].detail.readyForBase, true);
      assert.equal(current.objectCard, undefined, 'receipt card must not mutate authoritative latest state');
      const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js');
      const kit = pickPlayKit(sessions[0].detail, new Date());
      assert.equal(kit.type, 'photocheck');
      assert.equal(kit.card.title, CARD.title);
      assert.equal(kit.passed, true);
    }
  });
}
