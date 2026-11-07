'use strict'
// 第十轮 f2 · j2 F-06:会员为俱乐部场次而来,却被引去「随时开玩」;付完场次后主题页仍推自玩;订单页进不了场。
//   ① 俱乐部活动卡进主题页带 via=session,主按钮是「立即报名」(选场次),不是自玩通行证;
//   ② 已付本主题场次的会员:主题页主按钮「去票夹」(后端 isSignUp 计入场次报名,见 ApiTopicController);
//   ③ 订单页:已付、未结束的票底部「开始游戏」直进 play,与票夹同一目标。
const { beforeEach, test } = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { flattenComponentToPage } = require('../helpers/component-as-page.js')

const ROOT = path.resolve(__dirname, '../..')
const DIR = 'subpackageMember/components/cy/scene-member-order-detail/'
const JS = path.join(ROOT, DIR + 'index.js')
const read = rel => fs.readFileSync(path.join(ROOT, rel), 'utf8')
const motion = require(path.join(ROOT, 'pages/topic/utils/topic-motion.js'))

let sandbox
beforeEach(() => {
  sandbox = { requests: [], navs: [] }
  global.getApp = () => ({
    globalData: {}, tips() {}, sendRequest: o => sandbox.requests.push(o),
    getRequestErrorMessage: (res, fallback) => (res && res.msg) || fallback,
    getImgUrl: src => src || '',
  })
  global.Component = config => { sandbox.config = flattenComponentToPage(config) }
  global.wx = { showToast() {}, hideLoading() {}, showLoading() {}, navigateBack() {},
    navigateTo(o) { sandbox.navs.push(o.url); if (o.complete) o.complete() } }
})
function load(row) {
  delete require.cache[require.resolve(JS)]
  require(JS)
  const vm = Object.assign({}, sandbox.config, { setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb() } })
  vm.data = Object.assign({}, sandbox.config.data)
  sandbox.requests = []; vm.data.orderId = String(row.id); vm.onLoad()
  sandbox.requests.find(r => r.url === '/api/registration/info').success({ code: 200, data: row })
  return vm
}

const future = '2099-11-04 14:30:00'

test('F-06 ① 从俱乐部场次卡进来:主按钮去选场次,不是「随时开玩」', () => {
  const info = { lifecycle: 3, selfPlay: 1, activityList: [{ id: 9 }] }
  assert.equal(motion.buildHeroCta(info, { priceText: '¥19.9' }).kind, 'selfplay', '普通入口照旧卖通行证')
  const cta = motion.buildHeroCta(info, { priceText: '¥19.9', preferSession: true })
  assert.deepEqual(cta, { kind: 'signup', text: '立即报名' })
  assert.equal(motion.buildHeroCta({ lifecycle: 3, selfPlay: 1, activityList: [] }, { preferSession: true }).kind, 'selfplay',
    '没有可报名场次时不硬推报名')
  assert.equal(motion.buildHeroCta(info, { isJoin: 1, preferSession: true }).kind, 'joined', '已购仍去票夹')
  const club = read('pages/club/detail/index.js')
  assert.match(club, /'\/pages\/topic\/index\/index\?id=' \+ id \+ '&via=session'/)
  const topic = read('pages/topic/index/index.js')
  assert.match(topic, /preferSession: this\._viaSession/)
  assert.match(topic, /that\._viaSession = options\.via === 'session'/)
})

test('F-06 ③ 订单页:已付未开始的场次票底部「开始游戏」直进 play', () => {
  const vm = load({ id: 74, ownerType: 2, ownerId: 9, registrationStatus: 2, paymentStatus: 2, verificationStatus: 0,
    refundInfo: { refundable: true }, cmsActivity: { id: 9, name: '沪上慢走', topicId: 5, startDate: future, endDate: '2099-11-04 18:00:00' } })   // 第十二轮 k2:有游戏的场次(没有游戏的票不出「开始游戏」,见 k2-no-game-ticket)
  assert.equal(vm.data.info.orderStateKey, 'not_started')
  assert.equal(vm.data.primaryCta, 'play')
  vm.startPlay()
  assert.equal(sandbox.navs[0], '/pages/play/index?activityId=9&registrationId=74&entry=ticket&topicName=' + encodeURIComponent('沪上慢走'))
  const wxml = read(DIR + 'index.wxml')
  const button = wxml.match(/wx:elif="{{([^}]+)}}">\s*<view class="primary-cta" bindtap="startPlay"[^>]*>{{([^}]+)}}<\/view>/)
  assert.ok(button)
  const evaluate = require('node:vm').runInNewContext
  assert.equal(evaluate(button[1], vm.data), true)
  assert.equal(evaluate(button[2], vm.data), '开始游戏')
})

test('F-06 ③ 待支付仍是「去支付」;已取消、已完成不出「开始游戏」', () => {
  assert.equal(load({ id: 1, ownerType: 2, registrationStatus: 1, paymentStatus: 0, payExpireTime: future, cmsActivity: { id: 9 } }).data.primaryCta, 'pay')
  assert.equal(load({ id: 2, ownerType: 2, registrationStatus: 3, paymentStatus: 4, cmsActivity: { id: 9 } }).data.primaryCta, '')
  assert.equal(load({ id: 3, ownerType: 2, registrationStatus: 2, paymentStatus: 2, verificationStatus: 1, cmsActivity: { id: 9 } }).data.primaryCta, '')
})


test('completed exploration order reuses exact registered play route as View Records, including historical records', () => {
  const evaluate = require('node:vm').runInNewContext;
  for (const endDate of ['2099-11-04 18:00:00', '2020-11-04 18:00:00']) {
    const instance = load({ id: 9350807, ownerType: 2, ownerId: 9350605, purchaseKind: 3,
      registrationStatus: 2, paymentStatus: 2, verificationStatus: 1,
      cmsActivity: { id: 9350605, name: '双店', topicId: 9170200, productType: 2, endDate },
      exploreProgress: { required: 2, redeemed: 2, completed: true, active: false } });
    assert.equal(instance.data.info.orderStateKey, 'completed');
    assert.equal(instance.data.primaryCta, 'record');
    const wxml = read(DIR + 'index.wxml');
    const element = wxml.match(/<view class="bp-cta-bar" wx:elif="{{([^}]+)}}">\s*<view class="primary-cta" bindtap="startPlay"[^>]*>([\s\S]*?)<\/view>/);
    assert.ok(element);
    assert.equal(evaluate(element[1], instance.data), true);
    const label = element[2].match(/^{{([^}]+)}}$/);
    assert.ok(label);
    assert.equal(evaluate(label[1], instance.data), '查看记录');
    instance.startPlay();
    assert.equal(sandbox.navs.at(-1), '/pages/play/index?activityId=9350605&registrationId=9350807&entry=ticket&topicName=' + encodeURIComponent('双店'));
    assert.equal(instance.data.info.verificationStatus, 1);
  }
});

test('record projection requires real exploration purchase/entitlement, registered paid owner and game source', () => {
  const base = { id: 74, ownerType: 2, ownerId: 9, purchaseKind: 3,
    registrationStatus: 2, paymentStatus: 2, verificationStatus: 1,
    cmsActivity: { id: 9, topicId: 5, productType: 2, endDate: future } };
  for (const patch of [
    { purchaseKind: 1, cmsActivity: { id: 9, topicId: 5, productType: 1 } },
    { registrationStatus: 3 }, { paymentStatus: 4 },
    { cmsActivity: { id: 9, productType: 2 } },
    { ownerId: 0, cmsActivity: { topicId: 5 } },
    { purchaseKind: null, entitlements: [{ id: 1 }] },
  ]) assert.equal(load({ ...base, ...patch }).data.primaryCta, '', JSON.stringify(patch));
  const legacy = load({ ...base, purchaseKind: null, entitlements: [{ chapterId: 9170301 }] });
  assert.equal(legacy.data.primaryCta, 'record');
});
