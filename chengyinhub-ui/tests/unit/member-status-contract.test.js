const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/member/index.vue'), 'utf8');
function memberVm() {
  const calls = [];
  const body = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import[^\n]*\n/gm, '')
    .replace(/export\s+default\s*/, 'return ');
  const component = new Function('PointsDetail', 'BalanceDetail', 'CyPageHeader', 'CyStatusTag', 'updateMember', body)(
    {}, {}, {}, {}, payload => { calls.push(payload); return Promise.resolve({}); }
  );
  const vm = { ...component.data(), $modal: { msgSuccess() {} }, getList() {}, resetForm() {} };
  for (const [name, method] of Object.entries(component.methods)) vm[name] = method.bind(vm);
  vm.getList = () => {};
  vm.$refs = { form: { validate(done) {
    const rules = vm.rules.status || [];
    let invalid = false;
    for (const rule of rules) rule.validator({}, vm.form.status, error => { invalid ||= !!error; });
    done(!invalid);
  } }, levelForm: { validate(done) { done(true); } } };
  return { component, vm, calls };
}
const unknown = [0, '0', null, undefined, '', ' ', ' 1', '2 ', '01', 3, -1, NaN, Infinity, true, false, '__proto__', 'constructor', 'toString', {}, []];

test('会员专用选项为后端数字1/2，选择器不消费共享用户字典', () => {
  const { component, vm } = memberVm();
  assert.deepEqual(vm.memberStatusOptions, [{ value: 1, label: '启用' }, { value: 2, label: '禁用' }]);
  assert.ok(!component.dicts.includes('sys_user_status'));
  assert.match(source, /:radioData="memberStatusOptions"/);
  assert.doesNotMatch(source, /:filter="\['1,2'\]"/);
});

test('会员标签和tone只承认精确1/2，未知值不显示正常或注销', () => {
  const { vm } = memberVm();
  for (const status of [1, '1']) {
    assert.equal(vm.memberStatusLabel(status), '启用');
    assert.equal(vm.memberStatusTone(status), 'success');
  }
  for (const status of [2, '2']) {
    assert.equal(vm.memberStatusLabel(status), '禁用');
    assert.equal(vm.memberStatusTone(status), 'danger');
  }
  for (const status of unknown) {
    assert.equal(vm.memberStatusLabel(status), '未知状态');
    assert.equal(vm.memberStatusTone(status), 'neutral');
  }
});

test('概览只按已知启用禁用统计，未知不计入任一状态', () => {
  const { component, vm } = memberVm();
  vm.memberList = [1, '1', 2, '2', ...unknown].map(status => ({ status }));
  const overview = component.computed.memberOverview.call(vm);
  assert.equal(overview[0].value, vm.memberList.length);
  assert.equal(overview[1].value, 2);
  assert.equal(overview[1].label, '启用状态');
  assert.doesNotMatch(overview[1].note, /可用|登录/);
  assert.equal(overview[2].value, 2);
  assert.doesNotMatch(overview[2].label, /注销/);
});

test('打开状态表单只归一明确1/2，未知行要求重新选择', () => {
  const { vm } = memberVm();
  for (const status of [1, '1', 2, '2']) {
    vm.handleStatus({ id: 71, status });
    assert.deepEqual(vm.form, { id: 71, status: status === 1 || status === '1' ? 1 : 2 });
  }
  for (const status of unknown) {
    vm.handleStatus({ id: 71, status });
    assert.deepEqual(vm.form, { id: 71, status: null });
  }
});

test('实际表单validator仅接受已选择的数字1/2', () => {
  const { vm } = memberVm();
  assert.equal(vm.rules.status.length, 1);
  for (const status of [1, 2, '1', '2', ...unknown]) {
    let called = false;
    vm.rules.status[0].validator({}, status, error => {
      called = true;
      assert.equal(!!error, status !== 1 && status !== 2);
    });
    assert.equal(called, true);
  }
});

test('提交只发送{id,status}，未知值即使被表单错误放行也不发送', async () => {
  const { vm, calls } = memberVm();
  for (const status of [1, '1', 2, '2']) {
    vm.handleStatus({ id: 71, status });
    vm.form.point = 900;
    vm.form.password = 'never-sent';
    vm.submitForm();
    await Promise.resolve();
    assert.deepEqual(calls.at(-1), { id: 71, status: status === 1 || status === '1' ? 1 : 2 });
    assert.equal(vm.dialogStatus, false);
  }
  const before = calls.length;
  for (const status of ['1', '2', ...unknown]) {
    vm.form = { id: 71, status };
    vm.submitForm();
  }
  vm.$refs.form.validate = done => done(true);
  for (const status of ['1', '2', ...unknown]) {
    vm.form = { id: 71, status };
    vm.submitForm();
  }
  await Promise.resolve();
  assert.equal(calls.length, before);
});

test('会员等级提交保留既有独立流程与最小请求', async () => {
  const { vm, calls } = memberVm();
  vm.levelForm = { id: 71, levelId: 4, status: 2, nickname: 'not-sent' };
  vm.dialogLevel = true;
  vm.submitLevelForm();
  await Promise.resolve();
  assert.deepEqual(calls, [{ id: 71, levelId: 4 }]);
  assert.equal(vm.dialogLevel, false);
});

test('打开和取消状态表单不发写请求、不改变会员行', () => {
  const { vm, calls } = memberVm();
  const row = { id: 71, status: '1' };
  vm.handleStatus(row);
  vm.form.status = 2;
  vm.cancel();
  assert.equal(vm.dialogStatus, false);
  assert.deepEqual(vm.form, { id: null, status: null });
  assert.deepEqual(row, { id: 71, status: '1' });
  assert.deepEqual(calls, []);
});
