// DS 空值硬规(方案 §3.4 / §11.2-1 / §11.4 P0 追加;D-119 + F14a MC-011=B / MC-018 改口径):
// 数值位绑定值为空 ⇒ 渲 0(金额 ¥0 / ¥0.00,数量 0),禁止渲出裸 ¥、空白行、空冒号行,也不再渲 '—'。
// 「状态待定」「数据待同步」这类状态/说明文字不是数值,不在本规则内;请求失败走各页自己的加载失败·重试态。
//
// 本文件管其中最机械、最可判的两条:
//  ① 金额位:全仓不得再出现 `¥{{expr}}` 这种「¥ 是死字符、数值来自绑定」的写法 ——
//     expr 为空时屏幕上只剩一个孤零零的 ¥。正确写法是 `{{money.amount(expr)}}`,
//     由 utils/wxs/money.wxs 决定空值渲 0。
//  ② F14a 清单里的页面不得再拿 '—' 当数值兜底。
const { test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const SKIP = new Set(['node_modules', 'tests', '.git', 'miniprogram_npm'])

function allWxml(dir = ROOT, out = []) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (SKIP.has(e.name)) continue
    const p = path.join(dir, e.name)
    if (e.isDirectory()) allWxml(p, out)
    else if (e.name.endsWith('.wxml')) out.push(p)
  }
  return out
}

const BARE = /¥\{\{/

test('全仓 wxml 不得出现裸 ¥{{...}}(空值会渲成孤零零一个 ¥)', () => {
  const bad = []
  for (const p of allWxml()) {
    const lines = fs.readFileSync(p, 'utf8').split('\n')
    lines.forEach((line, i) => {
      if (BARE.test(line)) bad.push(`${path.relative(ROOT, p)}:${i + 1}`)
    })
  }
  assert.deepEqual(bad, [], `这些位置的 ¥ 是死字符，数值为空时只剩 ¥:\n  ${bad.join('\n  ')}`)
})

test('money.amount:空值渲 ¥0(D-119 业主「金额也写 0」),0 是合法金额不能被吃掉', () => {
  // wxs 不是 CommonJS,这里按同一份源码求值,保证断言的就是线上那份逻辑
  const src = fs.readFileSync(path.join(ROOT, 'utils/wxs/money.wxs'), 'utf8')
  const sandbox = { module: { exports: {} } }
  new Function('module', src)(sandbox.module)
  const { amount } = sandbox.module.exports

  assert.equal(amount(undefined), '¥0')
  assert.equal(amount(null), '¥0')
  assert.equal(amount(''), '¥0')
  // 0 / '0' / '0.00' 都是真实金额,必须照渲
  assert.equal(amount(0), '¥0')
  assert.equal(amount('0'), '¥0')
  assert.equal(amount('0.00'), '¥0.00')
  assert.equal(amount('12.50'), '¥12.50')
})

test('引用 money.amount 的 wxml 都真的 import 了这个 wxs 模块', () => {
  const missing = []
  for (const p of allWxml()) {
    const s = fs.readFileSync(p, 'utf8')
    if (!s.includes('money.amount(')) continue
    if (!/<wxs\s+src="[^"]*utils\/wxs\/money\.wxs"\s+module="money"\s*\/>/.test(s)) {
      missing.push(path.relative(ROOT, p))
    }
  }
  assert.deepEqual(missing, [], `用了 money.amount 却没 import:\n  ${missing.join('\n  ')}`)
})

test('money.wxs 的相对路径在每个引用方都能真解析到文件', () => {
  const broken = []
  for (const p of allWxml()) {
    const s = fs.readFileSync(p, 'utf8')
    const m = s.match(/<wxs\s+src="([^"]*utils\/wxs\/money\.wxs)"/)
    if (!m) continue
    if (!fs.existsSync(path.resolve(path.dirname(p), m[1]))) {
      broken.push(`${path.relative(ROOT, p)} -> ${m[1]}`)
    }
  }
  assert.deepEqual(broken, [], `wxs 相对路径解析不到:\n  ${broken.join('\n  ')}`)
})

// F14a(MC-011=B / MC-018):这些页面的金额、数量、比例没取到时写 0 / ¥0.00,不再写 '—'。
// 清单外的 '—' 只允许出现在非数值位(日期、状态、文字说明、时长),逐条登记理由;新增任何一处都要判红。
const F14A_FILES = {
  'pages/merchant/ledger/index.js': [
    /settleAlertsHint: '—'/,                                   // 订阅提醒说明文字
    /settleAlertsHint: loading \? '正在确认微信通知授权…' : '—'/,
    /'核销有效' : '—'\)/,                                       // 核销状态文字
  ],
  'pages/merchant/ledger/order-detail/index.js': [/^\s*return '—';$/],   // fulfillmentStateText 状态文字
  'pages/merchant/ledger/batch-detail/index.js': [/invoiceText = .*\|\| '—';/], // 开票状态文字
  'subpackageA/components/scene-asset-income-detail/index.wxml': [/item\.displayDate \|\| '—'/], // 日期
  'subpackageMember/components/cy/scene-member-withdraw-history/index.wxml': [],
  'subpackageMember/free-explore/index.wxml': [],
  'pages/coop/components/settlement-detail/view-model.js': [
    /line\('payable', '等待入账', '—'\)/,                       // 时间线日期
    /line\('paid', '已入余额', '—'\)/,
    /dateText\(record\.updateTime\) \|\| '—'/,
  ],
  'components/cy/club-direct-ledger/index.wxml': [],
  'pages/club/enroll/index.wxml': [],
  'subpackageMember/invoice/index.wxml': [],
  'pages/topic/pricing/index.js': [],
  'pages/topic/pricing/partner/index.wxml': [],
  'pages/club/notify/index.wxml': [],
  'pages/club/detail/index.js': [/^\s*: '—';$/],                // 贡献榜用时:null = 未计时,不是 0 分钟(P-18 另有补位写 0)
  'pages/team/detail/index.wxml': [],
  'pages/team/join/index.wxml': [],
  'pages/activity/official-mine/index.js': [
    /_city: nonBlank\((event|b)\.city, '—'\)/,                 // 城市文字
    /_aud: .* : '—',/,                                          // 受众文字
    /_mode: .* : '—'\),/,                                       // 发送方式文字
  ],
  'components/cy/scene-roam-session/index.js': [/time: formattedDuration \|\| '—'/], // 时长按文本处理
}

function stripComments(src, file) {
  if (file.endsWith('.wxml')) return src.replace(/<!--[\s\S]*?-->/g, (m) => m.replace(/[^\n]/g, ''))
  return src
    .replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ''))
    .split('\n').map((line) => line.replace(/(^|[^:'"])\/\/.*$/, '$1')).join('\n')
}

test('F14a:清单页面不得用 \'—\' 当数值兜底(没取到写 0 / ¥0.00)', () => {
  const bad = []
  for (const [file, allowed] of Object.entries(F14A_FILES)) {
    const abs = path.join(ROOT, file)
    assert.ok(fs.existsSync(abs), `F14a 清单文件不存在:${file}`)
    const lines = stripComments(fs.readFileSync(abs, 'utf8'), file).split('\n')
    lines.forEach((line, i) => {
      if (!/['"]—['"]/.test(line)) return
      if (allowed.some((re) => re.test(line))) return
      bad.push(`${file}:${i + 1}  ${line.trim()}`)
    })
  }
  assert.deepEqual(bad, [], `这些位置仍拿 '—' 当数值兜底,按 F14a 改成 0 / ¥0.00:\n  ${bad.join('\n  ')}`)
})

test('F14a:清单里的金额兜底不写「待定」(状态文字「状态待定」除外)', () => {
  const bad = []
  for (const file of [
    'pages/merchant/ledger/order-detail/index.js',
    'pages/merchant/ledger/batch-detail/index.js',
  ]) {
    const lines = stripComments(fs.readFileSync(path.join(ROOT, file), 'utf8'), file).split('\n')
    lines.forEach((line, i) => { if (/'待定'/.test(line)) bad.push(`${file}:${i + 1}`) })
  }
  const ledger = stripComments(fs.readFileSync(path.join(ROOT, 'pages/merchant/ledger/index.js'), 'utf8'), 'x.js')
  for (const fn of ['entryRow', 'batchRow']) {
    const body = ledger.slice(ledger.indexOf(`  ${fn}(row) {`), ledger.indexOf('\n  },', ledger.indexOf(`  ${fn}(row) {`)))
    assert.ok(body.length > 0, fn)
    if (/'待定'/.test(body)) bad.push(`pages/merchant/ledger/index.js ${fn}`)
  }
  const moneyDisplay = ledger.slice(ledger.indexOf('function moneyDisplay('), ledger.indexOf('\n}', ledger.indexOf('function moneyDisplay(')))
  assert.ok(moneyDisplay.length > 0)
  if (/'待定'/.test(moneyDisplay)) bad.push('pages/merchant/ledger/index.js moneyDisplay')
  assert.deepEqual(bad, [], `金额缺字段仍写「待定」:\n  ${bad.join('\n  ')}`)
})
