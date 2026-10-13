// 第二轮拍板第 4 条(2026-09-17):后台撤销核销弹窗加勾选「同时冲回因这次核销发的积分和经验」。
// 默认不勾;勾了才把 clawbackRewards=true 发给服务端。原因必填不变。
// 券核销撤销(coupon/ledger.vue)不加:券核销本身不发积分/经验/券,加了就是假按钮。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');
const source = fs.readFileSync(path.join(uiRoot, 'src/views/business/registration/index.vue'), 'utf8');
const ledgerSource = fs.readFileSync(path.join(uiRoot, 'src/views/business/coupon/ledger.vue'), 'utf8');

function loadRegistrationPage(reverseRedemption, reverseParentOrderRedemptions) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{[^}]*\} from "@\/api\/business\/registration"$/m, 'const { reverseRedemption, reverseParentOrderRedemptions } = dependencies;')
    .replace(/^import (\w+) from .+$/gm, 'const $1 = {};')
    .replace(/^import \{[^}]*\} from .+$/gm, '');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))({ reverseRedemption, reverseParentOrderRedemptions });
}

function createVm(component, sent) {
  const vm = Object.assign(component.data(), { toasts: [], reloads: 0 });
  vm.$modal = { msgSuccess: (m) => vm.toasts.push(m), msgError: (m) => vm.toasts.push('ERR:' + m) };
  vm.loadRedemptions = () => { vm.reloads += 1; };
  vm.getList = () => { vm.reloads += 1; };
  for (const name of ['handleReverseRedeem', 'handleReverseParentOrder', 'submitReverseRedeem']) vm[name] = component.methods[name].bind(vm);
  return vm;
}

test('撤销弹窗有冲回勾选,文案说清勋章不收回与连带券规则', () => {
  assert.match(source, /<el-checkbox v-model="reverseForm\.clawbackRewards">同时冲回因这次核销发的积分和经验<\/el-checkbox>/);
  assert.match(source, /勋章不收回/);
  assert.match(source, /v-hasPermi="\['business:registration:reverseRedeem'\]"/);
  assert.doesNotMatch(ledgerSource, /clawbackRewards|冲回/, '券核销撤销不发奖励,不加假勾选');
});

test('默认不勾:只撤销不冲回;勾上才带 clawbackRewards=true', async () => {
  const sent = [];
  const component = loadRegistrationPage((data) => { sent.push(data); return Promise.resolve({}); });
  const vm = createVm(component, sent);

  vm.handleReverseRedeem({ id: 31 });
  assert.equal(vm.reverseOpen, true);
  assert.equal(vm.reverseForm.clawbackRewards, false, '冲回是对玩家的扣减,默认不勾');
  vm.reverseForm.reason = '  店员扫错票  ';
  await vm.submitReverseRedeem();
  assert.deepEqual(sent[0], { redemptionId: 31, reason: '店员扫错票', clawbackRewards: false });

  vm.handleReverseRedeem({ id: 32 });
  assert.equal(vm.reverseForm.clawbackRewards, false, '上一次的勾选不得带到下一条');
  vm.reverseForm.reason = '核错项目';
  vm.reverseForm.clawbackRewards = true;
  await vm.submitReverseRedeem();
  assert.deepEqual(sent[1], { redemptionId: 32, reason: '核错项目', clawbackRewards: true });
  assert.equal(vm.reverseOpen, false);
  assert.equal(vm.reloads, 4, '两次成功各重读流水与列表');
});

test('原因为空不发请求', async () => {
  const sent = [];
  const component = loadRegistrationPage((data) => { sent.push(data); return Promise.resolve({}); });
  const vm = createVm(component, sent);
  vm.handleReverseRedeem({ id: 33 });
  vm.reverseForm.clawbackRewards = true;
  vm.reverseForm.reason = '   ';
  await vm.submitReverseRedeem();
  assert.deepEqual(sent, []);
  assert.deepEqual(vm.toasts, ['ERR:撤销原因不能为空']);
});

test('父订单撤销仅发送原父单且保留原因/奖励选项，失败不报成功', async () => {
 const sent=[];let fail=false;
 const component=loadRegistrationPage(()=>{throw Error('不得调单章');}, data=>{sent.push(data);return fail?Promise.reject(Error()):Promise.resolve({data:[31,32]});});
 const vm=createVm(component);vm.redeemParentOrderNo='PARENT-1';vm.handleReverseParentOrder();
 assert.equal(vm.reverseForm.clawbackRewards,false);vm.reverseForm.reason='核错项目';vm.reverseForm.clawbackRewards=true;
 await vm.submitReverseRedeem();assert.deepEqual(sent[0],{parentOrderNo:'PARENT-1',reason:'核错项目',clawbackRewards:true});assert.equal(vm.reloads,2);
 fail=true;vm.handleReverseParentOrder();vm.reverseForm.reason='重试';const count=vm.toasts.length;
 await vm.submitReverseRedeem();assert.equal(vm.toasts.length,count);assert.equal(vm.reverseOpen,true);assert.equal(vm.reverseSubmitting,false);
 assert.match(source,/v-hasPermi="\['business:registration:reverseRedeem'\]" @click="handleReverseParentOrder"/);
});
