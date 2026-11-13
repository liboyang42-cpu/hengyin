const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const m = require('../../pages/topic/utils/topic-motion.js')
const routePreview = require('../../utils/topic-route-preview.js')
const { buildIntro } = require('../../pages/templatedetail/topic-preview/intro.js')
const read = (p) => fs.readFileSync(path.join(__dirname, '../..', p), 'utf8')

test('锁定章节的锁图标不随展开态旋转', () => {
  const css = read('pages/topic/index/index.wxss')
  assert.match(css, /\.story-cc\.is-locked \.story-cc__chev \{ transform: none; \}/)
  assert.ok(css.indexOf('.story-cc.is-locked .story-cc__chev') > css.indexOf('.story-cc.is-open .story-cc__chev'))
})

test('结局/终章/尾声/序章章名不再加序号前缀', () => {
  for (const name of ['结局 · 雨停之后', '结局一·归途', '终章 · 回响', '尾声', '序章 · 入城']) {
    assert.equal(m.chapterHeading(3, 5, name), name)
    assert.equal(m.chapterLabel(3, 5, name), '')
  }
  assert.equal(m.chapterHeading(0, 4, '雨夜'), '第一章 · 雨夜')
  assert.equal(m.chapterHeading(3, 4, '回响'), '终章 · 回响')
  const cards = m.buildStoryCards({ chaptersList: [{ name: '雨夜', nodes: [] }, { name: '结局 · 归途', nodes: [] }] }, [])
  assert.equal(cards[0].label, '第一章')
  assert.equal(cards[1].label, '')
  assert.equal(cards[1].title, '结局 · 归途')
})

test('路线地图:取景点包含全部站点,中心取包围盒中心', () => {
  const ch = [
    { nodes: [{ latitude: 30, longitude: 120, sortId: 1 }, { latitude: 30.02, longitude: 120.04, sortId: 2 }] },
    { nodes: [{ latitude: 29.99, longitude: 119.98, sortId: 1 }] },
  ]
  const r = routePreview.build(ch)
  assert.equal(r.routeMarkers.length, 3)
  for (const mk of r.routeMarkers) assert.ok(r.routeIncludePoints.some((p) => p.latitude === mk.latitude && p.longitude === mk.longitude))
  assert.ok(Math.abs(r.mapLatitude - (29.99 + 30.02) / 2) < 1e-9)
  assert.ok(Math.abs(r.mapLongitude - (119.98 + 120.04) / 2) < 1e-9)
  assert.equal(routePreview.FIT_PADDING.length, 4)
  assert.ok(routePreview.FIT_PADDING[0] + routePreview.FIT_PADDING[2] <= 90, '上下边距合计不得吃掉地图高度的一半')
  const js = read('pages/topic/index/index.js')
  assert.match(js, /ctx\.includePoints\(\{ points: pts, padding: routePreview\.FIT_PADDING \}\)/)
  assert.match(js, /\[400, 1200\]\.forEach\(\(ms\) => this\._later\(ms, \(\) => \{ if \(String\(this\.data\.activeTab\) === '2'\) this\._fitRouteMap/)
})

test('无封面占位留在封面区内,不盖返回键', () => {
  assert.match(read('pages/topic/index/index.wxss'), /\.topic \.det1\.dt \.bgt\.cy-cover-fb \{ display: flex; padding-top: 180rpx; \}/)
})

test('章节导读不含题面、观察位置、密码表(锁/未锁同口径)', () => {
  const body = '雨夜进城，先去钟楼。【题目】请破译下面的密码 A=1 B=2'
  assert.equal(m.chapterGuide({ description: body }), '雨夜进城，先去钟楼。')
  assert.equal(m.chapterGuide({ description: '【观察位置】钟楼东侧', blocks: [{ type: 'text', content: '旧城的雨一直下。' }] }), '旧城的雨一直下。')
  assert.equal(m.chapterGuide({ description: '【题目】x' }), '')
  for (const locked of [true, false]) {
    const c = m.buildStoryCards({ chaptersList: [{ name: '一', locked, description: body }] }, [])[0]
    assert.ok(!/题目|观察位置|A=1/.test(c.excerpt))
  }
})

test('模板预览带出简介与章节导读', () => {
  const i = buildIntro({ description: '  一杯咖啡，一段散步。 ', chapters: [{ id: 1, name: '街角', description: '先去第一家店。' }, { name: '' }] })
  assert.equal(i.desc, '一杯咖啡，一段散步。')
  assert.equal(i.chapters.length, 1)
  assert.equal(i.chapters[0].guide, '先去第一家店。')
  assert.deepEqual(buildIntro(null), { desc: '', chapters: [] })
  assert.match(read('pages/templatedetail/topic-preview/index.wxml'), /intro\.chapters/)
})

test('模板预览的章名/导读与主题详情同口径(分包副本不漂移)', () => {
  const samples = [
    { name: '街角', description: '先去第一家店。' },
    { name: '结局 · 归途', description: '雨夜进城。【题目】破译 A=1' },
    { name: '回响', blocks: [{ type: 'text', content: '长'.repeat(90) }] },
  ]
  const intro = buildIntro({ chapters: samples })
  samples.forEach((c, i) => {
    assert.equal(intro.chapters[i].name, m.chapterHeading(i, samples.length, c.name))
    assert.equal(intro.chapters[i].guide, m.chapterGuide(c))
  })
  assert.doesNotMatch(read('pages/templatedetail/topic-preview/intro.js'), /require\(/)
})
