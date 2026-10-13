const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/topic/workspace.vue'), 'utf8');

function loadWorkspace(dependencies) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ getTopicWorkspace, getTopicOperations \} from '@\/api\/business\/topic'$/m,
      'const { getTopicWorkspace, getTopicOperations } = dependencies;')
    .replace(/^import \{ auditNpcRouteEgg, listNpcRouteEgg, removeNpcRouteEgg, updateNpcRouteEgg \} from '@\/api\/business\/npcRouteEgg'$/m,
      'const { auditNpcRouteEgg, listNpcRouteEgg, removeNpcRouteEgg, updateNpcRouteEgg } = dependencies;')
    .replace(/^import \{ checkPermi \} from '@\/utils\/permission'$/m, 'const { checkPermi } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies);
}

test('主题 360 将 P0 统一待办作为默认总览，复用现有 G2 数据表和对象头', () => {
  assert.ok(source.includes("import { getTopicWorkspace, getTopicOperations } from '@/api/business/topic'"));
  assert.ok(source.includes("import CyDataTable from '@/components/CyDataTable'"));
  assert.ok(source.includes("import CyEntityHeader from '@/components/CyEntityHeader'"));
  assert.match(source, /<el-tab-pane label="总览" name="overview">/);
  assert.match(source, /<cy-entity-header\s+v-if="!operationsError && !operationsUnavailable"/);
  assert.match(source, /<cy-entity-header[\s\S]*?:facts="operationFacts"/);
  assert.match(source, /<cy-data-table[\s\S]*?:data="operationItems"[\s\S]*?:columns="operationColumns"/);
  assert.doesNotMatch(source, /row\.(email|longitude|latitude|message)/,
    '工作台总览不得把来源实体的 PII 或原始申请留言直接渲染出来');
  assert.doesNotMatch(source, /OPEN_LEGACY_(DISPATCH|OFFER)[\s\S]{0,240}\$router\.push/,
    '对象级授权未落地前，禁用动作不能偷渡成前端深链');
});

test('工作台并行读取业务全景与安全操作快照，快照失败不把主工作台降级为空白', async () => {
  const component = loadWorkspace({
    getTopicWorkspace: () => Promise.resolve({ data: { topic: { id: 71, name: '夜游梧桐区' }, chapters: [] } }),
    getTopicOperations: () => Promise.resolve({ data: {
      topic: { id: 71, contentOwnershipState: 'UNRESOLVED', readOnlyReasonCode: 'BLOCKED_CONTENT_OWNERSHIP_UNRESOLVED' },
      items: [{ itemKey: 'MERCHANT_REGISTRATION:11', displayState: '待调配', actionability: 'BLOCKED', allowedActions: [] }],
      summary: { actionableCount: 0, waitingExternalCount: 0, blockedCount: 1, readOnlyCount: 0 }
    } }),
    auditNpcRouteEgg: () => Promise.resolve(),
    listNpcRouteEgg: () => Promise.resolve({ data: [] }),
    removeNpcRouteEgg: () => Promise.resolve(),
    updateNpcRouteEgg: () => Promise.resolve(),
    checkPermi: () => false
  });
  const vm = Object.assign(component.data(), {
    $route: { query: { id: 71 } },
    parseTime: () => '2026-07-22 12:00',
    loadRoamEggs: () => Promise.resolve()
  });

  await component.methods.loadWorkspace.call(vm);

  assert.equal(vm.workspace.topic.name, '夜游梧桐区');
  assert.equal(vm.operations.items[0].itemKey, 'MERCHANT_REGISTRATION:11');
  assert.equal(vm.operationsError, '');
  assert.deepEqual(component.computed.operationItems.call(vm), vm.operations.items);
  assert.deepEqual(component.computed.operationSummary.call(vm), vm.operations.summary);
  assert.match(component.computed.operationsScopeMessage.call(vm), /历史待归属/);
});

test('P0 快照读取失败时保留既有主题工作台，且不伪造可编辑状态', async () => {
  const component = loadWorkspace({
    getTopicWorkspace: () => Promise.resolve({ data: { topic: { id: 72, name: '海岸步行线' }, chapters: [] } }),
    getTopicOperations: () => Promise.reject(new Error('operations unavailable')),
    auditNpcRouteEgg: () => Promise.resolve(),
    listNpcRouteEgg: () => Promise.resolve({ data: [] }),
    removeNpcRouteEgg: () => Promise.resolve(),
    updateNpcRouteEgg: () => Promise.resolve(),
    checkPermi: () => false
  });
  const vm = Object.assign(component.data(), {
    $route: { query: { id: 72 } },
    parseTime: () => '2026-07-22 12:00',
    loadRoamEggs: () => Promise.resolve()
  });

  await component.methods.loadWorkspace.call(vm);

  assert.equal(vm.workspace.topic.name, '海岸步行线');
  assert.match(vm.operationsError, /既有只读数据仍可继续查看/);
  assert.equal(component.computed.operationSummary.call(vm).actionableCount, 0);
});

test('对象级主题范围未配置时，工作台消费无数据的安全不可用快照而非全局错误', async () => {
  const component = loadWorkspace({
    getTopicWorkspace: () => Promise.resolve({ data: { topic: { id: 73, name: '山城夜行' }, chapters: [] } }),
    getTopicOperations: () => Promise.resolve({ data: {
      available: false,
      unavailableReasonCode: 'BLOCKED_TOPIC_SCOPE_UNAVAILABLE',
      topic: {}, items: [], summary: {}
    } }),
    auditNpcRouteEgg: () => Promise.resolve(),
    listNpcRouteEgg: () => Promise.resolve({ data: [] }),
    removeNpcRouteEgg: () => Promise.resolve(),
    updateNpcRouteEgg: () => Promise.resolve(),
    checkPermi: () => false
  });
  const vm = Object.assign(component.data(), {
    $route: { query: { id: 73 } },
    parseTime: () => '2026-07-22 12:00',
    loadRoamEggs: () => Promise.resolve()
  });

  await component.methods.loadWorkspace.call(vm);

  assert.equal(vm.operationsError, '');
  assert.equal(component.computed.operationsUnavailable.call(vm), true);
  assert.match(component.computed.operationsScopeMessage.call(vm), /对象级主题受托范围/);
});
