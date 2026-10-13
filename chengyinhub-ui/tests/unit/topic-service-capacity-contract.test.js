const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(
  path.resolve(__dirname, '../../src/views/business/topic/detail.vue'),
  'utf8'
);
const apiSource = fs.readFileSync(
  path.resolve(__dirname, '../../src/api/business/topic.js'),
  'utf8'
);
const controllerSource = fs.readFileSync(
  path.resolve(__dirname, '../../../chengyinhub-admin/src/main/java/com/chengyinhub/web/controller/business/CmsTopicChapterController.java'),
  'utf8'
);

function loadComponent(dependencies) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/import \{[\s\S]*?\} from "@\/api\/business\/topic"/,
      'const { getChapterServiceCapacity, configureChapterServiceCapacity } = dependencies; const parseTime = dependencies.parseTime || (() => "")')
    .replace(/^import .*$/gm, '');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies);
}

function deferred() {
  let resolve;
  const promise = new Promise(resolvePromise => {
    resolve = resolvePromise;
  });
  return { promise, resolve };
}

function extractApiContract(functionName) {
  const body = apiSource.match(new RegExp(`export function ${functionName}\\([^)]*\\) \\{([\\s\\S]*?)\\n\\}`));
  assert.ok(body, `真实 topic.js 缺少 ${functionName}`);
  const url = body[1].match(/url:\s*(['"])([^'"]*)\1\s*\+\s*chapterId\s*\+\s*(['"])([^'"]*)\3/);
  const method = body[1].match(/method:\s*['"]([^'"]+)['"]/);
  assert.ok(url, `${functionName} 缺少按 chapterId 拼接的真实 URL`);
  assert.ok(method, `${functionName} 缺少真实 HTTP verb`);
  return { path: `${url[2]}{id}${url[4]}`, verb: method[1].toUpperCase() };
}

function extractControllerContract(methodName) {
  const base = controllerSource.match(/@RequestMapping\("([^"]+)"\)/);
  const mapping = controllerSource.match(new RegExp(`@(Get|Put)Mapping\\("([^"]+)"\\)\\s+public AjaxResult ${methodName}\\(`));
  assert.ok(base, '真实 Controller 缺少类级 RequestMapping');
  assert.ok(mapping, `真实 Controller 缺少 ${methodName} mapping`);
  return { path: `${base[1]}${mapping[2]}`, verb: mapping[1].toUpperCase() };
}

test('真实 topic.js URL/verb 与 Controller mapping 保持一致', () => {
  assert.deepEqual(
    extractApiContract('getChapterServiceCapacity'),
    extractControllerContract('getServiceCapacity')
  );
  assert.deepEqual(
    extractApiContract('configureChapterServiceCapacity'),
    extractControllerContract('configureServiceCapacity')
  );
});

test('批次容量弹窗按章节 GET 并把三数完整读回', async () => {
  const calls = [];
  const component = loadComponent({
    getChapterServiceCapacity: id => {
      calls.push(id);
      return Promise.resolve({ code: 200, data: {
        batchSize: 4,
        batchIntervalMinutes: 15,
        effortMaxConcurrent: 2
      } });
    },
    configureChapterServiceCapacity: () => Promise.resolve({ code: 200 })
  });
  const vm = Object.assign(component.data(), component.methods, {
    $set: (target, key, value) => { target[key] = value; }
  });

  await component.methods.openServiceCapacityDialog.call(vm, { id: 18, name: '咖啡店' });

  assert.deepEqual(calls, [18]);
  assert.equal(vm.serviceCapacityDialog.chapterId, 18);
  assert.equal(vm.serviceCapacityDialog.batchSize, 4);
  assert.equal(vm.serviceCapacityDialog.batchIntervalMinutes, 15);
  assert.equal(vm.serviceCapacityDialog.effortMaxConcurrent, 2);
});

test('章节 A 的迟到 GET 只更新 A 的 map，不污染当前章节 B 弹窗', async () => {
  const reads = new Map([[18, deferred()], [19, deferred()]]);
  const component = loadComponent({
    getChapterServiceCapacity: id => reads.get(id).promise,
    configureChapterServiceCapacity: () => Promise.resolve({ code: 200 })
  });
  const vm = Object.assign(component.data(), component.methods, {
    $set: (target, key, value) => { target[key] = value; }
  });

  const openA = component.methods.openServiceCapacityDialog.call(vm, { id: 18, name: 'A店' });
  const openB = component.methods.openServiceCapacityDialog.call(vm, { id: 19, name: 'B店' });
  reads.get(19).resolve({ code: 200, data: {
    batchSize: 6, batchIntervalMinutes: 20, effortMaxConcurrent: 3
  } });
  await openB;
  reads.get(18).resolve({ code: 200, data: {
    batchSize: 4, batchIntervalMinutes: 15, effortMaxConcurrent: 2
  } });
  await openA;

  assert.deepEqual(vm.serviceCapacityByChapter[18], {
    batchSize: 4, batchIntervalMinutes: 15, effortMaxConcurrent: 2
  });
  assert.equal(vm.serviceCapacityDialog.chapterId, 19);
  assert.equal(vm.serviceCapacityDialog.batchSize, 6);
  assert.equal(vm.serviceCapacityDialog.batchIntervalMinutes, 20);
  assert.equal(vm.serviceCapacityDialog.effortMaxConcurrent, 3);
});

test('章节 A 的迟到 finally 不能提前解除当前章节 B 的 loading', async () => {
  const reads = new Map([[18, deferred()], [19, deferred()]]);
  const component = loadComponent({
    getChapterServiceCapacity: id => reads.get(id).promise,
    configureChapterServiceCapacity: () => Promise.resolve({ code: 200 })
  });
  const vm = Object.assign(component.data(), component.methods, {
    $set: (target, key, value) => { target[key] = value; }
  });

  const openA = component.methods.openServiceCapacityDialog.call(vm, { id: 18, name: 'A店' });
  const openB = component.methods.openServiceCapacityDialog.call(vm, { id: 19, name: 'B店' });
  reads.get(18).resolve({ code: 200, data: {} });
  await openA;
  assert.equal(vm.serviceCapacityDialog.chapterId, 19);
  assert.equal(vm.serviceCapacityDialog.loading, true);

  reads.get(19).resolve({ code: 200, data: {} });
  await openB;
  assert.equal(vm.serviceCapacityDialog.loading, false);
});

test('三数任一 null、0、负数时前端精确拒绝且不发 PUT', async () => {
  let puts = 0;
  const warnings = [];
  const component = loadComponent({
    getChapterServiceCapacity: () => Promise.resolve({ code: 200, data: {} }),
    configureChapterServiceCapacity: () => { puts += 1; return Promise.resolve({ code: 200 }); }
  });
  const invalid = [
    [null, 15, 2], [0, 15, 2], [-1, 15, 2],
    [4, null, 2], [4, 0, 2], [4, -1, 2],
    [4, 15, null], [4, 15, 0], [4, 15, -1]
  ];

  for (const [batchSize, batchIntervalMinutes, effortMaxConcurrent] of invalid) {
    const vm = Object.assign(component.data(), component.methods, {
      serviceCapacityDialog: {
        chapterId: 18,
        batchSize,
        batchIntervalMinutes,
        effortMaxConcurrent,
        loading: false
      },
      $modal: { msgWarning: message => warnings.push(message) }
    });
    await component.methods.submitServiceCapacity.call(vm);
  }

  assert.equal(puts, 0);
  assert.equal(warnings.length, 9);
  warnings.forEach(message => assert.match(message, /三项都必须填写正整数/));
});

test('保存后再次 GET 回读服务端结果，不用刚提交的值伪造成功', async () => {
  const putPayloads = [];
  let reads = 0;
  const component = loadComponent({
    configureChapterServiceCapacity: (id, payload) => {
      putPayloads.push([id, payload]);
      return Promise.resolve({ code: 200 });
    },
    getChapterServiceCapacity: () => {
      reads += 1;
      return Promise.resolve({ code: 200, data: {
        batchSize: 6,
        batchIntervalMinutes: 20,
        effortMaxConcurrent: 3
      } });
    }
  });
  const successes = [];
  const vm = Object.assign(component.data(), component.methods, {
    serviceCapacityDialog: {
      visible: true,
      loading: false,
      chapterId: 18,
      chapterName: '咖啡店',
      batchSize: 4,
      batchIntervalMinutes: 15,
      effortMaxConcurrent: 2
    },
    serviceCapacityByChapter: {},
    $set: (target, key, value) => { target[key] = value; },
    $modal: { msgWarning: () => {}, msgSuccess: message => successes.push(message) }
  });

  await component.methods.submitServiceCapacity.call(vm);

  assert.deepEqual(putPayloads, [[18, {
    batchSize: 4,
    batchIntervalMinutes: 15,
    effortMaxConcurrent: 2
  }]]);
  assert.equal(reads, 1, 'PUT 成功后必须再 GET 回读');
  assert.deepEqual(vm.serviceCapacityByChapter[18], {
    batchSize: 6,
    batchIntervalMinutes: 20,
    effortMaxConcurrent: 3
  });
  assert.equal(vm.serviceCapacityDialog.visible, false, '当前章节保存并回读成功后必须关闭弹窗');
  assert.equal(successes.length, 1);
});

test('章节 A 保存迟到时仍以 A 发 PUT/GET，只更新 A map 且不操作当前 B 弹窗', async () => {
  const put = deferred();
  const putCalls = [];
  const readCalls = [];
  const component = loadComponent({
    configureChapterServiceCapacity: (id, payload) => {
      putCalls.push([id, payload]);
      return put.promise;
    },
    getChapterServiceCapacity: id => {
      readCalls.push(id);
      return Promise.resolve({ code: 200, data: {
        batchSize: 4, batchIntervalMinutes: 15, effortMaxConcurrent: 2
      } });
    }
  });
  const vm = Object.assign(component.data(), component.methods, {
    serviceCapacityDialog: {
      visible: true, loading: false, chapterId: 18, chapterName: 'A店',
      batchSize: 4, batchIntervalMinutes: 15, effortMaxConcurrent: 2
    },
    serviceCapacityByChapter: {},
    $set: (target, key, value) => { target[key] = value; },
    $modal: { msgWarning: () => {}, msgSuccess: () => {} }
  });

  const saveA = component.methods.submitServiceCapacity.call(vm);
  vm.serviceCapacityDialog = {
    visible: true, loading: true, chapterId: 19, chapterName: 'B店',
    batchSize: 6, batchIntervalMinutes: 20, effortMaxConcurrent: 3
  };
  put.resolve({ code: 200 });
  await saveA;

  assert.equal(putCalls[0][0], 18);
  assert.deepEqual(readCalls, [18]);
  assert.deepEqual(vm.serviceCapacityByChapter[18], {
    batchSize: 4, batchIntervalMinutes: 15, effortMaxConcurrent: 2
  });
  assert.equal(vm.serviceCapacityDialog.chapterId, 19);
  assert.equal(vm.serviceCapacityDialog.visible, true);
  assert.equal(vm.serviceCapacityDialog.loading, true);
  assert.equal(vm.serviceCapacityDialog.batchSize, 6);
});
