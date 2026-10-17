// BL-189：主题详情、商家申请、项目报名里可能出现重复值的图片/标签列表，
// 不能用 wx:key="*this"（同图或同名标签会造成 key 重复、渲染错位）。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const read = (rel) => fs.readFileSync(path.join(__dirname, '../..', rel), 'utf8')

const CASES = [
  ['pages/topic/index/index.wxml', /<swiper-item[^>]*wx:for="\{\{img\.list\(info\.imgArr\)\}\}"[^>]*>/g, 1],
  ['pages/topic/index/index.wxml', /<view[^>]*wx:for="\{\{info\.contentIncludes\}\}"[^>]*>/g, 1],
  ['pages/topic/index/index.wxml', /<view[^>]*wx:for="\{\{ uploadImages \}\}"[^>]*>/g, 1],
  ['pages/topic/merchantapply/index.wxml', /<view[^>]*wx:for="\{\{picUrl\.split\(','\)\}\}"[^>]*>/g, 1],
  ['pages/topic/components/project-join/index.wxml', /<view[^>]*wx:for="\{\{ (?:nodeSheet\.)?play\.chips \}\}"[^>]*>/g, 2],
]

for (const [file, re, count] of CASES) {
  test(`BL-189 ${file} ${re.source.slice(0, 40)} 按下标作 key`, () => {
    const tags = read(file).match(re) || []
    assert.equal(tags.length, count)
    for (const tag of tags) {
      assert.doesNotMatch(tag, /wx:key="\*this"/)
      assert.match(tag, /wx:key="index"/)
    }
  })
}
