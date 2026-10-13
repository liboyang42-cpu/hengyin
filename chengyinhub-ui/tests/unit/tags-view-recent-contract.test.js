const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const uiRoot = path.resolve(__dirname, '../..');

function read(relativePath) {
  return fs.readFileSync(path.join(uiRoot, relativePath), 'utf8');
}

function loadTagsView() {
  const utils = loadTagsViewUtils();
  const source = read('src/store/modules/tagsView.js')
    .replace("import { MAX_RECENT_VIEWS, hasEntityId, getViewKey } from '@/utils/tagsView'", 'const { MAX_RECENT_VIEWS, hasEntityId, getViewKey } = utils');
  const body = source.replace(/export\s+default\s*/, 'return ');
  return new Function('utils', body)(utils);
}

function loadTagsViewUtils() {
  const source = read('src/utils/tagsView.js');
  const body = source
    .replace(/export\s+const\s+/g, 'const ')
    .concat('\nreturn { MAX_RECENT_VIEWS, hasEntityId, getViewKey };');
  return new Function(body)();
}

function createView({ path: routePath, id, title, affix = false }) {
  const query = id === undefined ? {} : { id };
  const suffix = id === undefined ? '' : `?id=${id}`;
  return {
    path: routePath,
    fullPath: `${routePath}${suffix}`,
    query,
    name: `${title}Page`,
    meta: { title, affix }
  };
}

function freshState(module) {
  return JSON.parse(JSON.stringify(module.state));
}

test('TagsView 为同一路由的不同对象保留独立最近页签，并给出可辨认标题', () => {
  const module = loadTagsView();
  const state = freshState(module);

  module.mutations.ADD_VISITED_VIEW(state, createView({ path: '/index', title: '首页', affix: true }));
  module.mutations.ADD_VISITED_VIEW(state, createView({ path: '/order/detail', id: 1024, title: '订单详情' }));
  module.mutations.ADD_VISITED_VIEW(state, createView({ path: '/order/detail', id: 2048, title: '订单详情' }));

  assert.deepEqual(state.visitedViews.map(view => view.tagKey), [
    '/index',
    '/order/detail?id=1024',
    '/order/detail?id=2048'
  ]);
  assert.deepEqual(state.visitedViews.slice(1).map(view => view.context), ['#1024', '#2048']);
  assert.deepEqual(state.visitedViews.slice(1).map(view => view.title), ['订单详情', '订单详情']);
});

test('TagsView 重新访问同一对象只更新原页签并移动到最近位置', () => {
  const module = loadTagsView();
  const state = freshState(module);

  module.mutations.ADD_VISITED_VIEW(state, createView({ path: '/index', title: '首页', affix: true }));
  module.mutations.ADD_VISITED_VIEW(state, createView({ path: '/order/detail', id: 1024, title: '订单详情' }));
  module.mutations.ADD_VISITED_VIEW(state, createView({ path: '/topic/workspace', id: 51, title: '主题运营工作台' }));
  module.mutations.ADD_VISITED_VIEW(state, createView({ path: '/order/detail', id: 1024, title: '订单详情' }));

  assert.deepEqual(state.visitedViews.map(view => view.tagKey), [
    '/index',
    '/topic/workspace?id=51',
    '/order/detail?id=1024'
  ]);
});

test('TagsView 最多保留八个最近页签，固定页签永远不被淘汰', () => {
  const module = loadTagsView();
  const state = freshState(module);

  module.mutations.ADD_VISITED_VIEW(state, createView({ path: '/index', title: '首页', affix: true }));
  for (let id = 1; id <= 9; id += 1) {
    module.mutations.ADD_VISITED_VIEW(state, createView({ path: '/order/detail', id, title: '订单详情' }));
  }

  assert.equal(state.visitedViews.length, 9);
  assert.equal(state.visitedViews[0].tagKey, '/index');
  assert.deepEqual(state.visitedViews.slice(1).map(view => view.context), [
    '#2', '#3', '#4', '#5', '#6', '#7', '#8', '#9'
  ]);
});

test('对象详情不进入按组件名维护的 keep-alive，避免关闭一个页签误伤同类对象', () => {
  const module = loadTagsView();
  const state = freshState(module);

  module.mutations.ADD_CACHED_VIEW(state, createView({ path: '/order/detail', id: 1024, title: '订单详情' }));
  module.mutations.ADD_CACHED_VIEW(state, createView({ path: '/order', title: '订单管理' }));

  assert.deepEqual(state.cachedViews, ['订单管理Page']);
});

test('TagsView 以对象身份判断当前页签，并在视觉上显示对象上下文和固定标记', () => {
  const source = read('src/layout/components/TagsView/index.vue');

  assert.match(source, /:key="tag\.tagKey"/);
  assert.match(source, /import \{ getViewKey \} from '@\/utils\/tagsView'/);
  assert.match(source, /return route\.tagKey === getViewKey\(this\.\$route\)/);
  assert.match(source, /v-if="tag\.context"/);
  assert.match(source, /tag\.context/);
  assert.match(source, /v-if="isAffix\(tag\)"/);
});

test('AppMain 用同一对象身份重建详情实例，避免在 #ID 页签之间复用旧数据', () => {
  const source = read('src/layout/components/AppMain.vue');

  assert.match(source, /import \{ getViewKey \} from '@\/utils\/tagsView'/);
  assert.match(source, /return getViewKey\(this\.\$route\)/);
});
