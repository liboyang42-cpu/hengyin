const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadConfirmAction() {
  const source = read('src/components/CyConfirmAction/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1];
  return new Function(script.replace(/export\s+default\s*/, 'return '))();
}

test('CyConfirmAction 只承载高风险确认界面与原因校验，不拥有领域请求', () => {
  const component = loadConfirmAction();

  for (const propName of ['visible', 'title', 'impact', 'severity', 'confirmText', 'reasonLabel', 'reasonPlaceholder', 'loading']) {
    assert.ok(component.props[propName], `缺少 ${propName} 接口`);
  }
  assert.equal(component.methods && component.methods.manualRefundCashwithdraw, undefined, '组件不应调用提现接口');
  assert.equal(component.methods && component.methods.auditClub, undefined, '组件不应调用俱乐部接口');
  assert.match(read('src/components/CyConfirmAction/index.vue'), /:close-on-click-modal="false"/);
  assert.match(read('src/components/CyConfirmAction/index.vue'), /:close-on-press-escape="false"/);
  assert.match(read('src/components/CyConfirmAction/index.vue'), /:show-close="false"/);
});

test('CyConfirmAction 阻止空白原因，并只把裁剪后的原因交回页面', () => {
  const component = loadConfirmAction();
  const emitted = [];
  const vm = {
    reason: '   ',
    reasonError: '',
    $emit: (...args) => emitted.push(args)
  };

  component.methods.submit.call(vm);
  assert.equal(vm.reasonError, '请填写处理原因');
  assert.deepEqual(emitted, []);

  vm.reason = '  已核实收款失败，转交复核。  ';
  component.methods.submit.call(vm);
  assert.equal(vm.reasonError, '');
  assert.deepEqual(emitted, [['confirm', '已核实收款失败，转交复核。']]);

  vm.loading = true;
  component.methods.submit.call(vm);
  assert.deepEqual(emitted, [['confirm', '已核实收款失败，转交复核。']], '提交中不得再次 emit');
});

test('CyConfirmAction 提交期间不允许关闭，空闲时才交还受控开关', () => {
  const component = loadConfirmAction();
  const emitted = [];
  const vm = { loading: true, $emit: (...args) => emitted.push(args) };

  component.methods.requestClose.call(vm);
  assert.deepEqual(emitted, []);

  let closed = false;
  vm.loading = false;
  component.methods.requestClose.call(vm, () => { closed = true; });
  assert.deepEqual(emitted, [['update:visible', false]]);
  assert.equal(closed, true);
  assert.match(read('src/components/CyConfirmAction/index.vue'), /:disabled="loading"/);
  assert.match(read('src/components/CyConfirmAction/index.vue'), /:loading="loading"/);
});

test('提现人工回补和俱乐部驳回接入同一确认组件，页面继续持有权限与请求', () => {
  const cashwithdraw = read('src/views/business/cashwithdraw/index.vue');
  const club = read('src/views/business/club/index.vue');

  for (const source of [cashwithdraw, club]) {
    assert.ok(source.includes("import CyConfirmAction from '@/components/CyConfirmAction'"));
    assert.ok(source.includes('<cy-confirm-action'));
    assert.ok(source.includes('@confirm='));
  }

  assert.match(cashwithdraw, /v-hasPermi="\['business:cashwithdraw:manage'\]"/);
  assert.match(cashwithdraw, /submitManual\(reason\)/);
  assert.match(cashwithdraw, /manualRefundCashwithdraw\(\{ id: this\.manualForm\.id, status: this\.manualForm\.status, manualNote: reason \}\)/);
  assert.match(cashwithdraw, /this\.\$modal\.msgSuccess\(response\.msg\)/);
  assert.match(club, /v-hasPermi="\['business:club:audit'\]"/);
  assert.match(club, /submitReject\(reason\)/);
  assert.match(club, /auditClub\(\{ id: this\.rejectTarget\.id, status: 2, rejectReason: reason \}\)/);
  assert.match(club, /:reason-maxlength="200"/);
  assert.match(read('src/components/CyConfirmAction/index.vue'), /reasonMaxlength/);
  assert.match(read('src/components/CyConfirmAction/index.vue'), /show-word-limit/);
  assert.match(club, /保存为俱乐部档案的驳回原因/);
  assert.doesNotMatch(club, /告知申请方/);
});
