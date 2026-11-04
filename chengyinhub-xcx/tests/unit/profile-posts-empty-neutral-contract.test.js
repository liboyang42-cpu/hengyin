// CR-309 / 产品拍板 2026-09-15 第 29 问:工作台发帖器/店帖「不做」,去掉误导空态提示。
//
// subpackageMember/components/cy/profile 的「帖文」tab 对本人空态写的是「发布第一条城市帖文吧」,
// 但商家主页/工作台里没有任何发帖入口(全仓零 creativesquare/post 创建入口)。
// 用户裁决:不新增发帖功能,只把这条催发帖文案换成中性陈述,他方文案保存量。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const WXML = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/profile/index.wxml'), 'utf8')

test('RED 锚点:帖文 tab 空态不得再催本人发帖(没有入口的 CTA 是误导)', () => {
  assert.doesNotMatch(WXML, /发布第一条城市帖文/, '空态仍在催发帖，但页面上没有发帖入口')
})

// 第十轮 f3 第 4 项(j5 L9):owner「删说明小字」规则取代了 CR-309 时的副标题文案,空态只留标题。
test('空态只有中性标题,没有催发帖的副标题', () => {
  assert.match(WXML, /<cy-empty class="pc-feed-state" wx:elif="\{\{nodata\}\}" title="还没有帖文" \/>/)
  assert.doesNotMatch(WXML, /你还没有发布过帖文|TA 还没有发布帖文/)
})

test('不为此新增发帖入口或 handler(拍板:不做发帖器/店帖)', () => {
  assert.doesNotMatch(WXML, /去发帖|立即发帖|goPost|createPost|onCreatePost/)
  const js = fs.readFileSync(path.join(ROOT, 'subpackageMember/components/cy/profile/index.js'), 'utf8')
  assert.doesNotMatch(js, /goPost|createPost|onCreatePost/, '不得新增发帖方法')
})
