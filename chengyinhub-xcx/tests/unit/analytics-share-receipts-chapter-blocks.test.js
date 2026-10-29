'use strict'
// 2026-10-24 上线前小问题快修 S4:
//   U-171 漫游 / 广场分享的完成、失败回执;P1-021 章节块流解析回落埋点 chapter_blocks_parse_failed。
// 事件名必须同时进客户端与服务端白名单(白名单外的 track 会被静默丢弃,等于没埋)。
// 负控:删掉 pages/roam/index.js 里的 .then(receipt) → 「漫游分享回执」用例变红;
//       删掉 analytics.js 里任一新事件 → 白名单用例变红。
const assert = require('node:assert/strict')
const test = require('node:test')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const NEW_EVENTS = ['roam_share_complete', 'roam_share_fail', 'square_share_complete', 'square_share_fail', 'chapter_blocks_parse_failed']

test('新事件同时进入客户端与服务端白名单', () => {
  const client = fs.readFileSync(path.join(ROOT, 'utils/analytics.js'), 'utf8')
  const server = fs.readFileSync(path.join(ROOT, '../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/api/ApiAnalyticsController.java'), 'utf8')
  const serverList = /ALLOWED_EVENTS = new HashSet<>\(Arrays\.asList\(([\s\S]*?)\n\s*\)\);/.exec(server)[1]
  for (const name of NEW_EVENTS) {
    assert.match(client, new RegExp('^\\s*' + name + ': true,?$', 'm'), '客户端白名单缺 ' + name)
    assert.ok(serverList.includes('"' + name + '"'), '服务端白名单缺 ' + name)
  }
})

test('blocksParseFailure:只对形状不对的块流给原因码,未下发块流是正常路径', () => {
  const { blocksParseFailure } = require('../../pages/play/utils/chapter-blocks-health.js')
  assert.equal(blocksParseFailure(undefined), '')
  assert.equal(blocksParseFailure(null), '')
  assert.equal(blocksParseFailure([]), '')
  assert.equal(blocksParseFailure([{ type: 'text', content: 'x' }, { type: 'node', nodeId: 1 }]), '')
  assert.equal(blocksParseFailure('[{"type":"text"}]'), 'unparsed_string')
  assert.equal(blocksParseFailure({ type: 'text' }), 'not_array')
  assert.equal(blocksParseFailure([{ type: 'text' }, null]), 'bad_block')
  assert.equal(blocksParseFailure([{ content: '没 type' }]), 'bad_block')
})

test('reportBlocksParseFailure:只报 id + 原因码,同章同因只报一次', () => {
  const { reportBlocksParseFailure, EVENT } = require('../../pages/play/utils/chapter-blocks-health.js')
  const calls = []
  const fake = { track: (name, payload) => calls.push([name, payload]) }
  const seen = {}
  reportBlocksParseFailure(fake, seen, 12, '{"bad"')
  reportBlocksParseFailure(fake, seen, 12, '{"bad"')
  reportBlocksParseFailure(fake, seen, 13, [{ type: 'text' }])
  assert.deepEqual(calls, [[EVENT, { bizType: 'chapter', bizId: 12, properties: { reason: 'unparsed_string' } }]])
  assert.equal(EVENT, 'chapter_blocks_parse_failed')
})

test('play 页 buildChapter / buildChapterCards 在块流形状不对时上报', () => {
  const src = fs.readFileSync(path.join(ROOT, 'pages/play/index.js'), 'utf8')
  const body = (name) => src.slice(src.indexOf('  ' + name + '('), src.indexOf('\n  },', src.indexOf('  ' + name + '(')))
  assert.match(body('buildChapter'), /reportBlocksParseFailure\(analytics,/)
  assert.match(body('buildChapterCards'), /reportBlocksParseFailure\(analytics,/)
})

// ---- 漫游分享回执:行为测(真调 shareTo) ----
let pageConfig
const tracked = []
function loadRoamPage() {
  const analyticsPath = require.resolve('../../utils/analytics.js')
  require.cache[analyticsPath] = { id: analyticsPath, filename: analyticsPath, loaded: true,
    exports: { track: (name, payload) => { tracked.push([name, payload]); return true }, flush() {}, setSessionContext() {}, sanitizeProperties: (p) => p } }
  global.getApp = () => ({ getUserID: () => '9', globalData: { user_id: 9, features: {} }, isDevEnv: () => false, sendRequest() {} })
  global.Page = (config) => { pageConfig = config }
  global.wx = { getStorageSync: () => undefined, setStorageSync() {}, getWindowInfo: () => ({ statusBarHeight: 20 }),
    getSystemInfoSync: () => ({ statusBarHeight: 20 }), showLoading() {}, hideLoading() {}, showToast() {}, showShareMenu() {} }
  delete require.cache[require.resolve('../../pages/roam/index.js')]
  require('../../pages/roam/index.js')
  const page = Object.assign({}, pageConfig, { data: JSON.parse(JSON.stringify(pageConfig.data)) })
  page.data.share = Object.assign({}, page.data.share, { visibility: 'PUBLIC', idx: 0 })
  page._activeShareSessionId = () => 77
  page.setData = () => {}
  return page
}

test('漫游分享回执:成功记 complete,失败/取消记 fail(只放渠道码与结果码)', async () => {
  const page = loadRoamPage()
  page._shareToSquare = async () => ({ ok: true, channel: '广场', posted: true, postId: 5 })
  page._shareToAlbumGuide = async (ch) => ({ ok: false, channel: ch, saved: false, posted: false, cancelled: true })
  page._exportAndSave = async () => ({ saved: false, outcome: 'failed' })
  const ev = (ch) => ({ currentTarget: { dataset: { ch } } })

  tracked.length = 0
  await page.shareTo(ev('广场'))
  await page.shareTo(ev('朋友圈'))
  await page.shareTo(ev('保存'))
  const receipts = tracked.filter(([name]) => name !== 'roam_share_channel')
  assert.deepEqual(receipts, [
    ['roam_share_complete', { bizType: 'roam', bizId: 77, properties: { channel: 'square' } }],
    ['roam_share_fail', { bizType: 'roam', bizId: 77, properties: { channel: 'moments', reason: 'cancelled' } }],
    ['roam_share_fail', { bizType: 'roam', bizId: 77, properties: { channel: 'album', reason: 'failed' } }],
  ])
})

test('广场详情转发:success/fail 回调记回执(2026-10-29 列表页已删)', () => {
  for (const rel of ['pages/square/detail/index.js']) {
    const src = fs.readFileSync(path.join(ROOT, rel), 'utf8')
    const share = src.slice(src.indexOf('onShareAppMessage'), src.indexOf('onShareAppMessage') + 1800)
    assert.match(share, /success: function[\s\S]*?analytics\.track\('square_share_complete'/, rel)
    assert.match(share, /fail: function[\s\S]*?analytics\.track\('square_share_fail'/, rel)
  }
})
