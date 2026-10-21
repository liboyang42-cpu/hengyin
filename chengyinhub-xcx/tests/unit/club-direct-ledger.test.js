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

const idFields = ['id', 'redemptionId', 'topicId', 'payerClubId', 'merchantMemberId'];
const badIds = [true, false, [], [1], {}, { valueOf: () => 1 }, null, undefined,
  '', ' ', 0, -1, 1.5, '1.5', NaN, Infinity, Number.MAX_SAFE_INTEGER + 1,
  '9007199254740992', '1e0', '0x1', Symbol('id')];
for (const field of idFields) {
  test(field + ' 拒绝非标量、非整数和不安全 ID，不能强转成有效账目', () => {
    const unitRow = Object.fromEntries(idFields.map(key => [key, 1]));
    for (const value of badIds) {
      assert.equal(ledgerView({ code: 200, data: [{ ...base, ...unitRow, [field]: value }] },
        { kind: 'club', clubId: 1, topicId: 0 }), null, field + ': ' + typeof value);
    }
  });
}

test('非法 ID 对象不得执行强转钩子', () => {
  let calls = 0;
  const hostile = { [Symbol.toPrimitive]() { calls++; throw new Error('coercion'); } };
  for (const field of idFields) {
    assert.equal(ledgerView({ code: 200, data: [{ ...base, [field]: hostile }] }, scope), null);
  }
  const view = ledgerView({ code: 200, data: [{ ...base, claimId: hostile }] }, scope);
  assert.ok(view);
  assert.equal(view.rows[0].claimNo, '');
  assert.equal(calls, 0);
});

test('合法数字及数字字符串 ID 保留原值，正常 scope 和重复行保护不变', () => {
  for (const stringify of [value => value, value => String(value)]) {
    const row = { ...base };
    for (const key of idFields) row[key] = stringify(row[key]);
    const view = ledgerView({ code: '200', data: [row] },
      { kind: 'club', clubId: stringify(base.payerClubId), topicId: stringify(base.topicId) });
    assert.equal(view.rows[0].id, row.id);
    assert.equal(view.rows[0].redemptionId, row.redemptionId);
    assert.equal(view.stats[0].value, '¥0.01');
    assert.ok(ledgerView({ code: 200, data: [row] }, { kind: 'merchant', clubId: 0, topicId: 0 }));
  }
  assert.equal(ledgerView({ code: 200, data: [base, { ...base, id: String(base.id) }] }, scope), null);
});

test('scope ID 也不得接受布尔、数组或对象，空列表不能绕过校验', () => {
  for (const value of badIds) {
    for (const data of [[], [base]]) {
      assert.equal(ledgerView({ code: 200, data }, { ...scope, clubId: value }), null);
      if (value !== null && value !== undefined && value !== 0) {
        assert.equal(ledgerView({ code: 200, data }, { ...scope, topicId: value }), null);
      }
    }
  }
});

test('合法原结算单号原样展示，缺失或非法单号不伪造也不丢合法账目', () => {
  for (const claimId of [7788, '007788', Number.MAX_SAFE_INTEGER, String(Number.MAX_SAFE_INTEGER)]) {
    const row = { ...base, claimId };
    const club = ledgerView({ code: 200, data: [row] }, scope);
    const merchant = ledgerView({ code: 200, data: [row] }, { kind: 'merchant', topicId: 0 });
    assert.equal(club.rows[0].claimNo, String(claimId));
    assert.deepEqual(club.rows, merchant.rows);
  }
  for (const claimId of badIds) {
    const view = ledgerView({ code: 200, data: [{ ...base, claimId }] }, scope);
    assert.ok(view);
    assert.equal(view.rows.length, 1);
    assert.equal(view.rows[0].claimNo, '');
    assert.equal(view.stats[0].value, '¥0.01');
  }
  const fs = require('node:fs');
  const path = require('node:path');
  const wxml = fs.readFileSync(path.join(__dirname, '../../components/cy/club-direct-ledger/index.wxml'), 'utf8');
  assert.match(wxml, /wx:if="\{\{item\.claimNo\}\}"[^>]*>原结算单 #\{\{item\.claimNo\}\}/);
  assert.doesNotMatch(wxml, /open-type="contact"|bindtap=.*pay|仲裁/);
});

test('负控：恢复旧强转校验，布尔 ID 会再次产出有效一元账目', () => {
  const fs = require('node:fs');
  const path = require('node:path');
  const vm = require('node:vm');
  const source = fs.readFileSync(path.join(__dirname, '../../components/cy/club-direct-ledger/view-model.js'), 'utf8');
  const guard = "  if (typeof value !== 'number' && (typeof value !== 'string' || !/^\\d+$/.test(value))) return false;\n";
  assert.ok(source.includes(guard), '负控必须精确命中类型边界');
  const sandbox = { module: { exports: {} } };
  vm.runInNewContext(source.replace(guard, ''), sandbox);
  const row = { ...base, ...Object.fromEntries(idFields.map(key => [key, true])), perHeadFeeSnapshot: 1 };
  const response = { code: 200, data: [row] };
  const unitScope = { kind: 'club', clubId: 1, topicId: 0 };
  assert.equal(ledgerView(response, unitScope), null);
  assert.equal(sandbox.module.exports.ledgerView(response, unitScope).stats[0].value, '¥1.00');
});
