const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const source = fs.readFileSync(path.resolve(__dirname, '../../src/views/business/contentsearch/index.vue'), 'utf8');

function loadContentSearch(dependencies) {
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import \{ getComment, listComment \} from '@\/api\/business\/comment'$/m, 'const { getComment, listComment } = dependencies;')
    .replace(/^import \{ getSquare, listSquare \} from '@\/api\/business\/square'$/m, 'const { getSquare, listSquare } = dependencies;')
    .replace(/^import (Cy\w+) from .+$/gm, 'const $1 = {};');
  return new Function('dependencies', script.replace(/export\s+default\s*/, 'return '))(dependencies);
}

function createVm(component) {
  const vm = Object.assign(component.data.call({ defaultType: 'SQUARE' }), {
    $router: { push: () => {}, replace: () => Promise.resolve() },
    $route: { path: '/business/square', query: {} }
  });
  for (const name of ['listRequest', 'detailRequest', 'queryForCurrentType', 'getList', 'openEvidence']) {
    vm[name] = component.methods[name].bind(vm);
  }
  return vm;
}

test('内容检索切换内容类型后不接受较早的列表响应', async () => {
  const pending = [];
  const component = loadContentSearch({
    listSquare: params => new Promise(resolve => pending.push({ type: 'SQUARE', params, resolve })),
    listComment: params => new Promise(resolve => pending.push({ type: 'COMMENT', params, resolve })),
    getSquare: () => Promise.resolve({ data: {} }),
    getComment: () => Promise.resolve({ data: {} })
  });
  const vm = createVm(component);

  const squareRequest = vm.getList();
  vm.contentType = 'COMMENT';
  const commentRequest = vm.getList();

  assert.equal(pending[0].type, 'SQUARE');
  assert.equal(pending[1].type, 'COMMENT');
  pending[0].resolve({ rows: [{ id: 'square-1' }], total: 1 });
  await squareRequest;
  assert.deepEqual(vm.contentList, []);
  assert.equal(vm.loading, true);

  pending[1].resolve({ rows: [{ id: 'comment-1' }], total: 1 });
  await commentRequest;
  assert.deepEqual(vm.contentList, [{ id: 'comment-1' }]);
  assert.equal(vm.loading, false);
});

test('内容检索详情只接收最后一次打开的内容', async () => {
  const pending = [];
  const component = loadContentSearch({
    listSquare: () => Promise.resolve({ rows: [], total: 0 }),
    listComment: () => Promise.resolve({ rows: [], total: 0 }),
    getSquare: id => new Promise(resolve => pending.push({ type: 'SQUARE', id, resolve })),
    getComment: id => new Promise(resolve => pending.push({ type: 'COMMENT', id, resolve }))
  });
  const vm = createVm(component);

  const squareRequest = vm.openEvidence({ id: 'square-1' });
  vm.contentType = 'COMMENT';
  const commentRequest = vm.openEvidence({ id: 'comment-1' });

  pending[0].resolve({ data: { id: 'square-1', contents: '旧帖文' } });
  await squareRequest;
  assert.equal(vm.detail.id, 'comment-1');
  assert.equal(vm.detailLoading, true);

  pending[1].resolve({ data: { id: 'comment-1', contents: '最新评论' } });
  await commentRequest;
  assert.equal(vm.detail.id, 'comment-1');
  assert.equal(vm.detail.contents, '最新评论');
  assert.equal(vm.detailSource, 'COMMENT');
  assert.equal(vm.detailLoading, false);
});
