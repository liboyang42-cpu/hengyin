// 拍板 2026-09-16 #12(商家售后超时自动转平台)在后台退款台的呈现合同。
//
// 病:商家 24 小时不回应,系统把 refund_application.platform_takeover_at 写上并通知玩家,
// 但后台退款台看不到「这单已转平台」——平台客服找不到要接手的单。本测试钉住三件事:
// ① 列表标签(仅已转平台的行显示) ② 按是否已转平台筛选 ③ 按转平台时间排序,且参数失败关闭。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadRefundWorkbench(dependencies) {
  const source = read('src/views/business/finance/refund/index.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ listRefund, addRefund, reviewRefund \} from '@\/api\/business\/refund'$/m, 'const { listRefund, addRefund, reviewRefund } = dependencies;')
    .replace(/^import auth from '@\/plugins\/auth'$/m, 'const auth = dependencies.auth;')
    .replace(/^import (ActivityCancelRefundCases|ManualPayoutQueue|Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies);
}

function viewModel(query) {
  const component = loadRefundWorkbench({
    listRefund: () => Promise.resolve({ rows: [], total: 0 }),
    addRefund: () => Promise.resolve(),
    reviewRefund: () => Promise.resolve(),
    auth: { hasPermi: () => true }
  });
  const vm = Object.assign(component.data(), {
    $router: { replace: () => {} },
    getList: () => {},
    parseTime: (value) => String(value)
  });
  component.methods.loadRoute.call(vm, query || {});
  return { component, vm };
}

test('已转平台的行显示「商家超时·已转平台」标签,未转的行不显示', () => {
  const source = read('src/views/business/finance/refund/index.vue');
  // 守卫必须挂在 cy-status-tag 自己身上:[^>]* 不跨标签,标签外随便哪里出现
  // platformTakeoverAt 都不算数(第一版断言用 [\s\S]*? 跨到脚本里的文案,负控不红)。
  assert.match(source, /<cy-status-tag[^>]*v-if="scope\.row\.platformTakeoverAt"[^>]*label="商家超时·已转平台"/,
    '标签必须只看 platformTakeoverAt,不能空手挂在所有行上');
  assert.doesNotMatch(source, /<cy-status-tag[^>]*v-if="true"[^>]*商家超时·已转平台/,
    '无守卫的标签 = 每张退款单都显示「已转平台」');
  assert.match(source, /转平台 \{\{ takeoverTimeText\(scope\.row\.platformTakeoverAt\) \}\}/,
    '转平台时间要显示出来,客服才知道这单什么时候转过来的');
});

test('筛选与排序控件绑定到 queryParams,取值与后端合同一致(1=已转/0=未转/排序=1)', () => {
  const source = read('src/views/business/finance/refund/index.vue');
  assert.match(source, /v-model="queryParams\.platformTakeover"/);
  assert.match(source, /label="商家超时·已转平台" :value="1"/);
  assert.match(source, /label="未转平台" :value="0"/);
  assert.match(source, /v-model="queryParams\.takeoverOrder"/);
  assert.match(source, /label="按转平台时间" :value="1"/);
});

test('路由回填筛选后,请求参数确实带上 platformTakeover=1 与 takeoverOrder=1', () => {
  const { vm } = viewModel({ platformTakeover: '1', takeoverOrder: '1' });

  assert.equal(vm.queryParams.platformTakeover, 1);
  assert.equal(vm.queryParams.takeoverOrder, 1);
});

test('畸形参数失败关闭:只认 1/0 与排序 1,其余回落到「全部/默认排序」', () => {
  const { vm } = viewModel({ platformTakeover: '3', takeoverOrder: '0' });
  assert.equal(vm.queryParams.platformTakeover, null, '3 不是合法筛选值');
  assert.equal(vm.queryParams.takeoverOrder, null, '只有 1 才是按转平台时间排序');

  const arrayValue = viewModel({ platformTakeover: ['1'] }).vm;
  assert.equal(arrayValue.queryParams.platformTakeover, 1, '数组取首值(与既有筛选同一口径)');

  const empty = viewModel({ platformTakeover: '', takeoverOrder: '' }).vm;
  assert.equal(empty.queryParams.platformTakeover, null, '清空 = 全部');
  assert.equal(empty.queryParams.takeoverOrder, null, '清空 = 默认排序');
});

test('已生效的筛选在筛选条上可见可移除', () => {
  const { component, vm } = viewModel({ platformTakeover: '1', takeoverOrder: '1' });
  const filters = component.computed.activeFilters.call(vm);

  assert.ok(filters.some(item => item.key === 'platformTakeover' && item.value === '商家超时·已转平台'));
  assert.ok(filters.some(item => item.key === 'takeoverOrder' && item.value === '按转平台时间'));

  const plain = viewModel({}).vm;
  const plainFilters = component.computed.activeFilters.call(plain);
  assert.equal(plainFilters.some(item => item.key === 'platformTakeover'), false, '没筛就不出筛选条');
});

test('详情抽屉对已转平台的单补一条事实,普通单不加噪音', () => {
  const { component, vm } = viewModel({});
  vm.sourceLabel = component.methods.sourceLabel;
  vm.reviewLabel = component.methods.reviewLabel;
  vm.takeoverTimeText = () => '2026-09-17 08:00';

  vm.detailRow = { status: 0, payoutStatus: 0, platformTakeoverAt: '2026-09-17T08:00:00.000+08:00' };
  const facts = component.computed.refundHeaderFacts.call(vm);
  assert.ok(facts.some(item => item.label === '转平台' && item.value.includes('2026-09-17 08:00')));

  vm.detailRow = { status: 0, payoutStatus: 0, platformTakeoverAt: null };
  const plainFacts = component.computed.refundHeaderFacts.call(vm);
  assert.equal(plainFacts.some(item => item.label === '转平台'), false);
});
