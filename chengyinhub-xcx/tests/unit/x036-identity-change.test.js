// X036 发布者实名改绑:自助申请 + 人工审核进度;校验位表修正(末位 2)。
//
// 负控:
//  · form-state.js 的 ID_CARD_CHECK_CODES 去掉末尾 '2' ⇒ 「校验位为 2 的号码合法」红;
//  · checkChangeForm 不判原因长数字 ⇒ 「原因里不许写证件号」红;
//  · describeChangeProgress 回显 reviewNote 给非驳回态 ⇒ 「只有驳回才带审核意见」红;
//  · 页面提交成功不清空本地填写值 ⇒ 「提交后页面不再持有明文」红。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');
const { isValidIdCard } = require('../../utils/form-state.js');

const OLD_ID = '110105199003070017';
const NEW_ID = '110105198501010012';

function loadIdentityUtil(requests) {
  const rel = 'utils/publisher-identity.js';
  const mod = { exports: {} };
  vm.runInNewContext(read(rel), {
    module: mod, exports: mod.exports,
    require: (id) => require(path.resolve(ROOT, 'utils', id)),
    getApp: () => ({ sendRequest(o) { requests.push(o); } }),
    JSON, Object, String, Number, Array,
  }, { filename: rel });
  return mod.exports;
}

test('校验位为 2 的号码合法(原表少一位,约 1/11 的人登记不上)', () => {
  assert.equal(isValidIdCard(NEW_ID), true);
  assert.equal(isValidIdCard('110105198501010013'), false);
  assert.equal(isValidIdCard(OLD_ID), true);
  assert.match(read('../chengyinhub-common/src/main/java/com/chengyinhub/common/utils/IdCardUtils.java'),
    /CHECK_CODES = \{'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'\}/, '后端校验位表必须同为 11 位');
});

test('改绑表单:格式错误前端即拒,原因必填、不许写证件号', () => {
  const util = loadIdentityUtil([]);
  const ok = { realName: '李四', idCard: NEW_ID, consented: true, reason: '登记时填错' };
  assert.equal(util.checkChangeForm(ok).ok, true);
  assert.equal(util.checkChangeForm({ ...ok, idCard: '110105198501010013' }).ok, false);
  assert.equal(util.checkChangeForm({ ...ok, consented: false }).ok, false);
  assert.equal(util.checkChangeForm({ ...ok, reason: '  ' }).message, '请填写变更原因');
  assert.match(util.checkChangeForm({ ...ok, reason: '新证 110105198501' }).message, /证件号/);
  assert.match(util.checkChangeForm({ ...ok, reason: '字'.repeat(201) }).message, /200/);
});

test('进度归一:只有驳回才带审核意见,未知状态当没有申请', () => {
  const util = loadIdentityUtil([]);
  assert.equal(util.describeChangeProgress(null), null);
  assert.equal(util.describeChangeProgress({ id: 1, status: 'WHATEVER' }), null);
  const pending = util.describeChangeProgress({ id: 1, status: 'PENDING', reviewNote: 'x' });
  assert.equal(pending.pending, true);
  assert.equal(pending.statusText, '审核中');
  assert.equal(pending.reviewNote, '');
  const rejected = util.describeChangeProgress({ id: 2, status: 'REJECTED', reviewNote: '请补充说明' });
  assert.equal(rejected.reviewNote, '请补充说明');
  assert.match(util.describeChangeProgress({ id: 3, status: 'APPROVED' }).statusText, /已通过/);
});

test('提交改绑走 /api/publisher/identity/change,证件号归一、带单独同意', () => {
  const requests = [];
  const util = loadIdentityUtil(requests);
  let result;
  util.submitIdentityChange({ realName: ' 李四 ', idCard: NEW_ID.toLowerCase(), consented: true, reason: ' 证件更换 ' }, (r) => { result = r; });
  assert.equal(requests[0].url, '/api/publisher/identity/change');
  assert.deepEqual(JSON.parse(requests[0].data), { realName: '李四', idCard: NEW_ID, reason: '证件更换', consent: true });
  requests[0].success({ code: 200, msg: '改绑申请已提交,等待平台人工审核' });
  assert.equal(result.ok, true);
});

test('已登记提示写明「改绑申请 + 客服人工审核」,商家申请页与发布确认层都给改绑入口', () => {
  assert.match(read('utils/publisher-identity.js'), /ALREADY_REGISTERED_HINT\s*=\s*'已登记[^']*改绑申请[^']*客服/);
  // 主理人的发布者实名已移到发布确认层(pages/publish/fabu,7b894270db),入口跟着实名区块走。
  for (const rel of ['pages/publish/fabu', 'pages/merchant/apply']) {
    assert.match(read(rel + '/index.wxml'), /bindtap="goIdentityChange"/);
    assert.match(read(rel + '/index.js'), /goIdentityChange\(\) \{\s*wx\.navigateTo\(\{ url: '\/subpackageMember\/identity-change\/index' \}\);/);
  }
  const appJson = JSON.parse(read('app.json'));
  const member = appJson.subPackages.find((p) => p.root === 'subpackageMember');
  assert.ok(member.pages.includes('identity-change/index'));
  for (const file of ['subpackageMember/identity-change/index.wxml', 'subpackageMember/identity-change/index.js']) {
    assert.doesNotMatch(read(file), /实名认证/, `${file} 不得出现「实名认证」用词`);
  }
});

function mountChangePage() {
  const rel = 'subpackageMember/identity-change/index.js';
  const calls = { status: null, progress: null, submit: null, cancel: null, toasts: [], modals: [] };
  const identityStub = Object.assign({}, require('../../utils/publisher-identity.js'), {
    loadIdentityStatus(cb) { calls.status = cb; },
    loadChangeProgress(cb) { calls.progress = cb; },
    submitIdentityChange(form, cb) { calls.submit = { form, cb }; },
    cancelIdentityChange(id, cb) { calls.cancel = { id, cb }; },
  });
  let definition;
  vm.runInNewContext(read(rel), {
    Page(p) { definition = p; },
    getCurrentPages: () => [{}, {}],
    wx: { navigateBack() {}, switchTab() {} },
    require(id) {
      if (id === '../../utils/publisher-identity.js') return identityStub;
      if (id === '../../utils/toast.js') return (m) => calls.toasts.push(m);
      if (id === '../../utils/modal.js') return { show(o) { calls.modals.push(o); } };
      throw new Error('unexpected require ' + id);
    },
    JSON, Object, String, Number, Array,
  }, { filename: rel });
  const page = Object.assign({}, definition, {
    data: JSON.parse(JSON.stringify(definition.data)),
    setData(patch, cb) { Object.assign(this.data, patch); if (cb) cb.call(this); },
  });
  return { page, calls };
}

test('页面:未登记只给说明,不出改绑表单', () => {
  const { page, calls } = mountChangePage();
  page.onShow();
  calls.status(false);
  assert.equal(page.data.state, 'unregistered');
  assert.equal(calls.progress, null);
  assert.match(read('subpackageMember/identity-change/index.wxml'), /wx:if="\{\{!progress \|\| !progress\.pending\}\}"/);
});

test('页面:已登记可提交改绑,提交后页面不再持有明文;待审可撤回', () => {
  const { page, calls } = mountChangePage();
  page.onShow();
  calls.status(true);
  calls.progress({ ok: true, progress: null });
  assert.equal(page.data.state, 'ready');

  page.onSubmit();
  assert.equal(calls.submit, null, '格式不合法不发请求');
  assert.ok(page.data.formError);

  page.onInput({ currentTarget: { dataset: { field: 'realName' } }, detail: { value: '李四' } });
  page.onInput({ currentTarget: { dataset: { field: 'idCard' } }, detail: { value: NEW_ID } });
  page.onInput({ currentTarget: { dataset: { field: 'reason' } }, detail: { value: '证件更换' } });
  page.onConsent({ detail: { checked: true } });
  page.onSubmit();
  assert.ok(calls.submit);
  calls.submit.cb({ ok: true, message: 'ok' });
  assert.equal(page.data.idCard, '');
  assert.equal(page.data.realName, '');
  assert.equal(page.data.consented, false);

  calls.status(true); // 提交成功后重新回读进度
  calls.progress({ ok: true, progress: { id: 9, status: 'PENDING', statusText: '审核中', pending: true, reviewNote: '' } });
  page.onCancel();
  assert.equal(calls.modals.length, 1);
  calls.modals[0].success({ confirm: true });
  assert.equal(calls.cancel.id, 9);
});
