const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');
function read(relativePath) { return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8'); }

function loadCases(dependencies) {
  const source = read('src/views/business/finance/refund/ActivityCancelRefundCases.vue');
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import \{[\s\S]*?\} from '@\/api\/business\/refund'/m,
      'const { assessActivityCancelRefundCase, getActivityCancelRefundCase, handleActivityCancelRefundCase, listActivityCancelRefundCases, reopenActivityCancelRefundAssessment, reworkActivityCancelRefundCase, uploadActivityCancelRefundEvidence, verifyActivityCancelRefundCase } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies);
}

function dependencies(overrides = {}) {
  return Object.assign({
    listActivityCancelRefundCases: () => Promise.resolve({ rows: [], total: 0 }),
    getActivityCancelRefundCase: () => Promise.resolve({ data: {} }),
    assessActivityCancelRefundCase: () => Promise.resolve({ data: {} }),
    handleActivityCancelRefundCase: () => Promise.resolve({ data: {} }),
    uploadActivityCancelRefundEvidence: () => Promise.resolve({ objectKey: 'upload/merchant-aftercare-evidence/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png' }),
    reworkActivityCancelRefundCase: () => Promise.resolve({ data: {} }),
    reopenActivityCancelRefundAssessment: () => Promise.resolve({ data: {} }),
    verifyActivityCancelRefundCase: () => Promise.resolve({ data: {} })
  }, overrides);
}

test('活动取消人工退款放在 canonical 退款处理台，不写入俱乐部卡片', () => {
  const workbench = read('src/views/business/finance/refund/index.vue');
  const api = read('src/api/business/refund.js');
  const clubWorkspace = read('src/views/business/club/index.vue');
  const clubCard = read('../chengyinhub-xcx/components/cy/club-card/index.wxml');

  assert.match(workbench, /<activity-cancel-refund-cases\s+v-else-if="isActivityCancelQueue"/);
  assert.match(workbench, /query:\s*\{\s*queue:\s*['"]activity-cancel['"]\s*\}/);
  assert.match(workbench, /business:refund:review/);
  assert.match(api, /activity-cancel-cases\/list/);
  assert.match(api, /data\.append\('caseId', context\.caseId\)/);
  assert.match(api, /data\.append\('expectedVersion', context\.expectedVersion\)/);
  assert.match(api, /data\.append\('requestId', context\.requestId\)/);
  assert.match(api, /activity-cancel-cases\/assess/);
  assert.match(api, /activity-cancel-cases\/handle/);
  assert.match(api, /activity-cancel-cases\/verify/);
  assert.match(api, /activity-cancel-cases\/evidence\/upload/);
  assert.match(api, /activity-cancel-cases\/rework/);
  assert.match(api, /activity-cancel-cases\/reopen-assessment/);
  assert.match(clubWorkspace, /openActivityCancelRefundQueue/);
  assert.match(clubWorkspace, /queue:\s*['"]activity-cancel['"],\s*clubId:\s*this\.current\.id/);
  assert.doesNotMatch(clubCard, /退款|refund/i, '既有俱乐部卡片不得承载财务工单');
});

test('五态 UI 逐步开放判额、登记与第二人复核，且明确凭证不等于到账', () => {
  const source = read('src/views/business/finance/refund/ActivityCancelRefundCases.vue');
  for (const status of [
    'PENDING_ASSESSMENT', 'MANUAL_REFUND_REQUIRED', 'MANUAL_REFUND_RECORDED',
    'MANUAL_REFUND_EVIDENCE_VERIFIED', 'NO_REFUND'
  ]) assert.match(source, new RegExp(status));
  assert.match(source, /已复核凭证不等于支付渠道已确认到账/);
  assert.match(source, /处理人与复核人必须是不同的两个人/);
  assert.doesNotMatch(source, /\bdelete\s*\(/i);
});

test('判额把人民币字符串精确转成分，并保持 requestId 供网络重试复用', async () => {
  const requests = [];
  const component = loadCases(dependencies({
    assessActivityCancelRefundCase: payload => { requests.push(payload); return Promise.resolve({ data: {} }); }
  }));
  const vm = Object.assign(component.data(), {
    $modal: { msgError: message => { throw new Error(message); }, msgSuccess: () => {} },
    getList: () => Promise.resolve(),
    requestId: component.methods.requestId,
    yuanToFen: component.methods.yuanToFen
  });
  component.methods.openAssess.call(vm, { id: 9, version: 0 });
  const stableRequestId = vm.assessForm.requestId;
  vm.assessForm.amountYuan = '123.45';
  vm.assessForm.note = '未核销子票按冻结现金人工判额';

  await component.methods.submitAssess.call(vm);

  assert.equal(requests[0].assessedRefundAmountFen, 12345);
  assert.equal(requests[0].expectedVersion, 0);
  assert.equal(requests[0].requestId, stableRequestId);
  assert.equal(vm.assessOpen, false);
});

test('人工退款判额为 0 时负控会红且不调用 API', () => {
  let called = false;
  const errors = [];
  const component = loadCases(dependencies({
    assessActivityCancelRefundCase: () => { called = true; return Promise.resolve(); }
  }));
  const vm = Object.assign(component.data(), {
    $modal: { msgError: message => errors.push(message), msgSuccess: () => {} },
    getList: () => Promise.resolve(),
    requestId: component.methods.requestId,
    yuanToFen: component.methods.yuanToFen
  });
  component.methods.openAssess.call(vm, { id: 9, version: 0 });
  vm.assessForm.amountYuan = '0';
  vm.assessForm.note = '错误零元判额';

  component.methods.submitAssess.call(vm);

  assert.equal(called, false);
  assert.match(errors[0], /必须大于 0/);
});

test('分金额按十进制字符串展示，超过 JS 安全整数也不丢精度', () => {
  const component = loadCases(dependencies());
  assert.equal(component.methods.moneyFen('9007199254740993'), '90071992547409.93');
  assert.equal(component.methods.moneyFen(0), '0.00');
  assert.equal(component.methods.moneyFen('bad'), '—');
});

test('详情并发返回乱序时只保留最后打开的责任单', async () => {
  const pending = new Map();
  const component = loadCases(dependencies({
    getActivityCancelRefundCase: caseId => new Promise(resolve => pending.set(caseId, resolve))
  }));
  const vm = Object.assign(component.data(), {});

  const first = component.methods.openDetail.call(vm, { id: 1 });
  const second = component.methods.openDetail.call(vm, { id: 2 });
  pending.get(2)({ data: { id: 2 } });
  await second;
  pending.get(1)({ data: { id: 1 } });
  await first;

  assert.deepEqual(vm.detail, { id: 2 });
  assert.equal(vm.detailLoading, false);
});

test('登记先走受控上传，再只发送服务端 objectKey 与已审核判额', async () => {
  const uploads = [];
  const requests = [];
  const component = loadCases(dependencies({
    uploadActivityCancelRefundEvidence: (file, context) => {
      uploads.push({ file, context });
      return Promise.resolve({ objectKey: 'upload/merchant-aftercare-evidence/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png' });
    },
    handleActivityCancelRefundCase: payload => { requests.push({ ...payload }); return Promise.resolve({ data: {} }); }
  }));
  const vm = Object.assign(component.data(), {
    $modal: { msgError: message => { throw new Error(message); }, msgSuccess: () => {} },
    getList: () => Promise.resolve(),
    requestId: component.methods.requestId
  });
  component.methods.openHandle.call(vm, { id: 10, version: 1, assessedRefundAmountFen: 8800 });
  component.methods.onEvidenceChange.call(vm, { raw: { name: 'receipt.png' } }, [{ raw: { name: 'receipt.png' } }]);
  vm.handleForm.channel = 'offline_transfer';
  vm.handleForm.note = '线下转账回执由财务受控留存';

  await component.methods.submitHandle.call(vm);

  assert.equal(uploads.length, 1);
  assert.equal(uploads[0].file.name, 'receipt.png');
  assert.equal(uploads[0].context.caseId, 10);
  assert.equal(uploads[0].context.expectedVersion, 1);
  assert.match(uploads[0].context.requestId, /^activity-cancel-handle-10-/);
  assert.equal(uploads[0].context.requestId, requests[0].requestId);
  assert.equal(requests[0].actualRefundAmountFen, 8800);
  assert.equal(requests[0].expectedVersion, 1);
  assert.equal(requests[0].channel, 'offline_transfer');
  assert.equal(requests[0].evidenceObjectKey, 'upload/merchant-aftercare-evidence/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.png');
  assert.equal(Object.hasOwn(requests[0], 'evidenceSha256'), false);
});

test('第二人未打开受控凭证确认时负控会红，verify 不接收客户端 hash', async () => {
  let called = false;
  const errors = [];
  const component = loadCases(dependencies({
    getActivityCancelRefundCase: () => Promise.resolve({ data: { id: 11, evidencePreviewUrl: 'https://signed.example/evidence' } }),
    verifyActivityCancelRefundCase: payload => { called = payload; return Promise.resolve(); }
  }));
  const vm = Object.assign(component.data(), {
    $modal: { msgError: message => errors.push(message), msgSuccess: () => {} },
    getList: () => Promise.resolve(),
    requestId: component.methods.requestId
  });
  await component.methods.openVerify.call(vm, { id: 11, version: 2 });
  vm.verifyForm.note = '独立复核';

  component.methods.submitVerify.call(vm);

  assert.equal(called, false);
  assert.match(errors[0], /先打开并核对受控凭证/);

  vm.verifyForm.confirmed = true;
  await component.methods.submitVerify.call(vm);
  assert.equal(Object.hasOwn(called, 'evidenceSha256'), false);
  assert.equal(called.expectedVersion, 2);
});

test('凭证可退回重做，NO_REFUND 可由另一人重开评估', async () => {
  const actions = [];
  const component = loadCases(dependencies({
    reworkActivityCancelRefundCase: payload => { actions.push(['rework', payload]); return Promise.resolve({}); },
    reopenActivityCancelRefundAssessment: payload => { actions.push(['reopen', payload]); return Promise.resolve({}); }
  }));
  const vm = Object.assign(component.data(), {
    $modal: { msgError: message => { throw new Error(message); }, msgSuccess: () => {} },
    getList: () => Promise.resolve(),
    requestId: component.methods.requestId
  });

  component.methods.openCorrection.call(vm, { id: 12, version: 4 }, 'rework');
  vm.correctionForm.reason = '凭证金额不清晰';
  await component.methods.submitCorrection.call(vm);
  component.methods.openCorrection.call(vm, { id: 13, version: 3 }, 'reopen');
  vm.correctionForm.reason = '发现新的未核销退款依据';
  await component.methods.submitCorrection.call(vm);

  assert.equal(actions[0][0], 'rework');
  assert.equal(actions[0][1].expectedVersion, 4);
  assert.equal(actions[1][0], 'reopen');
  assert.equal(actions[1][1].expectedVersion, 3);
});

test('分页与俱乐部筛选从 route query 保真传给后端', () => {
  const requests = [];
  const component = loadCases(dependencies({
    listActivityCancelRefundCases: query => new Promise(resolve => requests.push({ query: { ...query }, resolve }))
  }));
  const vm = Object.assign(component.data(), {
    routeQuery: { queue: 'activity-cancel', pageNum: '3', pageSize: '25', clubId: '88', status: 'MANUAL_REFUND_RECORDED' }
  });
  vm.getList = component.methods.getList.bind(vm);

  component.watch.routeQuery.handler.call(vm, vm.routeQuery);

  assert.deepEqual(requests[0].query, {
    pageNum: 3,
    pageSize: 25,
    clubId: '88',
    status: 'MANUAL_REFUND_RECORDED'
  });
});
