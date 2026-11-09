/* P-10/P-11 第四轮(rig 实测):作者「结果反馈」判完那一次的回包带 outcomeFeedback,
 * 必须经 cy-advanced-game 的 session 事件抛给页面。第三轮只在 pickPlayKit 上测了它(直接喂视图,
 * 绕过了这一层),rig 里题屏于是仍叠兜底句。这里走真 action → 真 hydrate → 真 emitSession,只桩网络。 */
const assert = require('node:assert/strict')
const test = require('node:test')
const { pickPlayKit } = require('../../pages/play/utils/playkit-view.js')

const COMPONENT_PATH = require.resolve('../../pages/play/components/advanced-game/index.js')
let definition
{
  const prevC = global.Component, prevA = global.getApp
  global.Component = (o) => { definition = o }
  global.getApp = () => ({ sendRequest() {}, getUserID: () => 7 })
  delete require.cache[COMPONENT_PATH]
  require(COMPONENT_PATH)
  global.Component = prevC; global.getApp = prevA
}

const STATE = {
  sessionId: 42, version: 0, status: 'RUNNING', present: 'inline',
  config: { schemaVersion: 1, outcomeRulesVersion: 1, random: { enabled: false, drawCount: 0, items: [] }, branch: { enabled: false, steps: [] },
    multiplayer: { enabled: false, roles: [], requiredTurns: 1 }, leaderboard: { enabled: false }, timer: { enabled: false } },
  multiplayer: { roles: {}, members: [], completedUnitIds: [], turnIndex: 0 },
  playKit: { qa: { mode: 'TYPE', title: '苏州河的旧称', options: [], attempts: 0, finished: false, passed: false } },
}

function createGame(queue) {
  const events = [], disk = new Map()
  global.wx = { getStorageSync: k => disk.get(k), setStorageSync: (k, v) => disk.set(k, JSON.parse(JSON.stringify(v))), removeStorageSync: k => disk.delete(k) }
  const inst = {
    properties: {},
    data: Object.assign(JSON.parse(JSON.stringify(definition.data)), { node: { nodeId: 82 }, state: JSON.parse(JSON.stringify(STATE)) }),
    setData(u, cb) { Object.assign(this.data, u); if (cb) cb() },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    syncTimer() {}, stopTimer() {}, loadLeaderboard() {},
  }
  Object.assign(inst, definition.methods)
  inst.request = () => { const n = queue.shift(); return n ? Promise.resolve(n.res) : Promise.reject(new Error('unexpected')) }
  inst.events = events
  return inst
}

const FEEDBACK = { outcome: 'FAIL', reply: '不是这个名字。去桥头看看那块石碑。', images: ['https://cdn/fail.jpg'] }

test('答错回包带作者的结果反馈:session 事件原样抛出,页面挑出的题卡 authoredOutcome=FAIL', async () => {
  const failed = Object.assign(JSON.parse(JSON.stringify(STATE)), { version: 1, status: 'FAILED', outcomeFeedback: FEEDBACK,
    playKit: { qa: { mode: 'TYPE', title: '苏州河的旧称', options: [], attempts: 1, finished: false, passed: false, lastCorrect: false } } })
  const game = createGame([{ res: { code: 200, data: failed } }])
  await game.action('SUBMIT_QA', { input: '黄浦江' })
  const session = game.events.filter(e => e.name === 'session').pop()
  assert.ok(session, '没有抛 session 事件')
  assert.deepEqual(session.detail.outcomeFeedback, FEEDBACK, 'outcomeFeedback 在 emitSession 这一层被丢了')
  assert.equal(pickPlayKit(session.detail, new Date()).authoredOutcome, 'FAIL')
})

test('判前 / 作者没写:outcomeFeedback 为 null,题卡不认作者反馈(负控)', async () => {
  const game = createGame([{ res: { code: 200, data: Object.assign(JSON.parse(JSON.stringify(STATE)), { version: 1 }) } }])
  await game.action('SUBMIT_QA', { input: '黄浦江' })
  const session = game.events.filter(e => e.name === 'session').pop()
  assert.equal(session.detail.outcomeFeedback, null)
  assert.equal(pickPlayKit(session.detail, new Date()).authoredOutcome, '')
})
