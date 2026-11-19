const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const ROOT = path.resolve(__dirname, '../..')
const read = (file) => fs.readFileSync(path.join(ROOT, file), 'utf8')

function assertVisibleFailureContract(wxml) {
  assert.match(wxml, /wx:if="\{\{feedback\}\}"[^>]*>\{\{feedback\}\}/)
  // 结构改过一次(状态条拆成 圆点/文案/动作 三段),契约收回到真正要守的两件事:
  //   ① 有一个由 degradedMessage 控制显示的块;② 这段文字真的被渲染出来。
  assert.match(wxml, /wx:if="\{\{degradedMessage\}\}"/)
  assert.match(wxml, />\{\{degradedMessage\}\}/)
}

test('探店日凭证由真实 handler 驱动且失败态被 WXML 消费(F08:「我已到店」→「出示核销码」,扫码与拍照在「更多」)', () => {
  const js = read('pages/play/merchant/index.js')
  const wxml = read('pages/play/merchant/index.wxml')
  for (const handler of ['arriveHere', 'scanArrival', 'uploadProof', 'openVerifyCode']) {
    assert.match(js, new RegExp(handler + '\\s*\\('))
  }
  for (const handler of ['arriveHere', 'moreScan', 'morePhoto', 'openVerifyCode', 'openMore']) {
    assert.match(wxml, new RegExp('bindtap="' + handler + '"'))
  }
  assert.match(js, /moreScan\(\) \{ this\.setData\(\{ 'more\.show': false \}\); this\.scanArrival\(\) \}/)
  assert.match(js, /morePhoto\(\) \{ this\.setData\(\{ 'more\.show': false \}\); this\.uploadProof\(\) \}/)
  // 主按钮顺序:没到店 =「我已到店」,到店后直接「出示核销码」;拍照不再挡在核销码前面
  const cta = wxml.slice(wxml.indexOf('<view class="mp-cta">'))
  assert.ok(cta.indexOf('bindtap="arriveHere"') > 0 && cta.indexOf('bindtap="openVerifyCode"') > cta.indexOf('bindtap="arriveHere"'))
  assert.doesNotMatch(cta, /class="mp-cta__main[^"]*" bindtap="(scanArrival|uploadProof)"/)
  assertVisibleFailureContract(wxml)
  assert.match(js, /\/api\/play\/arrive/)
  assert.match(js, /wx\.getLocation\(\{ type: 'gcj02', isHighAccuracy: true/)
  assert.match(js, /\/api\/play\/checkin/)
  assert.match(js, /\/api\/play\/photo/)
  // PG-065(2026-11-17 业主批准「删说明小字」):三步凭证的副说明撤掉,只留步骤名
  assert.doesNotMatch(wxml, /仅记录到店时间|核销的时刻才算服务开始/)
})

test('探店日页面不承载领券条件与售卖读数(B5 于 2026-08-15 收窄:游戏可作扫码后体验层)', () => {
  const wxml = read('pages/play/merchant/index.wxml')
  // 仍然禁止:券类诱导与可售库存读数 —— 这两样才是「玩法当销售条件」的病根
  assert.doesNotMatch(wxml, /优惠券|领券|coupon|sellableRemaining|还可售/)
  // 游戏体验层必须锁在进店态之后:题面只在 game.show 弹层里出现,不出现在常驻区
  assert.match(wxml, /class="mp-sheet" wx:if="\{\{game\.show\}\}"/)
  assert.match(wxml, /serviceCapacity\.perHour/)
  assert.match(wxml, /expectedWaitMinutes/)
})

test('mutation 负控：移除可见降级绑定时契约确实变红', () => {
  const wxml = read('pages/play/merchant/index.wxml')
  // 只掐掉「可见插值」本身,不依赖它后面跟什么(现在后面还挂着 unknown 的再核对动作位)
  const mutated = wxml.replace('>{{degradedMessage}}', '>')
  assert.throws(() => assertVisibleFailureContract(mutated), assert.AssertionError)
})

function assertSingleMerchantConsentContract(js, wxml) {
  assert.match(js, /merchant_onsite_data_sharing/)
  assert.match(js, /merchant_redeem/)
  assert.match(js, /scopeType:\s*CONSENT_SCOPE/)
  assert.match(js, /scopeId:\s*this\.data\.node\s*&&\s*this\.data\.node\.merchantId/)
  assert.match(js, /\/api\/compliance\/consents['"]/)
  assert.match(js, /\/api\/compliance\/consents\/latest/)
  assert.match(js, /readback\.eventType === eventType/)
  assert.match(js, /readback\.scopeId/)
  assert.match(wxml, /bindtap="revokeMerchantConsent"/)
  assert.doesNotMatch(js + wxml, /邀请商家|merchant_invite|type\s*[:=]\s*2/)
}

test('核销前只授权当前门店，撤回与对面回读均有真实接线', () => {
  assertSingleMerchantConsentContract(
    read('pages/play/merchant/index.js'),
    read('pages/play/merchant/index.wxml'))
})

test('mutation 负控：删掉对面回读校验时单店授权契约变红', () => {
  const js = read('pages/play/merchant/index.js')
    .replace('readback.eventType === eventType', 'true')
  assert.throws(() => assertSingleMerchantConsentContract(
    js, read('pages/play/merchant/index.wxml')), assert.AssertionError)
})
