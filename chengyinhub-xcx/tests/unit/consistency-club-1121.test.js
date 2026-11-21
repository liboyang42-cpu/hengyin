// 2026-11-21 俱乐部域一致性走查(consistency pass 1121)。
// 钉住本轮的关键改动:页内自绘 / 原生按钮 → cy-btn、置灰键被点会说原因、首读骨架、
// 小命中区补到 88rpx、说明小字撤掉、改过的 wxss 不再出现裸色值。
const test = require('node:test')
const assert = require('node:assert')
const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '../..')
const read = p => fs.readFileSync(path.join(ROOT, p), 'utf8')
const stripWxml = s => s.replace(/<!--[\s\S]*?-->/g, '')
const stripCss = s => s.replace(/\/\*[\s\S]*?\*\//g, '')
const json = p => JSON.parse(read(p))

function usesCyBtn(page) {
  const comps = json(`pages/club/${page}/index.json`).usingComponents || {}
  assert.equal(comps['cy-btn'], '/components/cy/btn/index', `${page} 用了 cy-btn 就必须在 json 里注册`)
}

test('俱乐部页不再用原生 <button> 做提交键(分享 open-type 除外)', () => {
  for (const page of ['notify', 'settlement', 'event-ops']) {
    const wxml = stripWxml(read(`pages/club/${page}/index.wxml`))
    assert.doesNotMatch(wxml, /<button\b/, `${page} 还有原生 button`)
    usesCyBtn(page)
  }
  const detail = stripWxml(read('pages/club/detail/index.wxml'))
  for (const tag of detail.match(/<button\b[^>]*>/g) || []) {
    assert.match(tag, /open-type="share"/, '详情页只允许分享用原生 button')
  }
})

test('角色页「分配」是 cy-btn,置灰被点时由 assignRole 说缺哪一项', () => {
  const wxml = stripWxml(read('pages/club/roles/index.wxml'))
  assert.match(wxml, /<cy-btn variant="primary" disabled="\{\{!selectedMemberId \|\| !selectedRoleCode \|\| actingKey !== ''\}\}"\s+bindtap="assignRole" bind:disabledtap="assignRole">分配<\/cy-btn>/)
  assert.doesNotMatch(wxml, /class="assign-btn/)
  usesCyBtn('roles')
  const js = read('pages/club/roles/index.js')
  assert.match(js, /if \(!memberId\) \{ toast\('先选择一位成员'\); return \}/)
  assert.match(js, /if \(!roleCode\) \{ toast\('先选择一个角色'\); return \}/)
})

test('治理页两颗平台动作键是 cy-btn,提交中转圈;「请提交可核实的事实」说明行撤掉', () => {
  const wxml = stripWxml(read('pages/club/governance/index.wxml'))
  assert.match(wxml, /<cy-btn variant="primary" loading="\{\{actingKey !== ''\}\}" bindtap="reportTarget">填写事实并提交平台<\/cy-btn>/)
  assert.match(wxml, /<cy-btn variant="primary" loading="\{\{actingKey !== ''\}\}" bindtap="appealBan">填写理由并提交申诉<\/cy-btn>/)
  assert.doesNotMatch(wxml, /platform-button/)
  assert.doesNotMatch(wxml, /请提交可核实的事实/)
  usesCyBtn('governance')
})

test('勋章「创建」因结果未确认置灰时,被点会给原因', () => {
  const wxml = stripWxml(read('pages/club/badges/index.wxml'))
  assert.match(wxml, /disabled="\{\{createUnknown\}\}" bindtap="submitCreate" bind:disabledtap="onCreateBlockedTap"/)
  const js = read('pages/club/badges/index.js')
  assert.match(js, /onCreateBlockedTap\(\) \{\s*if \(this\.data\.createUnknown\) toast\('先核对上次的创建结果'\)/)
})

test('结算页提现键:原生 disabled button → cy-btn + disabledtap 说明原因,禁用口径不变', () => {
  const wxml = stripWxml(read('pages/club/settlement/index.wxml'))
  assert.match(wxml, /<cy-btn variant="primary" loading="\{\{withdrawing\}\}" disabled="\{\{withdrawing \|\| withdrawState !== 'positive'\}\}"/)
  assert.match(wxml, /bindtap="goWithdraw" bind:disabledtap="onWithdrawBlockedTap"/)

  const src = read('pages/club/settlement/index.js')
  const fn = /onWithdrawBlockedTap\(\) \{[\s\S]*?\n  \},/.exec(src)
  assert.ok(fn, 'onWithdrawBlockedTap 不见了')
  const toasts = []
  const ctx = { data: {} }
  const run = new Function('wx', `const o = { ${fn[0]} }; return o.onWithdrawBlockedTap`)({ showToast: t => toasts.push(t.title) })
  ctx.data = { withdrawState: 'zero', complaintWaiting: true, summary: { isOwner: true } }; run.call(ctx)
  ctx.data = { withdrawState: 'zero', complaintWaiting: false, summary: { isOwner: true } }; run.call(ctx)
  ctx.data = { withdrawState: 'unknown', summary: { isOwner: false, personal: false } }; run.call(ctx)
  ctx.data = { withdrawState: 'unknown', summary: { isOwner: true } }; run.call(ctx)
  ctx.data = { withdrawing: true, withdrawState: 'unknown', summary: {} }; run.call(ctx)
  assert.deepEqual(toasts, ['客诉期结束后可提现', '当前没有可提现余额', '仅主理人可发起提现', '可提现余额还在确认'])
})

test('群发通知:预览 / 发送 / 只重试失败项都是 cy-btn;失败行不再挂一个点不动的「重试」字样', () => {
  const wxml = stripWxml(read('pages/club/notify/index.wxml'))
  assert.match(wxml, /<cy-btn variant="secondary" size="sm" loading="\{\{previewState === 'loading'\}\}"[^>]*bindtap="preview">预览受众<\/cy-btn>/)
  assert.match(wxml, /<cy-btn variant="primary" loading="\{\{sending\}\}"[^>]*bindtap="send">发送站内通知<\/cy-btn>/)
  assert.match(wxml, /<cy-btn variant="primary" size="sm" loading="\{\{retrying\}\}"[^>]*bindtap="retryFailed">只重试失败项<\/cy-btn>/)
  assert.doesNotMatch(wxml, /<text class="row-desc"[^>]*>重试<\/text>/)
  const css = stripCss(read('pages/club/notify/index.wxss'))
  assert.match(css, /\.inline-retry \{ padding: var\(--cy-space-3\) var\(--cy-space-2\); margin: calc\(-1 \* var\(--cy-space-3\)\) calc\(-1 \* var\(--cy-space-2\)\); \}/)
})

test('活动运营:主按钮 / 重试取消 / 刷新是 cy-btn;取消本场行不重复弹窗里的后果说明', () => {
  const wxml = stripWxml(read('pages/club/event-ops/index.wxml'))
  assert.match(wxml, /<cy-btn variant="danger" loading="\{\{cancellationSubmitting\}\}" bindtap="cancelCurrentOccurrence">重试取消本场<\/cy-btn>/)
  assert.match(wxml, /<cy-btn variant="secondary" size="sm" bindtap="retryCancellationReadback">刷新<\/cy-btn>/)
  assert.equal((wxml.match(/<cy-btn variant="primary" loading="\{\{submitting\}\}"/g) || []).length, 2)
  assert.doesNotMatch(wxml, /<text class="row-meta">会下架本场/)
  assert.match(wxml, /content="会下架本场、处理退款并终止候补。已核销的部分不退。"/, '后果仍在确认弹窗里')
  assert.doesNotMatch(wxml, /已锁定主题与负责人策略/)
  const css = stripCss(read('pages/club/event-ops/index.wxss'))
  assert.match(css, /\.correct-btn::after \{ content: ""; position: absolute; left: 0; right: 0; top: -14rpx; bottom: -14rpx; \}/)
  assert.match(css, /\.remove-date, \.add-date \{ min-height: var\(--cy-btn-h\);/)
})

test('俱乐部详情:帖文首读给骨架,编辑历史读取中给骨架而不是一行「正在读取…」', () => {
  const wxml = stripWxml(read('pages/club/detail/index.wxml'))
  assert.match(wxml, /<\/block>\s*<cy-skeleton wx:elif="\{\{!postsLoaded\}\}" type="feed-card"[^>]*\/>\s*<cy-error load-failure wx:elif="\{\{postsError\}\}"/)
  assert.match(wxml, /<cy-skeleton wx:if="\{\{postHistoryState === 'loading'\}\}" type="list"/)
  assert.doesNotMatch(wxml, />正在读取…</)
})

test('客户列表:筛选空态不挂「换个筛选试试」;真空态走 kind=empty;chip 命中区 88rpx', () => {
  const wxml = stripWxml(read('pages/club/customers/index.wxml'))
  assert.doesNotMatch(wxml, /换个筛选试试/)
  assert.match(wxml, /<cy-empty wx:elif="\{\{state === 'empty'\}\}" fill size="lg" kind="empty" title="还没有客户"/)
  const css = stripCss(read('pages/club/customers/index.wxss'))
  const chip = /\.cus-chip \{([^}]*)\}/.exec(css)[1]
  assert.match(chip, /height: 88rpx;/)
  assert.match(chip, /border-width: 10rpx 0;/)
  assert.match(chip, /background-clip: padding-box;/)
})

test('小文字动作命中区补齐(角色撤销 / 治理解封 / 客户详情取消 / 编辑态退出)', () => {
  const pairs = [
    ['pages/club/roles/index.wxss', /\.row-link \{ padding: var\(--cy-space-3\) var\(--cy-space-2\); margin: 0 calc\(-1 \* var\(--cy-space-2\)\); \}/],
    ['pages/club/governance/index.wxss', /\.row-link \{ padding: var\(--cy-space-3\) var\(--cy-space-2\); margin: calc\(-1 \* var\(--cy-space-3\)\) calc\(-1 \* var\(--cy-space-2\)\); \}/],
    ['pages/club/customer-detail/index.wxss', /\.cd-section-cancel \{ padding: var\(--cy-space-3\)/],
    ['pages/club/customer-detail/index.wxss', /\.cd-edit-btn::after, \.cd-add-btn::after \{/],
    ['pages/club/event-ops/index.wxss', /\.editing-exit \{ padding: var\(--cy-space-3\)/],
  ]
  for (const [file, re] of pairs) assert.match(stripCss(read(file)), re, file)
})

test('俱乐部页所有可点 view/text 都有按压反馈与 aria-role', () => {
  const dir = path.join(ROOT, 'pages/club')
  const files = []
  ;(function walk(d) {
    for (const e of fs.readdirSync(d, { withFileTypes: true })) {
      const f = path.join(d, e.name)
      if (e.isDirectory()) walk(f)
      else if (f.endsWith('.wxml')) files.push(f)
    }
  })(dir)
  const misses = []
  for (const f of files) {
    const s = stripWxml(fs.readFileSync(f, 'utf8'))
    for (const tag of s.match(/<(view|text)\b[^>]*?\b(bindtap|catchtap)=[^>]*>/g) || []) {
      if (!/hover-class=/.test(tag) || !/aria-role=/.test(tag)) misses.push(path.relative(ROOT, f) + ' ' + tag.slice(0, 80))
    }
  }
  assert.deepEqual(misses, [])
})

test('本轮改过的样式表没有裸色值', () => {
  for (const page of ['apply', 'customers', 'roles', 'governance', 'notify', 'settlement', 'event-ops', 'customer-detail', 'enroll']) {
    const css = stripCss(read(`pages/club/${page}/index.wxss`))
      .split('\n').filter(line => !/ds-ok/.test(line)).join('\n')
    assert.doesNotMatch(css, /#[0-9a-fA-F]{3,8}\b|rgba?\(/, `${page} 有裸色值`)
  }
})
