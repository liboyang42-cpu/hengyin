/* V6b 验收（SC-UI-DATE-COUNT / F-R2S-13）：导演台「暂停本站」的 resumeEta 必须按中国时区算，
 * 设备在 UTC-5 / UTC+9 / UTC 时发出的 yyyy-MM-dd HH:mm:00 与 UTC+8 设备完全一致。
 * 时区在进程启动时就定了，所以每个时区起一个子进程跑真实的 submitIncident → modal 确认 → executeAction。
 * 负控：把 resumeEtaIn 改回 new Date(...).getHours()/getDate() 这类本地字段，非上海时区的用例必红。 */
const { test } = require('node:test')
const assert = require('node:assert/strict')
const path = require('node:path')
const { execFileSync } = require('node:child_process')

const DIRECTOR_PATH = path.resolve(__dirname, '../../pages/club/topic-detail/director.js')
// 2026-10-19T15:45:00Z = 北京时间 23:45，加 30 分钟跨到 10-20 00:15，正好能暴露跨日的本地字段写法
const NOW = Date.parse('2026-10-19T15:45:00Z')

const CHILD = `
const DIRECTOR_PATH = ${JSON.stringify(DIRECTOR_PATH)}
Date.now = () => ${NOW}
const actions = []
let modal = null
global.wx = { showModal(v) { modal = v }, showToast() {} }
global.getApp = () => ({ getUserID: () => '7', getAuthorization: () => 'unit-session' })
const { DIRECTOR_METHODS } = require(DIRECTOR_PATH)
const ctx = {
  ...DIRECTOR_METHODS,
  _activityId: 55,
  data: { writeLocked: false, incidentSelectedKey: 'backlog:8', incidentMode: 'pause',
    incidentRows: [{ id: 'backlog:8', key: 'backlog:8', kind: 'BACKLOG', nodeId: 8, planOptions: [] }] },
  setData(patch) { Object.assign(this.data, patch) },
  executeAction(action, payload, nodeId) { actions.push({ action, payload, nodeId }) },
}
global.getCurrentPages = () => [ctx]
DIRECTOR_METHODS.submitIncident.call(ctx)
modal.success({ confirm: true, content: '设备故障' })
process.stdout.write(JSON.stringify(actions))
`

function runIn(zone) {
  const out = execFileSync(process.execPath, ['-e', CHILD], { env: Object.assign({}, process.env, { TZ: zone }), encoding: 'utf8' })
  return JSON.parse(out)
}

test('V6b SC-UI-DATE-COUNT：暂停本站的预计恢复时间在任何设备时区都按北京时间发送', () => {
  const expected = '2026-10-20 00:15:00'
  for (const zone of ['Asia/Shanghai', 'America/New_York', 'Asia/Tokyo', 'UTC']) {
    const actions = runIn(zone)
    assert.equal(actions.length, 1, zone + ' 必须真的发出暂停')
    assert.equal(actions[0].action, 'CLUB_STATION_PAUSE')
    assert.equal(actions[0].nodeId, 8)
    assert.equal(actions[0].payload.resumeEta, expected, zone)
  }
})
