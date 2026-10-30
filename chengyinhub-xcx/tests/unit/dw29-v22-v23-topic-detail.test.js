'use strict'
// 2026-10-29 走查 V-22 / V-23:主题详情页 —— 去掉「我的游玩记录」「主题音频讲解」「举报这个主题」三行;
// 数据条在总里程右边恢复「参与商家 / 已参加」,真实为 0 就写 0。
// Round 2:V-22b 删掉详情 tab 地址卡、节点介绍卡图片走 app.getImgUrl;V-23c 头图只放主题配置的 20 字短简介(subtitle),
// 不再展开;V-23d 数据条各格等分屏宽。
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const ROOT = path.resolve(__dirname, '../..')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const wxml = () => read('pages/topic/index/index.wxml').replace(/<!--[\s\S]*?-->/g, '')
const { buildTopicStatBar } = require('../../pages/topic/utils/topic-detail-facts.js')

test('V-22b 详情 tab 不再有地址卡', () => {
  const x = wxml()
  assert.doesNotMatch(x, /venue-card|info\.venue/)
  assert.doesNotMatch(read('pages/topic/index/index.wxss'), /venue-card/)
})

test('V-22 详情页不再展示「我的游玩记录」和「主题音频讲解」行', () => {
  const x = wxml()
  assert.doesNotMatch(x, /我的游玩记录|主题音频讲解|toggleTopicAudio/)
  assert.doesNotMatch(read('pages/topic/index/index.js'), /toggleTopicAudio\(\)/)
})

test('V-23 详情正文不再有「举报这个主题」行', () => {
  assert.doesNotMatch(wxml(), /举报这个主题|topic-report-entry/)
})

function loadTopicPage(app) {
  let config
  global.getApp = () => app
  global.Page = c => { config = c }
  global.wx = { getSystemInfoSync: () => ({}), getMenuButtonBoundingClientRect: () => ({}) }
  delete require.cache[require.resolve('../../pages/topic/index/index.js')]
  require('../../pages/topic/index/index.js')
  return config
}

test('V-23c 头图简介只放主题配置的短简介(subtitle)，最多 20 字一行，不展开', () => {
  const x = wxml()
  assert.match(x, /<view class="hero-desc" wx:if="\{\{info\.heroIntro\}\}">\{\{info\.heroIntro\}\}<\/view>/)
  assert.doesNotMatch(x, /toggleHeroDesc|heroDescOpen/)
  const css = read('pages/topic/index/index.wxss')
  assert.match(css, /\.hero-desc \{[^}]*white-space: nowrap;[^}]*text-overflow: ellipsis;/)
  assert.doesNotMatch(css, /\.hero-desc\.is-open/)
  const js = read('pages/topic/index/index.js')
  assert.match(js, /res\.data\['heroIntro'\] = heroIntro\(res\.data\.subtitle\)/)
  const fn = new Function('nodeText', 'decodeDisplayText', js.slice(js.indexOf('const HERO_INTRO_MAX'), js.indexOf('function templateCover')) + 'return heroIntro')(
    v => (v == null ? '' : String(v).trim() === 'null' ? '' : String(v).trim()), v => v)
  assert.equal(fn('沿着街角线索，完成一次可报名的城市路线。再多写几个字'), '沿着街角线索，完成一次可报名的城市路线。'.slice(0, 20))
  assert.equal(Array.from(fn('一二三四五六七八九十一二三四五六七八九十多出来')).length, 20)
  assert.equal(fn(null), ''); assert.equal(fn('null'), '')
  const config = loadTopicPage({ globalData: {}, getImgUrl: v => v })
  assert.equal('heroDescOpen' in config.data, false); assert.equal(config.toggleHeroDesc, undefined)
})

test('V-23c 主题配置的主题描述输入限 20 字并显示字数', () => {
  const sheet = read('pages/publish/fabu/topic-detail-sheet.wxml')
  assert.match(sheet, /value="\{\{formData\.subtitle\}\}" bindinput="onSubtitleChange" maxlength="20"/)
  assert.match(sheet, /<text wx:if="\{\{subtitleEditing\}\}" class="pd-card-count">\{\{formData\.subtitle\.length \|\| 0\}\}\/20<\/text>/)
  assert.match(read('pages/publish/fabu/index.wxss'), /\.pd-card-count \{/)
})

test('V-22b 节点介绍卡玩法图经 app.getImgUrl，http 转 https、相对路径补全、拿不到就用占位图', () => {
  const app = { globalData: {}, getImgUrl(v) {
    if (!v) return ''
    if (/^https?:\/\//.test(v)) { const s = v.replace(/^http:/, 'https:'); return s.includes('trusted') ? s : '' }
    return 'https://cdn.trusted/' + v
  } }
  const config = loadTopicPage(app)
  const page = { formatTimeToChinese: () => '' }
  const list = config.processChaptersList.call(page, [{ name: '一', nodes: [
    { name: 'a', cmsMemberTemplate: { imgUrl: 'http://oss.trusted/a.png' } },
    { name: 'b', cmsMemberTemplate: { imgUrl: 'upload/b.png,upload/c.png' } },
    { name: 'c', cmsMemberTemplate: { imgUrl: '', imgArr: 'upload/d.png' } },
    { name: 'd', cmsMemberTemplate: { imgUrl: 'http://evil.example/x.png' } },
    { name: 'e' },
  ] }])
  const urls = list[0].nodes.map(n => n.cmsMemberTemplate && n.cmsMemberTemplate.imgUrl)
  assert.deepEqual(urls, ['https://oss.trusted/a.png', 'https://cdn.trusted/upload/b.png', 'https://cdn.trusted/upload/d.png',
    '/images/home-route-city-placeholder.jpg', undefined])
})

test('V-23d 数据条各格等分屏宽，不横向溢出', () => {
  const x = wxml()
  assert.match(x, /class="statbar__row \{\{statBar\.length >= 5 \? 'statbar__row--dense' : ''\}\}"/)
  assert.match(x, /<cy-theme-badge class="statbar__badge"/)
  const css = read('pages/topic/index/index.wxss')
  assert.match(css, /\.topic \.statbar__badge, \.topic \.statbar__cell \{ flex: 1 1 0; min-width: 0; width: auto; \}/)
  assert.match(css, /\.topic \.statbar__row \{ width: 100%; \}/)
  assert.match(read('pages/topic/components/cy/theme-badge/index.wxss'), /\.tb-cell \{ width: 100%;/)
  // 375 / 390 宽:徽章 + 最多 6 格等分,每格可用宽度要放得下最长的标签「参与商家」(4 × 21rpx)
  for (const screenPx of [375, 390]) {
    const rpx = 750, padding = 15 * 2, cols = 7, seps = 6
    const cell = (rpx - padding - seps) / cols
    assert.ok(cell >= 4 * 21, screenPx + ' 宽每格 ' + cell.toFixed(1) + 'rpx')
  }
})

test('V-23 玩家详情数据条:总里程右边照常出参与商家与已参加，真实为 0 就写 0', () => {
  const cells = buildTopicStatBar({ productType: 1, totalTime: 3600, totalMileage: 0.6, registrationMerchantCount: 0, playerCount: 0 }, { keepZeroCounts: true })
  assert.deepEqual(cells.map(c => c.key), ['hour', 'mileage', 'merchant', 'players'])
  assert.equal(cells[2].value, '0'); assert.equal(cells[3].value, '0')
  const rich = buildTopicStatBar({ productType: 1, totalMileage: 3, registrationMerchantCount: 4, playerCount: 1280 }, { keepZeroCounts: true })
  assert.deepEqual(rich.slice(-2).map(c => c.value), ['4', '1,280'])
  // 商家页不传选项，口径不变
  assert.deepEqual(buildTopicStatBar({ productType: 1, totalMileage: 3 }).map(c => c.key), ['mileage'])
  assert.match(read('pages/topic/index/index.js'), /buildTopicStatBar\(res\.data, \{ keepZeroCounts: true \}\)/)
})

test('V-22b R2 节点介绍卡先用站点自己的图，站点图不可用再退回玩法图', () => {
  const app = { globalData: {}, getImgUrl(v) {
    if (!v) return ''
    if (/^https?:\/\//.test(v)) { const s = v.replace(/^http:/, 'https:'); return s.includes('trusted') ? s : '' }
    return 'https://cdn.trusted/' + v
  } }
  const config = loadTopicPage(app)
  const page = { formatTimeToChinese: () => '' }
  const list = config.processChaptersList.call(page, [{ name: '一', nodes: [
    { name: 'a', imgUrl: 'http://oss.trusted/stop.jpg?Expires=1', cmsMemberTemplate: { imgUrl: 'upload/tpl.png' } },
    { name: 'b', imgUrl: null, cmsMemberTemplate: { imgUrl: 'upload/tpl.png' } },
    { name: 'c', imgUrl: 'http://evil.example/x.png', cmsMemberTemplate: { imgUrl: 'upload/tpl.png' } },
    { name: 'd', imgUrl: null, cmsMemberTemplate: { imgUrl: '' } },
  ] }])
  assert.deepEqual(list[0].nodes.map(n => n.cmsMemberTemplate.imgUrl), ['https://oss.trusted/stop.jpg?Expires=1',
    'https://cdn.trusted/upload/tpl.png', 'https://cdn.trusted/upload/tpl.png', '/images/home-route-city-placeholder.jpg'])
})

test('2026-10-30 站点和玩法都没图时用主题自己的封面,不再落到通用城市占位图', () => {
  const app = { globalData: {}, getImgUrl: (v) => (v ? 'https://cdn.trusted/' + v : '') }
  const config = loadTopicPage(app)
  const page = { formatTimeToChinese: () => '' }
  const list = config.processChaptersList.call(page, [{ name: '一', nodes: [
    { name: 'open', imgUrl: null, cmsMemberTemplate: { imgUrl: '' } },
  ] }], 'upload/topic-cover.jpg,upload/second.jpg')
  assert.equal(list[0].nodes[0].cmsMemberTemplate.imgUrl, 'https://cdn.trusted/upload/topic-cover.jpg')
  const js = fs.readFileSync(path.join(__dirname, '../../pages/topic/index/index.js'), 'utf8')
  assert.match(js, /that\.processChaptersList\(res\.data\['chaptersList'\], res\.data\['imgUrl'\]\)/)
})
