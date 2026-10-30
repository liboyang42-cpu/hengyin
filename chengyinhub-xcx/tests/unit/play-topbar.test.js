// 故事流顶栏(2026-10-30 业主定稿):罗盘⇄地图雷达、背包(打开定稿背包,不改它)、案件册、各站奖励、标题带主题名。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const topbar = require('../../pages/play/utils/play-topbar.js')

function host(responses) {
  const calls = []
  const req = (url, method, data) => { calls.push({ url, method, data }); return Promise.resolve(responses[url]) }
  const page = Object.assign({ data: Object.assign({ topicId: 9, activityId: 0, nodes: [{ nodeId: 1, name: '霍山路' }, { nodeId: 2, name: '名单墙' }], themeBag: { names: { hp: '保温储备' } } }, JSON.parse(JSON.stringify(topbar.data))),
    setData(patch) { for (const k of Object.keys(patch)) { const keys = k.split('.'); let o = this.data; while (keys.length > 1) o = o[keys.shift()]; o[keys[0]] = patch[k] } } }, topbar.methods(req))
  return { page, calls }
}
const flush = () => new Promise(r => setImmediate(r))

test('雷达:下一站在范围内交给地图画;超出范围给边上指针的角度(相对屏幕上方,随朝向转)', () => {
  const me = { lat: 31.2304, lng: 121.4737 }
  assert.equal(topbar.rimPointer(me, { lat: 31.2305, lng: 121.4737 }, 0), null, '十来米内不出指针')
  const north = { lat: 31.2404, lng: 121.4737 }
  assert.equal(topbar.rimPointer(me, north, 0), 0, '正北、人朝北 → 指针在正上方')
  assert.equal(topbar.rimPointer(me, north, 90), 270, '人朝东 → 正北的站在左边')
  const east = { lat: 31.2304, lng: 121.4837 }
  assert.ok(Math.abs(topbar.rimPointer(me, east, 0) - 90) <= 1, '正东 → 右边')
  assert.equal(topbar.rimPointer(null, north, 0), null)
})

test('案件册:有内容才在顶栏放这颗;打开时关系与日志按主题叫法排好', async () => {
  const empty = host({ '/api/play/journey/casebook': { code: 200, data: { facts: [], relations: [], costs: [], log: [] } } })
  empty.page._probeCasebook(); await flush()
  assert.equal(empty.page.data.hasCasebook, false)
  const full = host({ '/api/play/journey/casebook': { code: 200, data: {
    facts: [{ key: 'clue.a', label: '餐券编号连号' }], relations: [{ npcId: 3, name: '老人', state: 'ally' }], costs: [],
    log: [{ at: new Date(2026, 9, 30, 10, 42).getTime(), kind: 'node', text: '答对了', hpDelta: -1, luckDelta: 0 }], found: 3, total: 8 } } })
  full.page._probeCasebook(); await flush()
  assert.equal(full.page.data.hasCasebook, true)
  assert.deepEqual(full.calls[0], { url: '/api/play/journey/casebook', method: 'GET', data: { topicId: 9, activityId: undefined } })
  full.page.openCasebook(); await flush()
  const cb = full.page.data.casebook
  assert.equal(cb.relations[0].stateLabel, '信任')
  assert.equal(cb.log[0].time, '10:42')
  assert.equal(cb.log[0].delta, '保温储备 -1', '数值变化用主题自己的叫法')
  assert.equal(cb.found, 3)
})

test('各站奖励:只列有奖励的站,站名取自节点,状态说人话', async () => {
  const { page } = host({ '/api/play/journey/backpack': { code: 200, data: { nodes: [
    { nodeId: 1, reward: '救济餐券', status: 'pending', claimableAt: 1 }, { nodeId: 2, reward: '书签', status: 'claimed' },
    { nodeId: 3, reward: null, status: 'none' }, { nodeId: 2, reward: '咖啡', status: 'pending' }] } } })
  page.openJourneyRewards(); await flush()
  assert.deepEqual(page.data.journeyRewards.rows.map(r => [r.name, r.reward, r.state]),
    [['霍山路', '救济餐券', '待领取'], ['名单墙', '书签', '已领'], ['名单墙', '咖啡', '完成后可领']])
  assert.equal(page.data.sessionToolsOpen, false, '从「…」打开时先收起菜单')
})

test('读失败给原因与重试,不留空白', async () => {
  const { page } = host({ '/api/play/journey/casebook': { code: 500, msg: '本局配置不可读取' } })
  page.openCasebook(); await flush()
  assert.equal(page.data.casebook.error, '本局配置不可读取')
  assert.equal(page.data.casebook.loading, false)
})

test('顶栏结构:左上角两档、背包开既有定稿背包、案件册按内容出现、「…」有各站奖励、标题带主题名', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /bindtap="onNavTap"/)
  assert.match(wxml, /<view class="fx-story__act" wx:if="\{\{themeBag\.sessionId\}\}" bindtap="openThemeBackpack"/, '背包图标直接开定稿背包')
  assert.match(wxml, /<view class="fx-story__act" wx:if="\{\{hasCasebook\}\}" bindtap="openCasebook"/)
  assert.match(wxml, /bindtap="openJourneyRewards"[^>]*aria-label="各站奖励"/)
  assert.match(wxml, /<include src="play-topbar\.wxml" \/>/)
  assert.ok(!read('pages/play/play-topbar.wxml').includes('themeBackpackOpen'), '顶栏不碰背包弹层')
  assert.match(wxml, /\{\{topicName && topicName !== chapter\.name \? topicName \+ ' · ' : ''\}\}\{\{chapter\.name\}\}/)
  const java = read('../chengyinhub-admin/src/main/java/com/chengyinhub/web/service/play/PlayNodeQueryApplication.java')
  assert.match(java, /data\.put\("topicName", topicForFlow\.getName\(\)\)/, '/api/play/nodes 带主题名')
  assert.match(read('pages/play/index.js'), /topicName: d\.topicName \|\| this\._entryTopicName \|\| ''/)
  assert.match(read('components/cy/free-map/index.wxml'), /rotate="\{\{rotate\}\}"/)
})
