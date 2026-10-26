const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

// 审核台前后端状态口径契约:后端 AuditTask.status 是 Integer(0待审/1通过/2拒绝/3升级/4取消),
// AuditReviewDTO 是 {taskId, status:Integer, reasonCode, reasonText}。
// 前端一旦拿字符串('PENDING'/'APPROVED')去比/去传,按钮恒灰、提交必反序列化失败、原因静默丢失。
// 本测试把 index.vue 的 <script> 取出真跑,断言的是行为而非源码长相。
function loadComponent(listAuditTasks, getAuditTaskDetail, reviewAuditTask, reviewMediaAsset) {
  const src = fs.readFileSync(
    path.resolve(__dirname, '../../src/views/business/audit/index.vue'),
    'utf8'
  );
  const script = src.match(/<script>([\s\S]*?)<\/script>/)[1];
  // 去掉 @/ 别名 import(测试不跑构建),把 export default 变成可求值的返回
  const body = script
    .replace(/^\s*import[^\n]*\n/gm, '')
    .replace(/components:\s*\{[^}]+\},/, 'components: {},')
    .replace(/export\s+default\s*/, 'return ');
  const component = new Function('listAuditTasks', 'getAuditTaskDetail', 'reviewAuditTask', 'reviewMediaAsset', 'sessionStorage', body)(
    listAuditTasks, getAuditTaskDetail, reviewAuditTask, reviewMediaAsset, (() => { const rows = new Map(); return { getItem: k => rows.get(k) || null, setItem: (k,v) => rows.set(k,v), removeItem: k => rows.delete(k) }; })());
  return component;
}

function deferred() {
  let resolve, reject;
  const promise = new Promise((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

test('审核提交按当前选中任务选择媒体或普通入口，不依赖旧详情，失败保留确认层', async () => {
  const sent = [];
  let rejectMedia = false;
  const component = loadComponent(undefined, () => Promise.resolve({ code: 200, itemProblemReceipt: { ...sent.at(-1)[1], state: 'FOUND', applied: true } }),
    data => { sent.push(['audit', data]); return Promise.resolve({ code: 200 }); },
    data => { sent.push(['media', data]); return rejectMedia ? Promise.reject(Error('拒绝')) : Promise.resolve(); });
  const vm = { ...component.data(), ...component.methods, $modal: { msgSuccess() {} }, getList() {} };
  vm.$store = { getters: { id: '7', token: 'review-session', permissions: ['business:audit:review'] } };
  for (const [name, getter] of Object.entries(component.computed)) Object.defineProperty(vm, name, { get: () => getter.call(vm) });
  for (const [bizType, expected] of [['MEDIA_ASSET', 'media'], ['TOPIC', 'audit']]) {
    vm.detailTask = { bizType: bizType === 'MEDIA_ASSET' ? 'TOPIC' : 'MEDIA_ASSET' };
    vm.openReview({ id: 42, version: 3, bizType }, 2);
    vm.reviewForm.reasonText = '实际审核原因';
    await vm.submitReview();
    if (bizType === 'TOPIC') {
      const requestId = sent.at(-1)[1].requestId;
      assert.ok(requestId);
      assert.deepEqual(sent.at(-1), [expected, { taskId: '42', expectedVersion: 3, status: 2, reasonText: '实际审核原因', problems: [], requestId }]);
      assert.equal(vm.reviewConfirmed, true);
      vm.finishConfirmedReview();
    } else assert.deepEqual(sent.at(-1), [expected, { taskId: 42, expectedVersion: 3, status: 2, reasonText: '实际审核原因' }]);
    assert.equal(vm.reviewOpen, false);
  }
  rejectMedia = true;
  vm.detailTask = null;
  vm.openReview({ id: 43, version: 4, bizType: 'MEDIA_ASSET' }, 1);
  await assert.rejects(vm.submitReview());
  assert.equal(vm.reviewOpen, true);
});

test('社区图片沿同任务详情刷新，原正文与审核版本保留，不从快照长链接取图', async () => {
  const snapshotJson = JSON.stringify({ body: '原始待审正文', media: [{ id: 12, derived_object_key: 'upload/owned.png' }] });
  const ids = [], sent = [];
  let url = 'https://media.example/short-one';
  const task = { id: 40, bizId: 5, bizType: 'COMMUNITY_POST', status: 0, version: 3, snapshotJson };
  const c = loadComponent(undefined, id => { ids.push(id); return Promise.resolve({
    task: { ...task }, records: [], reportCount: 0,
    communityMediaPreview: { taskId: 40, taskVersion: 3, postId: 5, postVersion: 7,
      status: 'READY', expiresInSeconds: 300, items: [{ id: 12, url }] }
  }); }, data => { sent.push(data); return Promise.resolve(); });
  const vm = { ...c.data(), ...c.methods, $modal: { msgSuccess() {} }, getList() {} };
  await vm.openDetail(task);
  let items = c.computed.auditEvidenceItems.call(vm);
  assert.deepEqual(items.filter(item => item.kind === 'image'), [{ kind: 'image', src: url }]);
  assert.match(items[0].content, /原始待审正文/);
  url = 'https://media.example/short-two';
  await vm.openDetail(vm.detailRow);
  items = c.computed.auditEvidenceItems.call(vm);
  assert.deepEqual(ids, [40, 40]);
  assert.deepEqual(items.filter(item => item.kind === 'image'), [{ kind: 'image', src: url }]);
  assert.equal(JSON.parse(vm.detailTask.snapshotJson).body, '原始待审正文');
  vm.openReview(vm.detailTask, 1);
  await vm.submitReview();
  assert.equal(sent[0].expectedVersion, 3);
  assert.equal(sent[0].taskId, 40);
  const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/audit/index.vue'), 'utf8');
  assert.match(source, /@click="openDetail\(detailRow\)">刷新五分钟预览/);
});

test('社区预览失效或身份版本不匹配仍保留冻结文字与轨迹，但无旧图降级', async () => {
  const task = { id: 40, bizId: 5, version: 3, bizType: 'COMMUNITY_POST', status: 0,
    snapshotJson: JSON.stringify({ body: '冻结正文', media: [{ derived_object_key: 'https://old.example/long' }] }) };
  const valid = { taskId: 40, taskVersion: 3, postId: 5, postVersion: 7, status: 'READY',
    expiresInSeconds: 300, items: [{ id: 12, url: 'https://media.example/short' }] };
  for (const preview of [undefined, null, { ...valid, taskId: 39 }, { ...valid, taskVersion: 2 },
    { ...valid, status: 'STALE', items: [] }, { ...valid, status: 'UNAVAILABLE', items: [] },
    { ...valid, items: [{ id: 12, url: '/relative' }] }]) {
    const c = loadComponent(undefined, () => Promise.resolve({ task, records: [{ id: 2 }], reportCount: 0,
      communityMediaPreview: preview }));
    const vm = { ...c.data(), ...c.methods };
    await vm.openDetail(task);
    assert.equal(vm.detailTask, task);
    assert.equal(vm.detailRecords[0].id, 2);
    assert.deepEqual(c.computed.auditEvidenceItems.call(vm).filter(item => item.kind === 'image'), []);
    assert.match(vm.communityMediaPreviewError, /预览|刷新/);
  }
});

test('社区预览晚到或抽屉已关闭时不能覆盖新任务图片，加载失败先清旧图', async () => {
  for (const closed of [false, true]) {
    const old = deferred(), latest = deferred();
    const c = loadComponent(undefined, id => id === 39 ? old.promise : latest.promise);
    const vm = { ...c.data(), ...c.methods };
    const first = vm.openDetail({ id: 39 });
    const second = vm.openDetail({ id: 40 });
    latest.resolve({ task: { id: 40, version: 3, bizType: 'COMMUNITY_POST', snapshotJson: '{}' },
      records: [], reportCount: 0, communityMediaPreview: { taskId: 40, taskVersion: 3,
        status: 'READY', expiresInSeconds: 300, items: [{ id: 14, url: 'https://media.example/new' }] } });
    await second;
    const current = vm.communityMediaPreview;
    if (closed) vm.detailOpen = false;
    old.resolve({ task: { id: 39, version: 3, bizType: 'COMMUNITY_POST', snapshotJson: '{}' },
      records: [], reportCount: 0, communityMediaPreview: { taskId: 39, taskVersion: 3,
        status: 'READY', expiresInSeconds: 300, items: [{ id: 13, url: 'https://media.example/old' }] } });
    await first;
    assert.equal(vm.communityMediaPreview, current);
    assert.equal(vm.detailTask.id, 40);
  }
  const c = loadComponent(undefined, () => Promise.reject(Error('signing failed')));
  const vm = { ...c.data(), ...c.methods, communityMediaPreview: { items: [{ id: 1, url: 'https://old.example/short' }] } };
  await vm.openDetail({ id: 40 });
  assert.equal(vm.detailTask, null);
  assert.equal(vm.communityMediaPreview, null);
  assert.match(vm.detailError, /加载失败/);
});

test('canReview 认后端的 Integer 状态:待审(0)/升级(3)可审,终态不可审', () => {
  const { canReview } = loadComponent().methods;

  assert.equal(canReview({ status: 0 }), true, '待审(STATUS_PENDING=0)必须可审,否则按钮恒灰');
  assert.equal(canReview({ status: 3 }), true, '升级审核(STATUS_ESCALATED=3)必须可审');

  assert.equal(canReview({ status: 1 }), false, '已通过不可再审');
  assert.equal(canReview({ status: 2 }), false, '已拒绝不可再审');
  assert.equal(canReview({ status: 4 }), false, '已取消不可再审');
});

test('坏响应不清空上次审核结果，失败后禁止对旧结果作决定，原页重试恢复', async () => {
  let response = '<html>bad gateway</html>';
  const component = loadComponent(() => Promise.resolve(response));
  const vm = { ...component.data(), ...component.methods };
  const row = { id: 40, status: 0 };
  vm.auditList = [row];
  vm.total = 1;
  vm.queryParams.bizId = 990082;

  const failed = vm.getList();
  assert.equal(vm.canReview(row), false, '刷新期间不得处理旧结果');
  await failed;
  assert.deepEqual(vm.auditList, [row]);
  assert.equal(vm.total, 1);
  assert.equal(vm.queryParams.bizId, 990082);
  assert.match(vm.loadError, /加载失败/);
  assert.equal(vm.loading, false);
  assert.equal(vm.canReview(row), false, '坏响应后不得把旧任务当作当前结果');

  response = { rows: [{ id: 41, status: 3 }], total: 1 };
  await vm.getList();
  assert.equal(vm.loadError, '');
  assert.equal(vm.auditList[0].id, 41);
  assert.equal(vm.canReview(vm.auditList[0]), true);
});

test('网络请求失败保留结果和筛选，并结束加载', async () => {
  const component = loadComponent(() => Promise.reject(new Error('HTTP 500')));
  const vm = { ...component.data(), ...component.methods };
  vm.auditList = [{ id: 39, status: 1 }];
  vm.total = 1;
  vm.queryParams.bizId = 990082;
  await vm.getList();
  assert.equal(vm.auditList[0].id, 39);
  assert.equal(vm.total, 1);
  assert.equal(vm.queryParams.bizId, 990082);
  assert.match(vm.loadError, /刷新重试/);
  assert.equal(vm.loading, false);
});

test('只有完整合法的空队列才清空结果；缺字段、错误列表及非法总数都按失败处理', async () => {
  for (const response of [null, {}, { rows: {}, total: 0 }, { rows: [], total: '0' },
    { rows: [], total: -1 }, { rows: [], total: NaN }, { rows: [], total: 0.5 }]) {
    const component = loadComponent(() => Promise.resolve(response));
    const vm = { ...component.data(), ...component.methods };
    vm.auditList = [{ id: 39, status: 1 }];
    vm.total = 1;
    await vm.getList();
    assert.equal(vm.auditList.length, 1);
    assert.equal(vm.total, 1);
    assert.match(vm.loadError, /加载失败/);
  }
  const component = loadComponent(() => Promise.resolve({ rows: [], total: 0 }));
  const vm = { ...component.data(), ...component.methods };
  vm.auditList = [{ id: 39, status: 1 }];
  vm.total = 1;
  await vm.getList();
  assert.deepEqual(vm.auditList, []);
  assert.equal(vm.total, 0);
  assert.equal(vm.loadError, '');
});

test('证据读取失败不回填列表快照，也不把历史轨迹误报为空；原抽屉重试恢复', async () => {
  const task = { id: 40, status: 1 };
  let response = '<html>bad gateway</html>';
  const ids = [];
  const component = loadComponent(undefined, id => { ids.push(id); return Promise.resolve(response); });
  const vm = { ...component.data(), ...component.methods };
  vm.detailTask = { id: 39, status: 0 };
  vm.detailRecords = [{ id: 1, action: 'SUBMIT' }];
  await vm.openDetail(task);
  assert.equal(vm.detailOpen, true);
  assert.equal(vm.detailLoading, false);
  assert.equal(vm.detailTask, null, '失败不能显示旧任务或启用它的审核操作');
  assert.match(vm.detailError, /加载失败/);
  assert.equal(vm.detailRow, task);

  response = { task, records: [{ id: 2, action: 'APPROVE' }], reportCount: 0 };
  await vm.openDetail(vm.detailRow);
  assert.deepEqual(ids, [40, 40]);
  assert.equal(vm.detailError, '');
  assert.equal(vm.detailTask, task);
  assert.equal(vm.detailRecords[0].action, 'APPROVE');
});

test('证据请求拒绝、缺字段、错任务或非法举报数均显示错误，合法空记录可正常显示', async () => {
  const task = { id: 40, status: 1 };
  for (const response of [null, {}, { task }, { task, records: {}, reportCount: 0 },
    { task: { id: 39 }, records: [], reportCount: 0 }, { task, records: [], reportCount: -1 }]) {
    const component = loadComponent(undefined, () => Promise.resolve(response));
    const vm = { ...component.data(), ...component.methods };
    await vm.openDetail(task);
    assert.equal(vm.detailTask, null);
    assert.match(vm.detailError, /加载失败/);
    assert.equal(vm.detailLoading, false);
  }
  const component = loadComponent(undefined, () => Promise.reject(new Error('HTTP 500')));
  const vm = { ...component.data(), ...component.methods };
  await vm.openDetail(task);
  assert.equal(vm.detailTask, null);
  assert.match(vm.detailError, /重新加载/);
  const valid = loadComponent(undefined, () => Promise.resolve({ task, records: [], reportCount: 0 }));
  const validVm = { ...valid.data(), ...valid.methods };
  await validVm.openDetail(task);
  assert.equal(validVm.detailTask, task);
  assert.deepEqual(validVm.detailRecords, []);
  assert.equal(validVm.detailError, '');
});

test('切换到任务40后，任务39晚返回或晚失败均不能覆盖当前证据', async () => {
  for (const rejectOld of [false, true]) {
    const old = deferred(), latest = deferred();
    const c = loadComponent(undefined, id => id === 39 ? old.promise : latest.promise);
    const vm = { ...c.data(), ...c.methods };
    const oldRead = vm.openDetail({ id: 39 });
    const newRead = vm.openDetail({ id: 40 });
    latest.resolve({ task: { id: 40, status: 1 }, records: [{ id: 2, action: 'APPROVE' }], reportCount: 0 });
    await newRead;
    if (rejectOld) old.reject(new Error('old HTTP 500'));
    else old.resolve({ task: { id: 39, status: 0 }, records: [], reportCount: 9 });
    await oldRead;
    assert.equal(vm.detailTask.id, 40);
    assert.equal(vm.detailRecords[0].id, 2);
    assert.equal(vm.reportCount, 0);
    assert.equal(vm.detailRow.id, 40);
    assert.equal(vm.detailError, '');
    assert.equal(vm.detailLoading, false);
  }
});

test('改变队列筛选后，旧结果与旧失败都不能覆盖新筛选结果', async () => {
  for (const rejectOld of [false, true]) {
    const old = deferred(), latest = deferred();
    let calls = 0;
    const c = loadComponent(() => calls++ === 0 ? old.promise : latest.promise);
    const vm = { ...c.data(), ...c.methods };
    vm.queryParams.bizId = 990081;
    const oldRead = vm.getList();
    vm.queryParams.bizId = 990082;
    const newRead = vm.getList();
    latest.resolve({ rows: [{ id: 40, bizId: 990082, status: 1 }], total: 1 });
    await newRead;
    if (rejectOld) old.reject(new Error('old HTTP 500'));
    else old.resolve({ rows: [{ id: 38, bizId: 990081, status: 0 }], total: 9 });
    await oldRead;
    assert.equal(vm.auditList[0].bizId, 990082);
    assert.equal(vm.queryParams.bizId, 990082);
    assert.equal(vm.total, 1);
    assert.equal(vm.loadError, '');
    assert.equal(vm.loading, false);
  }
});

test('旧请求结束时，当前队列或详情仍在读取就必须保持加载状态', async () => {
  for (const surface of ['list', 'detail']) {
    const old = deferred(), latest = deferred();
    let calls = 0;
    const request = () => calls++ === 0 ? old.promise : latest.promise;
    const c = loadComponent(request, request);
    const vm = { ...c.data(), ...c.methods };
    const read = id => surface === 'list' ? vm.getList() : vm.openDetail({ id });
    const oldRead = read(39), newRead = read(40);
    old.resolve(surface === 'list' ? { rows: [], total: 0 } : { task: { id: 39 }, records: [], reportCount: 0 });
    await oldRead;
    assert.equal(surface === 'list' ? vm.loading : vm.detailLoading, true);
    if (surface === 'detail') assert.equal(vm.detailTask, null);
    latest.resolve(surface === 'list' ? { rows: [], total: 0 } : { task: { id: 40 }, records: [], reportCount: 0 });
    await newRead;
    assert.equal(surface === 'list' ? vm.loading : vm.detailLoading, false);
  }
});

test('详情已关闭时，晚到响应不能把证据重新写回关闭的对象', async () => {
  const late = deferred();
  const c = loadComponent(undefined, () => late.promise);
  const vm = { ...c.data(), ...c.methods };
  const read = vm.openDetail({ id: 39 });
  vm.detailOpen = false;
  late.resolve({ task: { id: 39 }, records: [], reportCount: 0 });
  await read;
  assert.equal(vm.detailOpen, false);
  assert.equal(vm.detailTask, null);
  assert.equal(vm.detailLoading, false);
});

test('模板上的通过/拒绝按钮传的是 Integer,不是 APPROVED/REJECTED 字符串', () => {
  const src = fs.readFileSync(
    path.resolve(__dirname, '../../src/views/business/audit/index.vue'),
    'utf8'
  );
  assert.doesNotMatch(src, /openReview\(scope\.row,\s*'/,
    "openReview 不能传字符串字面量 —— AuditReviewDTO.status 是 Integer,Jackson 会反序列化失败");
  assert.match(src, /openReview\(scope\.row,\s*1\)/, '通过 = STATUS_APPROVED(1)');
  assert.match(src, /openReview\(scope\.row,\s*2\)/, '拒绝 = STATUS_REJECTED(2)');
});

test('openReview 往 DTO 里放 Integer status,不是字符串', () => {
  const component = loadComponent();
  const vm = { ...component.data(), ...component.methods };

  vm.openReview({ id: 7 }, 1);
  assert.equal(vm.reviewForm.taskId, 7);
  assert.equal(vm.reviewForm.status, 1, 'status 必须是 Integer,字符串会让 Jackson 反序列化失败');
  assert.equal(typeof vm.reviewForm.status, 'number');
});

test('审核原因走 reasonText —— DTO 上没有 reason 字段,写 reason 等于静默丢原因', () => {
  const component = loadComponent();
  const vm = { ...component.data(), ...component.methods };

  vm.openReview({ id: 7 }, 2);
  const keys = Object.keys(vm.reviewForm);
  assert.ok(keys.includes('reasonText'), 'reviewForm 必须有 reasonText(AuditReviewDTO 的字段)');
  assert.ok(!keys.includes('reason'), 'reviewForm 不该有 reason —— 后端 DTO 收不到,原因会静默丢失');
});

test('状态下拉与状态列用 Integer 值 + 中文标签,运营不该看见裸数字', () => {
  const src = fs.readFileSync(
    path.resolve(__dirname, '../../src/views/business/audit/index.vue'),
    'utf8'
  );
  // 下拉传给 Integer 字段的必须是数字:<el-option :value="0" /> 而非 value="PENDING"
  assert.doesNotMatch(src, /value="(PENDING|ESCALATED|APPROVED|REJECTED|CANCELLED)"/,
    '查询下拉不能把字符串塞给 Integer 的 status 字段');

  const { statusLabel } = loadComponent().methods;
  assert.equal(statusLabel(0), '待审核');
  assert.equal(statusLabel(3), '升级审核');
});

test('业务列表跳转可用 bizType + bizId 精确定位唯一审核对象', () => {
  const component = loadComponent();
  const data = component.data();
  assert.ok(Object.hasOwn(data.queryParams, 'bizId'), '审核台查询必须支持后端已有的 bizId 精确过滤');

  const topic = fs.readFileSync(
    path.resolve(__dirname, '../../src/views/business/topic/index.vue'),
    'utf8'
  );
  const activity = fs.readFileSync(
    path.resolve(__dirname, '../../src/views/business/activity/index.vue'),
    'utf8'
  );
  assert.match(topic, /bizType:\s*'TOPIC',\s*bizId:\s*row\.id/,
    '主题审核状态必须跳统一审核台并带对象 ID');
  assert.match(activity, /bizType:\s*'ACTIVITY',\s*bizId:\s*row\.id/,
    '活动审核状态必须跳统一审核台并带对象 ID');
});

test('组局证据可读且保留合并举报快照，缺证提供同组局调查入口', () => {
  const c = loadComponent();
  const vm = { ...c.data(), ...c.methods };
  vm.detailTask = { id: 40, bizType: 'HANGOUT', bizId: 88, snapshotJson: JSON.stringify({
    reason: '扰民', content: { available: true, title: '当时标题', memberId: 99, ownerRole: 'club', coverUrl: 'https://image.example/cover.jpg' }
  }) };
  vm.detailRecords = [{ id: 2, action: 'REPORT', snapshotJson: JSON.stringify({ reason: '补充陈述', content: { description: '第二次举报证据' } }) }];
  const items = c.computed.auditEvidenceItems.call(vm);
  assert.ok(items.some(x => x.kind === 'image' && x.src.endsWith('cover.jpg')));
  assert.ok(items.some(x => x.content && x.content.includes('当时标题') && x.content.includes('局主')));
  assert.ok(items.some(x => x.content && x.content.includes('第二次举报证据')));
  vm.detailTask.snapshotJson = '{"content":{"available":false}}';
  assert.ok(c.computed.auditEvidenceItems.call(vm).some(x => /缺失|缺证/.test(x.content)));
  let loaded = 0; vm.getList = () => loaded++;
  vm.investigateSameTarget();
  assert.equal(vm.queryParams.bizId, 88); assert.equal(vm.queryParams.bizType, 'HANGOUT');
  assert.equal(vm.queryParams.status, null); assert.equal(loaded, 1);
});
