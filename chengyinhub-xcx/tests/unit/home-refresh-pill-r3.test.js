'use strict'

// 打磨第 3 轮 §4-6:首页浮在封面上的「正在更新…」用 .cy-refresh-float--pill 底,不再是封面上的一行淡字。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')

function assertRefreshPills(wxml) {
  // v3-sec-sync 也是普通说明文字样式；按真实刷新提示识别，不把「3 公里内」算作 loading。
  const syncs = wxml.match(/<text\b[^>]*>[^<]*正在更新[^<]*<\/text>/g) || []
  assert.equal(syncs.length, 3)
  const expected = [
    ['recommendedTopicList', 'recoHero', '推荐主题'],
    ['nearbyTopicList', 'nearbyTopicList.length', '附近主题'],
    ['upcomingActivityList', 'upcomingActivityList.length', '即将上线'],
  ]
  for (const [list, content, label] of expected) {
    const tag = syncs.find(tag => tag.includes('正在更新' + label + '…'))
    assert.ok(tag, '缺少刷新提示：' + label)
    assert.match(tag, /class="v3-sec-sync cy-refresh-float cy-refresh-float--pill"/)
    assert.ok(tag.includes('wx:if="{{listLoading.' + list + ' && ' + content + '}}"'))
    assert.match(tag, /aria-role="status" aria-live="polite"/)
  }
}

test('首页三处「正在更新…」都挂胶囊底', () => {
  assertRefreshPills(fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8'))
})

test('负控：任一真实刷新提示丢胶囊或保留内容条件必须判红', () => {
  const source = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxml'), 'utf8')
  for (const mutated of [
    source.replace('v3-sec-sync cy-refresh-float cy-refresh-float--pill', 'v3-sec-sync'),
    source.replace('listLoading.nearbyTopicList && nearbyTopicList.length', 'listLoading.nearbyTopicList'),
  ]) {
    assert.notEqual(mutated, source, '负控必须真实变异')
    assert.throws(() => assertRefreshPills(mutated))
  }
})

test('页内 .v3-sec-sync 不再写 padding 盖掉胶囊内距', () => {
  const wxss = fs.readFileSync(path.join(ROOT, 'pages/index/index.wxss'), 'utf8')
  const rule = wxss.match(/\.v3-sec-sync \{[^}]*\}/)[0]
  assert.doesNotMatch(rule, /padding/)
  const shared = fs.readFileSync(path.join(ROOT, 'style/fade-in.wxss'), 'utf8')
  assert.match(shared, /\.cy-refresh-float--pill \{[^}]*border-radius: var\(--cy-radius-pill\)/)
})
