const test = require('node:test');
const assert = require('node:assert/strict');
const { ledgerView } = require('../../components/cy/club-direct-ledger/view-model.js');
const componentPath = '../../components/cy/club-direct-ledger/index.js';
const base = { id: 4, redemptionId: 32, topicId: 990077, payerClubId: 990005,
  merchantMemberId: 9002, perHeadFeeSnapshot: 0.01, status: 'OPEN', createTime: '2026-10-04 22:00:00' };
const scope = { kind: 'club', clubId: 990005, topicId: 0 };

test('撤销历史不加应收，已付款撤销列待退回，不伪装成平台入账', () => {
  const rows = ['VOID', 'OPEN', 'OPEN', 'SETTLED', 'REFUND_DUE', 'REFUNDED']
    .map((status, i) => ({ ...base, id: i + 1, redemptionId: i + 31, status }));
  const view = ledgerView({ code: 200, data: rows }, scope);
  assert.equal(view.activeCount, 3);
  assert.deepEqual(view.stats.map(x => x.value), ['¥0.02', '¥0.01', '¥0.01']);
  assert.equal(view.rows[0].statusText, '核销已撤销');
  assert.equal(view.rows[4].statusText, '待退回');
  assert.equal(view.rows[5].statusText, '已退回');
});

test('100笔一分钱精确合计，空账目与缺失账目分开', () => {
  const rows = Array.from({ length: 100 }, (_, i) => ({ ...base, id: i + 1 }));
  assert.equal(ledgerView({ code: 200, data: rows }, scope).stats[0].value, '¥1.00');
  assert.equal(ledgerView({ code: 200, data: [] }, scope).stats[0].value, '¥0.00');
  for (const data of [null, {}, undefined]) assert.equal(ledgerView({ code: 200, data }, scope), null);
});

test('跨付款方、错主题、重复行和非法金额拒绝整个合计', () => {
  for (const patch of [{ payerClubId: 7002 }, { perHeadFeeSnapshot: null }, { perHeadFeeSnapshot: -1 },
    { perHeadFeeSnapshot: 0.001 }, { perHeadFeeSnapshot: '1e3' }, { status: 'constructor' },
    { status: 'UNKNOWN' }, { perHeadFeeSnapshot: '9999999999999999999999' }]) {
    assert.equal(ledgerView({ code: 200, data: [{ ...base, ...patch }] }, scope), null);
  }
  assert.equal(ledgerView({ code: 200, data: [base] }, { ...scope, topicId: 1 }), null);
  assert.equal(ledgerView({ code: 200, data: [base, base] }, scope), null);
});

function mount(kind = 'club') {
  let config; let memberId = 990009;
  const requests = [];
  global.getApp = () => ({ getUserID: () => memberId, sendRequest: r => requests.push(r) });
  global.Component = c => { config = c; };
  delete require.cache[require.resolve(componentPath)]; require(componentPath);
  const vm = { ...config.methods, properties: { kind, clubId: 990005, topicId: 0 },
    data: JSON.parse(JSON.stringify(config.data)), setData(patch) { Object.assign(this.data, patch); } };
  config.lifetimes.attached.call(vm);
  return { vm, config, requests, switchMember(id) { memberId = id; } };
}

test('俱乐部与商家只读现有归属接口，不提交客户指定商家或付款写入', () => {
  const club = mount();
  assert.equal(club.requests[0].method, 'GET');
  assert.deepEqual(club.requests[0].data, { clubId: 990005 });
  assert.equal(club.requests[0].url, '/api/club-direct-merchant/club');
  const merchant = mount('merchant');
  assert.equal(merchant.requests[0].url, '/api/club-direct-merchant/merchant');
  assert.deepEqual(merchant.requests[0].data, {});
});

test('重试、换俱乐部、切号、卸载后的旧响应不能泄漏旧账或盖住错误', () => {
  const { vm, config, requests, switchMember } = mount();
  const reply = { code: 200, data: [base] };
  vm.load(); requests[0].success(reply);
  assert.equal(vm.data.state, 'loading');
  requests[1].fail(); assert.equal(vm.data.state, 'error');
  vm.load(); switchMember(9004); requests[2].success(reply);
  assert.equal(vm.data.ledger, null);
  switchMember(990009); vm.properties.clubId = 7002;
  config.observers['kind, clubId, topicId'].call(vm);
  requests[3].success(reply); assert.equal(vm.data.state, 'error');
  vm.properties.clubId = 990005; vm.load();
  config.lifetimes.detached.call(vm); requests[4].success(reply);
  assert.equal(vm.data.ledger, null);
});

test('权限异常与畸形成功都显示取回失败，不以零元掩盖', () => {
  const { vm, requests } = mount();
  requests[0].success({ code: 403, msg: '权限拒绝' });
  assert.equal(vm.data.state, 'error'); assert.equal(vm.data.ledger, null);
  vm.load(); requests[1].success({ code: 200, data: {} });
  assert.equal(vm.data.state, 'error'); assert.equal(vm.data.ledger, null);
  vm.load(); requests[2].successStatusAbnormal({ code: 401 });
  assert.equal(vm.data.state, 'error');
});

test('X040 台账行带平台结算单号，双方看同一行；缺号不臆造', () => {
  const view = ledgerView({ code: 200, data: [{ ...base, claimId: 7788 }, { ...base, id: 5, redemptionId: 33 }] }, scope);
  assert.equal(view.rows[0].claimNo, '7788');
  assert.equal(view.rows[0].disputeTitle, '直付计酬对账 核销#32 结算单#7788');
  assert.equal(view.rows[1].claimNo, '');
  assert.equal(view.rows[1].disputeTitle, '直付计酬对账 核销#33');
  const merchantView = ledgerView({ code: 200, data: [{ ...base, claimId: 7788 }] },
    { kind: 'merchant', clubId: 0, topicId: 0 });
  assert.deepEqual(merchantView.rows[0], view.rows[0]);
});

test('X040 争议入口只走平台客服会话，不新增账目写请求', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const dir = path.join(__dirname, '../../components/cy/club-direct-ledger');
  const wxml = fs.readFileSync(path.join(dir, 'index.wxml'), 'utf8');
  assert.match(wxml, /平台结算单 #\{\{item\.claimNo\}\}/);
  assert.match(wxml, /<button class="direct-dispute" open-type="contact"[^>]*send-message-title="\{\{item\.disputeTitle\}\}"/);
  assert.doesNotMatch(wxml, /bindtap=/);
  const js = fs.readFileSync(path.join(dir, 'index.js'), 'utf8');
  assert.doesNotMatch(js, /POST/);
});
