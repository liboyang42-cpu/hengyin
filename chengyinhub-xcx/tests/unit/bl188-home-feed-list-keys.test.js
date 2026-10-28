// BL-188：首页动态的标签与图片列表不能用 wx:key="*this"，
// 同一条动态里两张相同图片 URL（或两个相同标签）会造成 key 重复、渲染错位。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const wxml = fs.readFileSync(path.join(__dirname, '../../pages/index/index.wxml'), 'utf8')

test('BL-188 feedTags 列表按下标作 key', () => {
  const tags = wxml.match(/<text[^>]*wx:for="\{\{item\.feedTags\}\}"[^>]*>/g) || []
  assert.equal(tags.length, 2)
  for (const tag of tags) {
    assert.doesNotMatch(tag, /wx:key="\*this"/)
    assert.match(tag, /wx:key="index"/)
  }
})

// 2026-10-28 p104/p70:首页推荐卡改成整张封面卡,四宫格 feedImages 撤掉;再加回来时仍按 gi 作 key
test('BL-188 feedImages 列表按 gi 下标作 key', () => {
  const blocks = wxml.match(/<block[^>]*wx:for="\{\{item\.feedImages\}\}"[^>]*>/g) || []
  assert.doesNotMatch(wxml, /wx:for="\{\{item\.feedImages\}\}"[^>]*wx:key="\*this"/)
  for (const block of blocks) {
    assert.doesNotMatch(block, /wx:key="\*this"/)
    assert.match(block, /wx:for-index="gi"/)
    assert.match(block, /wx:key="gi"/)
  }
})
