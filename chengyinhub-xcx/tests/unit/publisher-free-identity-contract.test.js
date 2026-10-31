const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ROOT = path.resolve(__dirname, '../..')
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const identity = require('../../utils/publisher-identity.js')
const ID_OK = '11010519491231002X' // 沿用既有 GB11643 校验测试号，不对应业务账号

const prices = [
  ['一个数值0票', [{ price: 0 }], false],
  ['两个明确0票', [{ price: 0 }, { price: '0.00' }], false],
  ['十进制负零', [{ price: '-0.00' }], false],
  ['没有票数组', undefined, true],
  ['空票数组', [], true],
  ['票项为空', [null], true],
  ['价为空', [{ price: null }], true],
  ['价未定义', [{}], true],
  ['价为空串', [{ price: '' }], true],
  ['价仅空格', [{ price: '  ' }], true],
  ['布尔false不能当0', [{ price: false }], true],
  ['数组不能当0', [{ price: [] }], true],
  ['十六进制串不冒充票价', [{ price: '0x0' }], true],
  ['NaN', [{ price: NaN }], true],
  ['无穷值', [{ price: Infinity }], true],
  ['正价', [{ price: 1 }], true],
  ['负价不免费', [{ price: -1 }], true],
  ['零票不能遮掉正价票', [{ price: 0 }, { price: 1 }], true],
  ['零票不能遮掉空价票', [{ price: 0 }, { price: null }], true],
]
for (const [name, tickets, required] of prices) {
  test('主题实名价款边界：' + name, () => {
    assert.equal(identity.topicRequiresIdentity({ tickets, selfPlay: false }), required)
  })
}
for (const [selfPlay, selfPlayPrice, required] of [
  [true, 0, false], [1, '0.00', false], [true, 1, true],
  [true, -1, true], [true, null, true], [true, '', true],
  [false, null, false], [0, 10, false],
]) {
  test('自玩票开启=' + selfPlay + ' 价格=' + selfPlayPrice + ' 实名=' + required, () => {
    assert.equal(identity.topicRequiresIdentity({ tickets: [{ price: 0 }], selfPlay, selfPlayPrice }), required)
  })
}

function harness(mutate) {
  let memberId = 42
  let pageDefinition
  const requests = []
  const loads = []
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    getUserID: () => memberId,
    sendRequest: (request) => requests.push(request),
  }
  global.getApp = () => app
  global.wx = {
    getStorageSync: () => undefined, setStorageSync() {}, removeStorageSync() {},
    getSystemInfoSync: () => ({ windowWidth: 375, statusBarHeight: 20 }),
    showLoading: () => loads.push('show'), hideLoading: () => loads.push('hide'),
    showToast() {}, pageScrollTo() {}, nextTick: (callback) => callback(),
    createSelectorQuery: () => ({ select: () => ({ boundingClientRect: () => ({ exec() {} }) }), exec() {} }),
  }
  const rel = 'pages/publish/fabu/index.js'
  let source = read(rel)
  if (mutate) {
    const changed = mutate(source)
    assert.notEqual(changed, source, '变异锚点必须仍存在')
    source = changed
  }
  vm.runInNewContext(source, {
    getApp: () => app, wx: global.wx,
    Page: (definition) => { pageDefinition = definition },
    require: (id) => require(path.resolve(ROOT, path.dirname(rel), id)),
    setTimeout, clearTimeout, console,
  }, { filename: rel })
  const page = Object.assign({}, pageDefinition)
  page.data = JSON.parse(JSON.stringify(pageDefinition.data))
  page.data.formData = { tickets: [{ price: 0 }], selfPlay: false }
  page.data.publishCheck = { show: true, blocking: [] }
  page.data.operationScope = 'PLAYER'
  page.setData = (patch, callback) => {
    for (const [key, value] of Object.entries(patch)) {
      const parts = key.split('.')
      const end = parts.pop()
      let target = page.data
      for (const part of parts) target = target[part]
      target[end] = value
    }
    if (callback) callback.call(page)
  }
  let published = 0
  // 2026-10-31 P-4:剧情预览「完成」通过实名闸后经 _submitFromPreview 走预检 → 发布;这里只数放行次数
  page._submitFromPreview = () => { published += 1 }
  page.setEditorState = () => {}
  page.destroyPreviewAudio = () => {}
  return { page, requests, loads, published: () => published, setMember: (id) => { memberId = id } }
}
function requirePaid(h) {
  h.page.data.formData.tickets[0].price = 1
  Object.assign(h.page.data, {
    identityRealName: '王舟', identityIdCard: ID_OK, identityConsented: true,
  })
}

test('免费主题无实名即可确认，改为收费即恢复实名闸且可改回免费', () => {
  const h = harness()
  h.page.confirmPublishCheck()
  assert.equal(h.published(), 1)
  assert.equal(h.requests.length, 0)
  assert.equal(h.page.data.identityRequired, false)
  h.page.data.formData.tickets[0].price = 1
  h.page.confirmPublishCheck()
  assert.equal(h.published(), 1)
  assert.equal(h.page.data.identityRequired, true)
  assert.equal(h.page._identityReady(), false)
  assert.equal(h.page.data.editorPage, 2, '实名没齐:送回票务页实名分区')
  h.page.data.formData.tickets[0].price = 0
  h.page.confirmPublishCheck()
  assert.equal(h.published(), 2)
})

test('收费登记单独提交；回包前不发布且连点不再发；成功后才发布', () => {
  const h = harness()
  requirePaid(h)
  h.page.confirmPublishCheck()
  h.page.confirmPublishCheck()
  assert.equal(h.requests.length, 1)
  assert.equal(h.published(), 0)
  assert.equal(h.requests[0].url, '/api/publisher/identity')
  assert.deepEqual(Object.keys(JSON.parse(h.requests[0].data)).sort(), ['consent', 'idCard', 'realName', 'source'])
  h.requests[0].success({ code: 200 })
  h.requests[0].complete({ statusCode: 200 })
  assert.equal(h.published(), 1)
  assert.equal(h.page._identityRegistering, false)
})

test('登记拒绝或HTTP502不发布，完整结束回调释放锁后可以重试', () => {
  for (const completeOnly of [false, true]) {
    const h = harness()
    requirePaid(h)
    h.page.confirmPublishCheck()
    if (completeOnly) h.requests[0].complete({ statusCode: 502 })
    else {
      h.requests[0].success({ code: 500, msg: '登记未完成' })
      h.requests[0].complete({ statusCode: 200 })
    }
    assert.equal(h.published(), 0)
    assert.equal(h.page._identityRegistering, false)
    assert.ok(h.page.data.identityError)
    assert.equal(h.page.data.identityRegistered, false)
    h.page.confirmPublishCheck()
    assert.equal(h.requests.length, 2)
  }
})

for (const [name, change] of [
  ['卸载', (h) => h.page.onUnload()],
  ['换账号', (h) => h.setMember(99)],
  ['切主办范围', (h) => { h.page.data.operationScope = 'MERCHANT' }],
  ['新安全预检', (h) => { h.page._precheckToken = 9 }],
  ['关闭确认页', (h) => h.page.closePublishCheck()],
  ['新增阻断项', (h) => { h.page.data.publishCheck.blocking.push({ label: '缺封面' }) }],
]) {
  test('迟到登记回包不能发布：' + name, () => {
    const h = harness()
    requirePaid(h)
    h.page.confirmPublishCheck()
    change(h)
    h.requests[0].success({ code: 200 })
    h.requests[0].complete({ statusCode: 200 })
    assert.equal(h.published(), 0)
    if (name !== '新增阻断项') assert.equal(h.page.data.identityRegistered, false)
  })
}

test('免费不是跳过业务检查：blocking 时零价也不能发布', () => {
  const h = harness()
  h.page.data.publishCheck.blocking.push({ label: '缺封面' })
  h.page.confirmPublishCheck()
  assert.equal(h.published(), 0)
  assert.equal(h.requests.length, 0)
})

test('负控：移除实名闸会让未登记收费主题进入登记流程，测试能识别', () => {
  const mutation = (source) => source.replace(
    "if (!this._identityReady()) { this._backToIdentity(''); return; }",
    '')
  const h = harness(mutation)
  h.page.data.formData.tickets[0].price = 1
  h.page.confirmPublishCheck()
  assert.ok(h.page.data.identityError)
  const good = harness()
  good.page.data.formData.tickets[0].price = 1
  good.page.confirmPublishCheck()
  assert.equal(good.page.data.identityError, '')
  assert.equal(good.loads.length, 0)
})

test('自玩票上送不把未填写的价格悄悄改成0', () => {
  assert.match(read('pages/publish/fabu/index.js'), /selfPlayPrice: this\.data\.formData\.selfPlay \? this\.data\.formData\.selfPlayPrice : 0,/)
})

test('场景主理人副本同样不采集实名，商家仍保留采集和失败阻断', () => {
  const source = read('components/cy/scene-route-content/index.js')
  const wxml = read('components/cy/scene-route-content/index.wxml')
  const clubForm = /if \(sceneId === 'club-apply'\) return \{([^\n]*)\}/.exec(source)[1]
  assert.doesNotMatch(clubForm, /realName|idCard|consented/)
  assert.match(source, /if \(sceneId === 'merchant-apply' && !this\.data\.identityRegistered\)/)
  const clubMarkup = wxml.slice(wxml.indexOf("wx:elif=\"{{formType === 'club-apply'}}\""), wxml.indexOf("wx:elif=\"{{formType === 'citynode'}}\""))
  assert.doesNotMatch(clubMarkup, /realName|idCard|identityConsent/)
})
