// 1119 审计清单复核(第二轮):#867 合入后在 master 上仍残留的小程序侧问题。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (p) => fs.readFileSync(path.join(ROOT, p), 'utf8')

test('PG-003: play 与通行证订单页的 URL 参数走安全解码', () => {
  const play = read('pages/play/index.js')
  assert.match(play, /options\.topicName \? safeDecode\(options\.topicName\)/)
  assert.doesNotMatch(play, /decodeURIComponent\(options\.topicName\)/)
  const order = read('subpackageMember/market/pass-order/index.js')
  assert.match(order, /safeDecode\(options\.orderNo\)/)
  assert.doesNotMatch(order, /decodeURIComponent\(/)
})

test('PG-046/PG-058: 残留的硬编码色换成 token', () => {
  const club = read('pages/club/detail/index.wxss')
  assert.doesNotMatch(club, /--club-glass: rgba\(/)
  assert.doesNotMatch(club, /\.feed-divider \{[^}]*rgba\(/)
  assert.doesNotMatch(read('pages/publish/fabu/index.wxss'), /#3a3a3c/i)
  assert.doesNotMatch(read('pages/topic/merchantinfo/merchantinfo.wxss'), /background: rgba\(255, 255, 255, 0\.5\)/)
})

test('PG-073: 自由探索导航行只写「导航去这里」,不带半角括号副注', () => {
  const wxml = read('pages/play/index.wxml')
  assert.match(wxml, /<text>导航去这里<\/text>/)
  assert.doesNotMatch(wxml, /打开地图 App/)
})

test('PG-089: 协作列表不露主题编号', () => {
  const js = read('pages/coop/list/index.js')
  assert.doesNotMatch(js, /'主题 #'/)
  assert.doesNotMatch(js, /'#' \+ this\._topicFilter/)
  assert.match(js, /it\.topicName \|\| '未命名主题'/)
})

test('PG-063/065/068/070: 清单所列页面的辅助说明已删', () => {
  const gone = {
    'pages/roam/index.wxml': ['拍一张，传上去', '先写再看：写一句', '已到过、已打卡的节点先不显示'],
    'pages/activity/official-detail/index.wxml': ['你参与主办这场活动'],
    'pages/topic/merchantinfo/merchantinfo.wxml': ['选择最适合你的品牌参与方式'],
    'pages/topic/components/project-host/index.wxml': ['先把节点配好，商家才有可承接的站'],
    'subpackageA/pages/myproject/index.wxml': ['须本人上传并通过内容安全审核'],
    'pages/coop/invite/index.wxml': ['先选择合作商家，再选择', '对方会在「收到的」中确认', '去编辑主题(票价/内容)'],
  }
  for (const [file, texts] of Object.entries(gone)) {
    const src = read(file)
    for (const t of texts) assert.ok(!src.includes(t), file + ' 仍含「' + t + '」')
  }
})

function loadPass() {
  const p = path.join(ROOT, 'subpackageMember/utils/platform-pass.js')
  delete require.cache[require.resolve(p)]
  return require(p)
}

const SERVER_TERMS = ['有效期内可领取通行证含的主题自玩票', '有效期：月卡30天，自开通起算', '仅本人可用，不含同行人', '不自动续费', '退款为整单退款，领取过主题需人工审核']

test('OR-024: 后端 terms 是字符串数组时逐条原文显示,标题唯一', () => {
  const pp = loadPass()
  const rows = pp.termRows({ terms: SERVER_TERMS })
  assert.deepEqual(rows, [{ k: '续费', v: '不自动续费' }, { k: '退款规则', v: '退款为整单退款，领取过主题需人工审核' }])
  const view = pp.passView({ enabled: true, plans: [{ planCode: 'MONTH', priceFen: 3900, validDays: 30, title: '月卡' }], libraryCount: 0, terms: SERVER_TERMS }, null, 'ios', false)
  const keys = view.facts.map((r) => r.k)
  assert.equal(new Set(keys).size, keys.length, 'wx:key="k" 必须唯一')
  assert.deepEqual(view.facts[0], { k: '可玩主题', v: '0 个主题' }, '0 也照实显示')
  for (const line of SERVER_TERMS) {
    assert.ok(view.facts.some((r) => line === r.v || line.endsWith(r.v)), '缺服务端须知:' + line)
  }
  assert.ok(view.facts.some((r) => r.k === '有效期' && r.v === '月卡30天，自开通起算'))
})

test('OR-024: terms 缺省或为旧对象形状时保持原兜底', () => {
  const pp = loadPass()
  assert.deepEqual(pp.termRows(null), [{ k: '续费', v: '不自动续费' }, { k: '退款规则', v: '退款为整单' }])
  assert.deepEqual(pp.termRows({ terms: [] }), [{ k: '续费', v: '不自动续费' }, { k: '退款规则', v: '退款为整单' }])
  assert.deepEqual(pp.termRows({ terms: { refund: '整单退' } })[1], { k: '退款规则', v: '整单退' })
})

test('FE-G9: 订单详情的探店图鉴 / 通关奖励按服务端 perkOnly 隐藏,字段缺省照旧显示', () => {
  const dir = 'subpackageMember/components/cy/scene-member-order-detail/'
  const js = read(dir + 'index.js')
  assert.match(js, /view\.perkOnly = vo\.perkOnly === true/)
  const wxml = read(dir + 'index.wxml')
  for (const title of ['探店图鉴', '通关奖励']) {
    const at = wxml.indexOf('<view class="section-title">' + title + '</view>')
    assert.ok(at > 0, title)
    const head = wxml.slice(wxml.lastIndexOf('<cy-card', at), at)
    assert.match(head, /completion\.show && !completion\.perkOnly/, title)
  }
})

test('FE-G5: 本店权益展示条款(已含 / 需另付 / 需预约)', () => {
  const p = path.join(ROOT, 'pages/play/utils/perk-only.js')
  delete require.cache[require.resolve(p)]
  const { perkTermsText } = require(p)
  assert.equal(perkTermsText(null), '')
  assert.equal(perkTermsText({}), '')
  assert.equal(perkTermsText({ included: true, extraSpendText: '一杯', reserveRequired: true }), '已含 · 需预约')
  assert.equal(perkTermsText({ included: false, extraSpendText: ' 任意饮品 ' }), '需消费 任意饮品')
  assert.equal(perkTermsText({ reserveRequired: true }), '需预约')
  assert.match(read('pages/play/index.js'), /perkTerms: perkTermsText\(perk\)/)
  assert.match(read('pages/play/index.wxml'), /wx:if="\{\{hero\.node\.perkTerms\}\}"/)
})

test('FE-G5: 章节供给表单写权益展示条款,生效后走 display-terms', () => {
  const form = read('pages/topic/components/cy/chapter-node-form/index.js')
  assert.match(form, /detail\.displayTerms = \{/)
  assert.match(read('pages/topic/components/cy/chapter-node-form/index.wxml'), /bind:change="onIncludedChange"/)
  const mi = read('pages/topic/merchantinfo/merchantinfo.js')
  assert.match(mi, /url: '\/api\/coop\/offer\/display-terms'/)
  assert.match(mi, /if \(!pending\) this\.saveOfferDisplayTerms\(res\.data && res\.data\.id, draft\.displayTerms\)/)
})
