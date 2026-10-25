'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('../helpers/ui-sandbox-vm.js')
const { createRequestClient } = require('../../utils/transport/request-client.js')
const sourcePath = path.resolve(__dirname, '../../subpackageMember/components/cy/scene-roam-poi-detail/index.js')
const readSource = () => fs.readFileSync(sourcePath, 'utf8')

function mount({ source = readSource(), storage = new Map(), owner = 7, poiId = '21', wxOverrides = {} } = {}) {
  let definition
  const requests = []; const locations = []; const events = []; const notices = []
  const identity = { owner }
  const client = createRequestClient({
    wxRequest(options) { requests.push(options); return { abort() {} } },
    getBaseUrl: () => 'https://fixture.invalid',
    getAuthorization: () => 'fixture-session-' + identity.owner,
    getUserID: () => identity.owner,
  })
  const app = { getUserID: () => identity.owner, sendRequest: options => client.send(options) }
  const wx = {
    getLocation(options) { locations.push(options) },
    getStorageSync(key) { return storage.get(key) },
    setStorageSync(key, value) { storage.set(key, JSON.parse(JSON.stringify(value))) },
    removeStorageSync(key) { storage.delete(key) },
    showToast(options) { notices.push(options.title) },
    ...wxOverrides,
  }
  vm.runInNewContext(source, {
    Component(options) { definition = options }, getApp: () => app, wx,
    require(id) { return require(path.resolve(path.dirname(sourcePath), id)) },
  }, { filename: sourcePath })
  const instance = {
    data: { ...JSON.parse(JSON.stringify(definition.data)), poiId, merchantId: '' },
    setData(patch) {
      for (const [key, value] of Object.entries(patch)) {
        const keys = key.split('.'); let cursor = this.data
        for (const part of keys.slice(0, -1)) cursor = cursor[part]
        cursor[keys.at(-1)] = value
      }
    },
    triggerEvent(name, detail) { events.push({ name, detail }) },
    ...definition.methods,
  }
  const respond = (request, body, statusCode = 200) => {
    const response = { statusCode, data: body }
    request.success(response)
    request.complete(response)
  }
  const get = (flags = {}, statusCode = 200) => respond(requests.at(-1), {
    code: statusCode === 200 ? 200 : statusCode,
    data: { poiId: Number(instance.data.poiId), status: 1, validationMethod: 5, completed: false, needRedeem: false, ...flags },
  }, statusCode)
  definition.lifetimes.attached.call(instance)
  assert.equal(requests[0].method, 'GET')
  get()
  return {
    instance, storage, requests, locations, events, notices, identity, respond, get,
    posts: () => requests.filter(request => request.method === 'POST' && /\/api\/city\/nodes\/\d+\/complete$/.test(request.url)),
    begin() { instance.completeNode(''); locations.at(-1).success({ latitude: 31.2, longitude: 121.5 }); return requests.at(-1) },
    detach() { definition.lifetimes.detached.call(instance) },
  }
}

function assertLocationSingleUse(source) {
  const h = mount({ source })
  const post = h.begin(); const location = h.locations[0]
  h.respond(post, { code: 200, data: { needRedeem: false, couponId: null } })
  assert.equal(h.storage.size, 0)
  assert.equal(h.events.filter(event => event.name === 'completed').length, 1)
  location.success({ latitude: 31.2, longitude: 121.5 })
  assert.equal(h.posts().length, 1, '同一 native success 只能消费一次，清锁后也不得再次 POST')
  location.fail({ errMsg: 'getLocation:fail auth deny' })
  assert.equal(h.instance.data.interactionState, 'idle', '已消费的 native fail 不得覆盖成功 UI')
}

test('真实 request-client：定位 success 在成功清锁后仍只能消费一次', () => assertLocationSingleUse())

test('真实 request-client：定位 fail 先到后迟来的 success 不得发送，显式重试可发送', () => {
  const h = mount()
  h.instance.completeNode('')
  h.locations[0].fail({ errMsg: 'getLocation:fail system error' })
  h.locations[0].success({ latitude: 31.2, longitude: 121.5 })
  assert.equal(h.posts().length, 0)
  h.instance.recoverInteraction()
  h.locations[1].success({ latitude: 31.2, longitude: 121.5 })
  assert.equal(h.posts().length, 1)
})

function assertReceiptUnknown(data, source) {
  const h = mount({ source }); const post = h.begin()
  h.respond(post, { code: 200, data })
  assert.equal(h.storage.size, 1, '矛盾或异对象回执不能清原 pending')
  assert.equal(h.instance.data.checkinPending, true)
  assert.equal(h.instance.data.interactionState, 'unknown')
  assert.equal(h.events.length, 0, '未知回执不能发完成事件或开码')
  assert.equal(h.notices.includes('打卡成功'), false)
  h.instance.recoverInteraction(); h.get()
  assert.equal(h.posts().length, 1, '只能 GET 确认，不能重放 POST')
}

for (const couponId of [null, undefined, 0, -1, true, '088', 'x', [], {}]) {
  test('真实 request-client：待核销须有严格正 couponId '+JSON.stringify(couponId), () => {
    assertReceiptUnknown({ needRedeem: true, couponId, alreadyClaimed: true })
  })
}
for (const identity of [{ poiId: 99 }, { poiId: null }, { poiId: '021' }, { poiId: true }, { id: 99 }, { poiId: 21, id: 22 }]) {
  test('真实 request-client：显式回执标识必须匹配原 POI '+JSON.stringify(identity), () => {
    assertReceiptUnknown({ ...identity, needRedeem: false, couponId: null })
  })
}
for (const data of [
  { needRedeem: false, couponId: null },
  { poiId: 21, needRedeem: false, couponId: null },
  { poiId: '21', id: 21, needRedeem: true, couponId: '88', alreadyClaimed: true },
]) {
  test('真实 request-client：合法完成或待核销回执保持原行为 '+JSON.stringify(data), () => {
    const h = mount(); h.respond(h.begin(), { code: 200, data })
    assert.equal(h.storage.size, 0)
    assert.equal(h.instance.data.checkinPending, false)
    assert.equal(h.instance.data.node.completed, !data.needRedeem)
    assert.equal(h.events.filter(event => event.name === 'completed').length, 1)
    assert.equal(h.events.some(event => event.name === 'open'), data.needRedeem)
  })
}
for (const statusCode of [202, 410, 500, 503]) {
  test('真实 request-client：HTTP '+statusCode+' 即便含明确拒绝文本也不能释放', () => {
    const h = mount(); h.respond(h.begin(), { code: 500, msg: '答案不正确' }, statusCode)
    assert.equal(h.storage.size, 1)
    assert.equal(h.instance.data.interactionState, 'unknown')
  })
}

test('真实 request-client：仅 HTTP200 的明确写前拒绝允许重新作答', () => {
  const h = mount(); h.respond(h.begin(), { code: 500, msg: '答案不正确' })
  assert.equal(h.storage.size, 0)
  assert.equal(h.instance.data.interactionActionText, '重新作答')
  h.instance.completeNode('新答案'); h.locations.at(-1).success({ latitude: 31.2, longitude: 121.5 })
  assert.equal(h.posts().length, 2)
  assert.equal(h.posts()[1].data.answer, '新答案')
})
for (const failure of ['timeout', 'empty']) {
  test('真实 request-client：'+failure+' 冷重开仍只 GET；负查询不能证明未写', () => {
    const h = mount(); const post = h.begin()
    if (failure === 'timeout') post.fail({ errMsg: 'request:fail timeout' })
    else h.respond(post, { code: 200, data: null })
    h.detach()
    const reopened = mount({ storage: h.storage })
    reopened.instance.completeNode('')
    assert.equal(reopened.posts().length, 0)
    assert.equal(reopened.locations.length, 0)
    assert.equal(reopened.storage.size, 1)
    reopened.instance.recoverInteraction(); reopened.get({ completed: true, needRedeem: false })
    assert.equal(reopened.storage.size, 0)
    assert.equal(reopened.events.at(-1).detail.recovered, true)
    assert.equal(reopened.notices.includes('打卡成功'), false)
  })
}

test('真实 request-client：落盘失败不发送，保留可见恢复入口', () => {
  const h = mount({ wxOverrides: { setStorageSync() { throw Error('disk') } } })
  h.begin()
  assert.equal(h.posts().length, 0)
  assert.match(h.instance.data.interactionText, /存储/)
})

test('真实 request-client：换 POI 后迟回包只清原 key，保留新据点代次', () => {
  const h = mount(); const old = h.begin(); const oldKey = [...h.storage.keys()][0]
  h.instance.data.poiId = '22'; h.instance._load(); h.get()
  h.begin()
  h.respond(old, { code: 200, data: { needRedeem: false, couponId: null } })
  assert.equal(h.storage.has(oldKey), false)
  assert.equal(h.storage.size, 1)
  assert.equal([...h.storage.values()][0].poiId, 22)
  assert.equal(h.instance.data.node.completed, false)
})

test('真实 request-client：换 owner 后传输层抑制旧成功，新身份 key 保留', () => {
  const h = mount(); const old = h.begin()
  h.identity.owner = 8; h.instance._load(); h.get(); h.begin()
  h.respond(old, { code: 200, data: { needRedeem: false, couponId: null } })
  assert.equal(h.storage.size, 2)
  assert.equal(h.events.length, 0)
  assert.equal(h.instance.data.checkinPending, true)
})

test('真实 request-client 负控：删除 native 消费、券 ID 或 POI 校验分别必红', () => {
  const source = readSource()
  const cases = [
    [source.replaceAll(' || locationSettled', ''), assertLocationSingleUse],
    [source.replace('&& matchingPoi && validCoupon', '&& matchingPoi'), mutant => assertReceiptUnknown({ needRedeem: true, couponId: null, alreadyClaimed: true }, mutant)],
    [source.replace('&& matchingPoi && validCoupon', '&& validCoupon'), mutant => assertReceiptUnknown({ poiId: 99, needRedeem: false, couponId: null }, mutant)],
  ]
  for (const [mutant, verify] of cases) {
    assert.notEqual(mutant, source)
    assert.throws(() => verify(mutant), { name: 'AssertionError' })
    assert.doesNotThrow(() => verify(source))
  }
})
