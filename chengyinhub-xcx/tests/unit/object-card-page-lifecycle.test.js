const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const { createRequire } = require('node:module');

const PAGE_PATH = path.join(__dirname, '../../subpackageP3/pages/object-cards/index/index.js');
const pageRequire = createRequire(PAGE_PATH);
const { createRequestClient } = require('../../utils/transport/request-client.js');

test('藏品册隐藏后迟到图片不重启动画，卸载后列表不更新页面', () => {
  let page, request, image, scheduled = 0, updates = 0;
  vm.runInNewContext(fs.readFileSync(path.join(__dirname, '../../subpackageP3/pages/object-cards/index/index.js'), 'utf8'), {
    getApp: () => ({ getUserID: () => 7, sendRequest: r => { request = r; } }),
    Page: p => { page = p; },
    require: id => id.endsWith('/object-card.js') ? { toCard: row => row } : id.endsWith('/session/login-reload.js') ? pageRequire(id) : {},
  });
  page.data = { cards: [], filter: '全部' };
  page.setData = patch => { updates++; Object.assign(page.data, patch); };
  page._images = {};
  page._bodies = [{}];
  page._canvas = { createImage: () => (image = {}), requestAnimationFrame: () => ++scheduled, cancelAnimationFrame() {} };
  page._loadImage('photo');
  page.onHide();
  image.onload();
  assert.equal(scheduled, 0);
  page.onShow();
  assert.equal(scheduled, 1);
  page.load('全部');
  page.onUnload();
  const before = updates;
  request.success({ code: '200', data: { list: [{ id: 1, title: '卡' }], total: 1 } });
  request.fail();
  image.onerror();
  assert.equal(updates, before);
  assert.equal(scheduled, 1);
});

function plain(value) {
  return JSON.parse(JSON.stringify(value));
}

function card(id, title, category) {
  return {
    id,
    title,
    category,
    sourceUrl: `https://unit.invalid/${id}.png`,
    frames: [`https://unit.invalid/${id}.png`],
    cardStyle: 'plain',
    genStatus: 'READY',
  };
}

function deferredPage() {
  let definition;
  let raf = 0;
  const requests = [];
  const toasts = [];
  const client = createRequestClient({
    wxRequest(options) {
      requests.push(options);
      return { abort() {} };
    },
    getBaseUrl() { return ''; },
    getAuthorization() { return ''; },
    hideToast() {},
    showToast() {},
  });
  const pile = {
    body(item) { return { item }; },
    step() {},
    settled() { return true; },
    hit() { return null; },
  };

  vm.runInNewContext(fs.readFileSync(PAGE_PATH, 'utf8'), {
    getApp: () => ({ getUserID: () => 7, sendRequest: options => client.send(options) }),
    Page: config => { definition = config; },
    require: id => {
      if (id.endsWith('/object-pile.js')) return pile;
      if (id.endsWith('/motion-preference.js')) return { readReducedMotion: () => true };
      if (id.endsWith('/toast.js')) return message => { toasts.push(message); };
      return pageRequire(id);
    },
  });

  const page = Object.assign({}, definition, {
    data: plain(definition.data),
    setData(patch, callback) {
      Object.assign(this.data, patch);
      if (callback) callback.call(this);
    },
  });
  page.onLoad();
  page._world = { W: 390, floor: 544, cx: 195, cy: 252 };
  page._canvas = {
    createImage: () => ({}),
    requestAnimationFrame: () => ++raf,
    cancelAnimationFrame() {},
  };
  return { page, requests, toasts };
}

function succeed(request, rows) {
  request.success({
    statusCode: 200,
    data: { code: 200, data: { list: rows, total: rows.length } },
  });
  request.complete({ statusCode: 200, data: { code: 200 } });
}

function fail(request) {
  request.fail({ errMsg: 'request:fail timeout' });
  request.complete({ errMsg: 'request:fail timeout' });
}

function visibleState(page) {
  return {
    cards: Array.from(page.data.cards || [], item => item.title),
    bodies: Array.from(page._bodies || [], body => body.item.title),
    filter: page.data.filter,
    active: page.data.active ? page.data.active.title : null,
    loading: page.data.loading,
    loaded: page.data.loaded,
    error: page.data.error,
  };
}

test('藏品册同页分类后发成功先到时，旧成功不得覆盖最新列表、堆与详情', () => {
  const h = deferredPage();
  succeed(h.requests[0], [card('base', '基线卡', '其他')]);

  h.page.load('电子产品');
  const oldRequest = h.requests[1];
  h.page.load('书籍文具');
  const currentRequest = h.requests[2];
  assert.deepEqual(
    [oldRequest.data.category, currentRequest.data.category],
    ['电子产品', '书籍文具'],
  );

  succeed(currentRequest, [card('book', '当前书卡', '书籍文具')]);
  h.page.setData({ active: h.page.data.cards[0] });
  const latest = visibleState(h.page);
  assert.deepEqual(latest, {
    cards: ['当前书卡'],
    bodies: ['当前书卡'],
    filter: '书籍文具',
    active: '当前书卡',
    loading: false,
    loaded: true,
    error: false,
  });

  succeed(oldRequest, [card('electronics', '迟到电子卡', '电子产品')]);
  assert.deepEqual(visibleState(h.page), latest);
});

test('藏品册同页新分类仍在加载时，旧失败不得退回筛选、结束 loading 或改详情', () => {
  const h = deferredPage();
  succeed(h.requests[0], [card('base', '基线卡', '其他')]);
  h.page.setData({ active: h.page.data.cards[0] });

  h.page.load('鞋包');
  const oldRequest = h.requests[1];
  h.page.load('食物饮料');
  const currentRequest = h.requests[2];
  fail(oldRequest);

  assert.deepEqual(visibleState(h.page), {
    cards: ['基线卡'],
    bodies: ['基线卡'],
    filter: '食物饮料',
    active: '基线卡',
    loading: true,
    loaded: true,
    error: false,
  });
  assert.deepEqual(h.toasts, []);

  succeed(currentRequest, [card('food', '当前食物卡', '食物饮料')]);
  h.page.setData({ active: h.page.data.cards[0] });
  assert.deepEqual(visibleState(h.page), {
    cards: ['当前食物卡'],
    bodies: ['当前食物卡'],
    filter: '食物饮料',
    active: '当前食物卡',
    loading: false,
    loaded: true,
    error: false,
  });
});
