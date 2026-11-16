'use strict'
// R6b money-payout:提现申请与收款绑定的小程序侧。资金开关关闭/读不到时一律当关闭,页面保持联系客服。
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const root = path.join(__dirname, '..', '..')

function load(answer) {
  const sent = []
  global.getApp = () => ({
    sendRequest(param) {
      sent.push(param)
      answer(param)
    }
  })
  const file = require.resolve(path.join(root, 'utils/money-payout.js'))
  delete require.cache[file]
  return { mod: require(file), sent }
}

test('withdrawalOpen is true only for an explicit server true', () => {
  const cases = [
    [p => p.success({ code: 200, data: { withdrawal: true } }), true],
    [p => p.success({ code: 200, data: { withdrawal: 'true' } }), false],
    [p => p.success({ code: 200, data: { withdrawal: false } }), false],
    [p => p.success({ code: 500, data: { withdrawal: true } }), false],
    [p => p.fail({}), false],
    [p => p.successStatusAbnormal({}, 503), false],
    [() => { throw new Error('boom') }, false],
  ]
  for (const [answer, want] of cases) {
    const { mod } = load(answer)
    let got = null
    mod.withdrawalOpen(v => { got = v })
    assert.equal(got, want)
  }
})

test('the switch read is anonymous; writes send JSON with the member session', () => {
  const { mod, sent } = load(p => p.success({ code: 200, data: {} }))
  mod.withdrawalOpen(() => {})
  mod.submit('12.50', () => {})
  mod.claimBinding('A'.repeat(24), 'js', () => {})
  assert.equal(sent[0].url, '/api/app/money-switch')
  assert.equal(sent[0].auth, false)
  assert.equal(sent[1].url, '/api/payout/request/submit')
  assert.deepEqual(sent[1].data, { amount: '12.50' })
  assert.equal(sent[1].header['content-type'], 'application/json')
  assert.equal(sent[2].url, '/api/payout/binding/claim')
  assert.deepEqual(sent[2].data, { bindCode: 'A'.repeat(24), jsCode: 'js' })
})

test('a refusal shows the server sentence, never a machine code', () => {
  let msg
  load(p => p.success({ code: 500, msg: '提现暂未开放' })).mod.submit('1', e => { msg = e })
  assert.equal(msg, '提现暂未开放')
  load(p => p.success({ code: 500, msg: 'COMMON.FEATURE_OFF' })).mod.submit('1', e => { msg = e })
  assert.equal(msg, '操作没有完成，请稍后再试')
})

test('binding code comes from the scanned link or ?bc=, and must be 24 url-safe characters', () => {
  const { mod } = load(() => {})
  const code = 'abcdefghijklmnopqrstuv_-'
  assert.equal(mod.bindCodeFromOptions({ q: encodeURIComponent('https://example.test/payout-bind?bc=' + code) }), code)
  assert.equal(mod.bindCodeFromOptions({ bc: code }), code)
  assert.equal(mod.bindCodeFromOptions({ bc: 'short' }), '')
  assert.equal(mod.bindCodeFromOptions({ q: '%E0%A4%A' }), '')
  assert.equal(mod.bindCodeFromOptions({}), '')
})

test('amount input accepts positive yuan with at most two decimals', () => {
  const { mod } = load(() => {})
  assert.equal(mod.normalizeAmount('12.5'), '12.5')
  assert.equal(mod.normalizeAmount(' 3 '), '3')
  for (const bad of ['0', '0.00', '-1', '1.234', 'abc', '', '1e3']) assert.equal(mod.normalizeAmount(bad), '')
})

test('the member cash-out page keeps the customer-service entry while the switch is closed', () => {
  const wxml = fs.readFileSync(path.join(root, 'subpackageMember/tixian/tixian.wxml'), 'utf8')
  const js = fs.readFileSync(path.join(root, 'subpackageMember/tixian/tixian.js'), 'utf8')
  assert.match(wxml, /<cy-btn variant="primary" bindtap="saveData">\{\{payoutOpen \? '申请提现' : '联系客服提现'\}\}<\/cy-btn>/)
  assert.match(js, /payoutOpen: false,/)
  assert.match(js, /if \(this\.data\.payoutOpen\) \{ wx\.navigateTo\(\{ url: '\/subpackageMember\/payout-request\/index' \}\); return \}\n {4}withdrawCs\.showWithdrawCsPopup\(\)/)
  assert.match(js, /moneyPayout\.withdrawalOpen\(function \(open\) \{/)
})

test('the request page submits only while the server says open, with a payout WeChat and no open request', () => {
  const js = fs.readFileSync(path.join(root, 'subpackageMember/payout-request/index.js'), 'utf8')
  assert.match(js, /if \(this\.data\.busy \|\| !view \|\| !this\.data\.open \|\| view\.pendingId \|\| !view\.wechatReady\) return/)
  assert.match(js, /open: data\.withdrawalOpen === true/)
})

test('the binding page is registered and claims with a fresh wx.login code', () => {
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  const member = (app.subpackages || app.subPackages).find(s => s.root.replace(/\/$/, '') === 'subpackageMember')
  assert.ok(member.pages.includes('payout-bind/index'))
  assert.ok(member.pages.includes('payout-request/index'))
  const js = fs.readFileSync(path.join(root, 'subpackageMember/payout-bind/index.js'), 'utf8')
  assert.match(js, /wx\.login\(/)
  assert.match(js, /moneyPayout\.claimBinding\(code, res\.code/)
})

test('MERCHANT-8: the claim hands back the two-digit number the app owner must pick', () => {
  let got = 'unset'
  load(p => p.success({ code: 200, data: { status: 'CLAIMED', matchNumber: 42 } })).mod.claimBinding('A'.repeat(24), 'js', (err, n) => { got = [err, n] })
  assert.deepEqual(got, [null, 42])
  load(p => p.success({ code: 200, data: { status: 'CLAIMED' } })).mod.claimBinding('A'.repeat(24), 'js', (err, n) => { got = [err, n] })
  assert.deepEqual(got, [null, null])
  load(p => p.success({ code: 500, msg: '绑定码已失效，请在 App 里重新生成' })).mod.claimBinding('A'.repeat(24), 'js', (err, n) => { got = [err, n] })
  assert.deepEqual(got, ['绑定码已失效，请在 App 里重新生成', null])
  const { mod } = load(() => {})
  for (const bad of [9, 100, 42.5, '42', true]) assert.equal(mod.matchNumberOf({ matchNumber: bad }), null)
  const wxml = fs.readFileSync(path.join(root, 'subpackageMember/payout-bind/index.wxml'), 'utf8')
  assert.match(wxml, /请在城瘾 App 里选择 \{\{matchNumber\}\}/)
})
