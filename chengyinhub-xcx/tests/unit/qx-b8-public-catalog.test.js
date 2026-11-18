// F14c(PG-025,业主 2026-11-17 定下线):城市合集 / 合集详情两页下线 —— 小程序里没有入口,iOS 也没有对应功能。
// 旧分享卡:catalog/index 留轻量重定向页(带 id 进主题页,其余回首页);catalog/detail 由 app.onPageNotFound 按同一规则转走。
// 后端 /api/public-catalog/list 保留(游戏主题库 market/library 在用)。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')

test('F14c:合集两页下线,只留 catalog/index 重定向页注册在 pages/topic 分包', () => {
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
  const topic = app.subPackages.find((p) => p.root === 'pages/topic')
  assert.ok(topic.pages.includes('catalog/index'), '旧分享卡还要能落地')
  assert.ok(!topic.pages.includes('catalog/detail'), '合集详情已下线')
  assert.deepEqual(fs.readdirSync(path.join(ROOT, 'pages/topic/catalog')).sort(), ['index.js', 'index.json', 'index.wxml'])
  assert.equal(fs.existsSync(path.join(ROOT, 'pages/topic/utils/public-catalog.js')), false)
  const page = fs.readFileSync(path.join(ROOT, 'pages/topic/catalog/index.js'), 'utf8')
  assert.doesNotMatch(page, /\/api\/public-catalog\//, '重定向页不再读目录接口')
  assert.doesNotMatch(page, /onShareAppMessage/, '下线页不再产生新的分享卡')
})

test('F14c:重定向页 —— 带合法主题 id 进主题页,其余回首页', () => {
  const calls = []
  let def
  const prev = { Page: global.Page, wx: global.wx }
  global.Page = (d) => { def = d }
  global.wx = { redirectTo: (o) => calls.push(['redirectTo', o.url]), reLaunch: (o) => calls.push(['reLaunch', o.url]) }
  try {
    const P = require.resolve('../../pages/topic/catalog/index.js')
    delete require.cache[P]
    const { redirectTarget } = require(P)
    assert.equal(redirectTarget({ id: '990101' }), '/pages/topic/index/index?id=990101')
    assert.equal(redirectTarget({ city_code: '310100' }), '/pages/index/index')
    assert.equal(redirectTarget({ id: '1 or 1=1' }), '/pages/index/index')
    def.onLoad({ id: '42' })
    def.onLoad({ city_code: '310100' })
    assert.deepEqual(calls, [['redirectTo', '/pages/topic/index/index?id=42'], ['reLaunch', '/pages/index/index']])
  } finally { Object.assign(global, prev) }
})

test('F14c:旧合集详情分享卡由 onPageNotFound 转到对应主题页,没有 id 回首页', () => {
  const P = require.resolve('../../utils/update-manager.js')
  delete require.cache[P]
  const { retiredPageTarget, handlePageNotFound } = require(P)
  assert.equal(retiredPageTarget('pages/topic/catalog/detail', { id: '990101' }), '/pages/topic/index/index?id=990101')
  assert.equal(retiredPageTarget('pages/topic/catalog/detail', {}), '')
  assert.equal(retiredPageTarget('pages/other/gone', { id: '1' }), '')
  const calls = []
  handlePageNotFound({ path: '/pages/topic/catalog/detail', query: { id: '7' } }, { reLaunch: (o) => calls.push(o.url) })
  assert.deepEqual(calls, ['/pages/topic/index/index?id=7'])
})

test('F14c:游戏主题库仍走保留的 /api/public-catalog/list', () => {
  const lib = fs.readFileSync(path.join(ROOT, 'subpackageMember/utils/pass-library.js'), 'utf8')
  assert.match(lib, /require\('\.\/public-catalog\.js'\)/)
})
