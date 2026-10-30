// P-60(2026-10-27):故事流手写体加载器 —— 未配置 / 失败回落系统字体,只给故事流正文和对白挂样式类。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.join(__dirname, '../..')
const MODULE = path.join(ROOT, 'pages/play/utils/story-font.js')
const PUBLISH_COPY = 'pages/publish/utils/publish/story-font.js'
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')

function fresh(url, wxStub) {
  delete require.cache[require.resolve(MODULE)]
  const source = fs.readFileSync(MODULE, 'utf8').replace(/var STORY_FONT_URL = '[^']*';/, 'var STORY_FONT_URL = ' + JSON.stringify(url) + ';')
  const Module = require('node:module')
  const m = new Module(MODULE)
  m.filename = MODULE
  m.paths = Module._nodeModulePaths(path.dirname(MODULE))
  global.wx = wxStub
  m._compile(source, MODULE)
  return m.exports
}

test('URL 未配置:不调 loadFontFace,回调 false(回落系统字体)', () => {
  let calls = 0
  const font = fresh('', { loadFontFace() { calls += 1 } })
  let result = null
  font.loadStoryFont(ok => { result = ok })
  assert.equal(calls, 0)
  assert.equal(result, false)
})

test('非 https 地址同样不加载', () => {
  let calls = 0
  const font = fresh('http://cdn.example.com/hand.woff2', { loadFontFace() { calls += 1 } })
  font.loadStoryFont()
  assert.equal(calls, 0)
})

test('配置了 https 地址:全局加载一次,成功 / 失败都通知所有等待者', () => {
  const calls = []
  const font = fresh('https://cdn.example.com/hand.woff2', { loadFontFace(opts) { calls.push(opts) } })
  const results = []
  font.loadStoryFont(ok => results.push(ok))
  font.loadStoryFont(ok => results.push(ok))
  assert.equal(calls.length, 1, '只加载一次')
  assert.equal(calls[0].global, true)
  assert.equal(calls[0].family, 'CyStoryHand')
  assert.equal(calls[0].source, 'url("https://cdn.example.com/hand.woff2")')
  calls[0].fail({ errMsg: 'loadFontFace:fail' })
  assert.deepEqual(results, [false, false])
  font.loadStoryFont(ok => results.push(ok))
  assert.deepEqual(results, [false, false, false], '失败后不再重试,直接回落')
})

test('样式类只挂在故事流正文 / 对白上,字族带系统字体回落;仓里不放字体文件', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /chfull__para--wide' : 'cy-story-hand'/)
  assert.match(wxml, /class="chfull__reveal-b cy-story-hand"/)
  assert.match(wxml, /class="chfull__voice-t cy-story-hand"/)
  assert.doesNotMatch(wxml, /st-line--ttl[^"]*cy-story-hand/, '标题不换字体')
  assert.match(read('pages/publish/temp/index.wxml'), /class="cg-pv-beat-text cy-story-hand"/)
  for (const file of ['pages/play/index.wxss', 'pages/publish/temp/index.wxss']) {
    assert.match(read(file), /\.cy-story-hand \{ font-family: 'CyStoryHand', -apple-system, "PingFang SC"/)
  }
  const fonts = []
  const walk = dir => fs.readdirSync(dir, { withFileTypes: true }).forEach(e => {
    if (e.name === 'node_modules' || e.name.startsWith('.')) return
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p); else if (/\.(ttf|otf|woff2?)$/i.test(e.name)) fonts.push(p)
  })
  walk(ROOT)
  assert.deepEqual(fonts, [], '字体走 CDN,不进包')
})

test('两份分包副本逐字一致,且不放进主包 utils/(3 MiB 棘轮)', () => {
  assert.equal(read(PUBLISH_COPY), read('pages/play/utils/story-font.js'))
  assert.equal(fs.existsSync(path.join(ROOT, 'utils/story-font.js')), false)
})

test('业主选定龙藏体:两份副本都指向已验证的官方完整 https 字库', () => {
  for (const f of ['pages/play/utils/story-font.js', 'pages/publish/utils/publish/story-font.js']) {
    assert.match(read(f), /var STORY_FONT_URL = 'https:\/\/fonts\.gstatic\.com\/s\/longcang\/v21\/LYjAdGP8kkgoTec8zkRgrQ\.ttf';/)
  }
})
