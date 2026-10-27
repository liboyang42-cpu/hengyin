// L08(2026-10-27 G-8 / M-12):三套预设标签库(俱乐部 / 商家 / 主题),每个标签配图标,实底无描边小一号
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const lib = require('../../utils/tag-library.js')

const ROOT = path.join(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')

test('三套预设分开,每个预设标签都配了已入库的图标', () => {
  ;['club', 'merchant', 'theme'].forEach((kind) => {
    const groups = lib.tagSet(kind)
    assert.ok(groups.length > 0, kind)
    groups.forEach((g) => g.tags.forEach((t) => {
      assert.ok(lib.ICONS.indexOf(t.icon) >= 0, kind + '/' + t.label + ' 图标未入库')
    }))
  })
  const merchantCats = lib.tagSet('merchant').find((g) => g.group === '经营类目')
  assert.equal(merchantCats.tags.length, 28, '沿用 10-22 的 28 个经营类目')
  assert.notDeepEqual(lib.tagSet('club'), lib.tagSet('theme'))
})

test('图标文件在两个分包里都存在(商家页 / 我的页),不进主包', () => {
  lib.ICONS.forEach((name) => {
    assert.ok(fs.existsSync(path.join(ROOT, lib.MERCHANT_ICON_DIR, name + '.svg')), 'merchant ' + name)
    assert.ok(fs.existsSync(path.join(ROOT, lib.MEMBER_ICON_DIR, name + '.svg')), 'member ' + name)
  })
})

test('decorateTags:预设优先、自由文字按关键字认领、认不出不带图标', () => {
  const items = lib.decorateTags(['老街', '精品咖啡馆', '神秘', ' ', null], 'merchant', lib.MEMBER_ICON_DIR)
  assert.deepEqual(items, [
    { label: '老街', iconSrc: '/subpackageMember/images/tags/landmark.svg' },
    { label: '精品咖啡馆', iconSrc: '/subpackageMember/images/tags/coffee.svg' },
    { label: '神秘', iconSrc: '' },
  ])
})

test('用在品牌介绍与公开主页:图标 + 实底无描边', () => {
  const decor = read('pages/merchant/decor/index.wxml')
  assert.match(decor, /class="dc-tag dc-tag--lib" wx:for="\{\{tagItems\}\}"/)
  assert.match(read('pages/merchant/decor/index.js'), /tagLibrary\.tagSet\('merchant'\)/, '特色标签预设只从标签库来')
  const profile = read('subpackageMember/components/cy/profile/index.wxml')
  assert.match(profile, /class="pc-tag pc-tag--lib" wx:for="\{\{merchantCategoryTags\}\}"/)
  assert.match(profile, /class="pc-tag pc-tag--lib" wx:for="\{\{merchantTagItems\}\}"/)
  const css = read('subpackageMember/components/cy/profile/index.wxss')
  assert.match(css, /\.pc-tag--lib \{[^}]*border: none;/)
})
