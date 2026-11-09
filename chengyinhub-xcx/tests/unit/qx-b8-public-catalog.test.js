// qx-b8 §20.1 公开目录 / UI-P3 主题合集落地:只展示服务端事实,未知不当 0,不造城市名/评分/到货提醒。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const c = require('../../pages/topic/utils/public-catalog.js')

const base = {
  id: '990101', name: '外滩夜行', cityCode: '310100', cityName: '上海', totalTime: 5400, locationCount: 6,
  productType: 2, priceState: 'KNOWN', priceFrom: 39, priceUnit: 'SELF_PLAY_PASS', saleState: 'ON_SALE',
  freeSelfPlay: false, groupEligible: null, schoolEligible: null, requestedLocale: 'zh-CN', actualLocale: 'zh-CN'
}

test('价格:UNKNOWN 显示待确认,不回退旧价也不当 0;未在售不给价;免费自玩单列', () => {
  assert.equal(c.saleText(base), '自玩 ¥39 起')
  assert.equal(c.saleText(Object.assign({}, base, { priceState: 'UNKNOWN', priceFrom: 39 })), '价格待确认')
  assert.equal(c.saleText(Object.assign({}, base, { priceFrom: null })), '价格待确认')
  assert.equal(c.saleText(Object.assign({}, base, { priceUnit: 'TICKET' })), '¥39 起')
  assert.equal(c.saleText(Object.assign({}, base, { saleState: 'NOT_ON_SALE' })), '未开售')
  assert.equal(c.saleText(Object.assign({}, base, { saleState: 'SALE_ENDED' })), '已停售')
  assert.equal(c.saleText(Object.assign({}, base, { saleState: 'NOT_ON_SALE', freeSelfPlay: true })), '免费自玩', '免费自玩不因未在售被封死')
  assert.equal(c.saleText(Object.assign({}, base, { saleState: null })), '')
})

test('卡片:团体/学校 null 是未声明,不写「不接待」;缺失事实整段不渲染', () => {
  const card = c.cardView(base)
  assert.equal(card.meta, '上海 · 1 小时 30 分钟 · 6 站')
  assert.deepEqual(card.tags, ['自玩 ¥39 起'])
  const bare = c.cardView({ id: 1, name: 'x', totalTime: null, locationCount: 0, cityName: null })
  assert.equal(bare.meta, '')
  assert.deepEqual(bare.tags, [])
  const group = c.cardView(Object.assign({}, base, { groupEligible: true, groupMinPeople: 6, groupMaxPeople: 30, schoolEligible: true, ageMin: 8, ageMax: 60 }))
  assert.deepEqual(group.tags, ['自玩 ¥39 起', '团体 6–30 人', '可接学校', '8–60 岁'])
  assert.ok(!group.tags.some((t) => /不接待|评分|人玩过|到货/.test(t)))
  assert.equal(c.cardView({ id: 1 }), null, '无标题不出卡')
})

test('语言:只在缺译回退到其它语言时标出实际语言', () => {
  assert.equal(c.cardView(Object.assign({}, base, { requestedLocale: 'en', actualLocale: 'zh-CN' })).tags.slice(-1)[0], '中文')
  assert.ok(!c.cardView(base).tags.includes('中文'))
})

test('城市:无编码或无名的城市不进选择器(不猜名)', () => {
  const cities = c.cityOptions([
    { cityCode: '310100', cityName: '上海', topicCount: 2 },
    { cityCode: null, cityName: null, topicCount: 1 },
    { cityCode: '330100', cityName: null, topicCount: 1 },
  ])
  assert.deepEqual(cities, [{ key: '310100', label: '上海', count: 2 }])
})

test('筛选:只出 /filters 给的真实取值;团体/学校只有 available 时才出', () => {
  const chips = c.filterChips({ productTypes: [2, 7], deliveryModes: ['SELF_PLAY', 'X'], groupEligibleAvailable: true, schoolEligibleAvailable: false })
  assert.deepEqual(chips.map((x) => x.label), ['自由探索', '自玩', '可接团体'])
  assert.deepEqual(c.filterChips(null), [])
})

test('查询与直达:同字段单选、再点取消;分享路径只带合法字段,直达无需经过首页', () => {
  const chip = { field: 'product_type', value: '2' }
  let s = c.toggleFilter({ city_code: '310100' }, chip)
  assert.deepEqual(s, { city_code: '310100', product_type: '2' })
  s = c.toggleFilter(s, chip)
  assert.deepEqual(s, { city_code: '310100' })
  assert.deepEqual(c.toggleFilter({}, { field: 'evil', value: '1' }), {})
  const q = c.buildQuery({ city_code: '310100', group_eligible: '1' }, { pageNum: 2, pageSize: 10 })
  assert.deepEqual(q, { locale: 'zh-CN', sort: 'NEWEST', city_code: '310100', group_eligible: '1', pageNum: 2, pageSize: 10 })
  assert.equal(c.sharePath('pages/topic/catalog/index', { city_code: '310100' }), '/pages/topic/catalog/index?city_code=310100')
  assert.deepEqual(c.selectedFromOptions({ city_code: 'abc', product_type: '2' }), { product_type: '2' })
})

test('落地详情:只取章节名与首章钩子;404 视为已下架或未公开', () => {
  const v = c.detailView(Object.assign({}, base, {
    chapterNames: ['第1章 咖啡', ''], preview: { firstChapterName: '第1章 咖啡', stops: [{ order: 1, name: '样板咖啡', hookTeaser: null }, { order: 2 }] },
    answer: 'SECRET', hint: 'SECRET'
  }))
  assert.deepEqual(v.chapters, ['第1章 咖啡'])
  assert.deepEqual(v.stops, [{ key: '1', order: 1, name: '样板咖啡', hook: '' }])
  assert.ok(!JSON.stringify(v).includes('SECRET'), '答案/提示不进视图')
  assert.equal(c.detailState({ code: 404 }), 'gone')
  assert.equal(c.detailState({ code: 500 }), 'error')
  assert.equal(c.detailState({ code: 200, data: {} }), 'ok')
})

test('页面:匿名只读目录接口;无到货提醒、无本地报价;主按钮落到真实主题详情', () => {
  const files = ['index.js', 'index.wxml', 'detail.js', 'detail.wxml'].map((f) => fs.readFileSync(path.join(ROOT, 'pages/topic/catalog', f), 'utf8').replace(/^\s*\/\/.*$/gm, '')).join('\n')
  assert.match(files, /\/api\/public-catalog\/list/)
  assert.match(files, /\/api\/public-catalog\/info/)
  assert.doesNotMatch(files, /到货提醒|订阅|requestSubscribeMessage|评分|rating/)
  assert.doesNotMatch(files, /\/api\/marketing\/product\/(quote|order)/)
  assert.match(files, /\/pages\/topic\/index\/index\?id=/)
  const app = JSON.parse(fs.readFileSync(path.join(ROOT, 'app.json'), 'utf8'))
  const topic = app.subPackages.find((p) => p.root === 'pages/topic')
  assert.ok(topic.pages.includes('catalog/index') && topic.pages.includes('catalog/detail'), '新页在分包内')
  assert.ok(!app.pages.some((p) => p.includes('catalog')), '不进主包')
})

test('U08-05 下架/未公开/读取失败时收起分享,只有公开作品可分享', () => {
  const calls = []
  let def
  const prev = { Page: global.Page, getApp: global.getApp, wx: global.wx }
  global.Page = (d) => { def = d }
  global.getApp = () => ({ globalData: {}, sendRequest() {} })
  global.wx = { showShareMenu: () => calls.push('show'), hideShareMenu: () => calls.push('hide') }
  try {
    delete require.cache[require.resolve('../../pages/topic/catalog/detail.js')]
    require('../../pages/topic/catalog/detail.js')
    const page = Object.assign({}, def, { data: Object.assign({}, def.data), setData(p) { Object.assign(this.data, p) } })
    page._seq = 1
    page.settle(1, { code: 404 })
    assert.equal(page.data.state, 'gone')
    page.settle(1, { code: 200, data: { id: '1', name: '外滩夜行' } })
    assert.equal(page.data.state, 'ok')
    assert.deepEqual(calls, ['hide', 'show'])
  } finally { Object.assign(global, prev) }
})
