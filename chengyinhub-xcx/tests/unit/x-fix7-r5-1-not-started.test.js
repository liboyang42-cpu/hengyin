const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const topbar = require('../../pages/play/utils/play-topbar.js')
const { createGameSessionClient } = require('../../utils/game-session-client.js')

// x-fix7 R5-1：局还没开始 / 没准备是预期业务态，后端不再回 500；小程序显示既有等待态而不是报错。

test('casebook 局未开始回空数据：不出案件册入口，打开显示「还没有记下什么」而非错误', async () => {
  const empty = { code: 200, data: { facts: [], relations: [], costs: [], log: [] } }
  const req = () => Promise.resolve(empty)
  const page = Object.assign({ data: Object.assign({ topicId: 9, activityId: 0, nodes: [], themeBag: {} }, JSON.parse(JSON.stringify(topbar.data))),
    setData(patch) { for (const k of Object.keys(patch)) { const keys = k.split('.'); let o = this.data; while (keys.length > 1) o = o[keys.shift()]; o[keys[0]] = patch[k] } } }, topbar.methods(req))
  page._probeCasebook(); await new Promise(r => setImmediate(r))
  assert.equal(page.data.hasCasebook, false)
  page.openCasebook(); await new Promise(r => setImmediate(r))
  assert.equal(page.data.casebook.error, '')
  assert.equal(page.data.casebook.loading, false)
  assert.equal(page.data.casebook.facts.length, 0)
})

test('session/view 局未准备回 601 + reasonCode：客户端给出 reasonCode，play 页按「缺席」处理不报错', async () => {
  const calls = []
  const client = createGameSessionClient({ sendRequest(o) { calls.push(o); return { abort() {} } } })
  const pending = client.loadProjection('player', 71)
  calls[0].success({ code: 601, msg: '本场游戏尚未准备', data: { reasonCode: 'GAME_SESSION_NOT_PREPARED' } })
  const result = await pending
  assert.equal(result.status, 'business-error')
  assert.equal(result.reasonCode, 'GAME_SESSION_NOT_PREPARED')
  const js = fs.readFileSync(path.join(__dirname, '../../pages/play/index.js'), 'utf8')
  assert.match(js, /\['GAME_SESSION_NOT_FOUND', 'GAME_SESSION_NOT_PREPARED', 'GAME_MODULE_DISABLED'\]\.includes\(result\.reasonCode\)/)
  assert.match(js, /'gameModule\.error': absent \? ''/)
})
