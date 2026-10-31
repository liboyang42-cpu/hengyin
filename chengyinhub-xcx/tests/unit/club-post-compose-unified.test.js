'use strict';
// 2026-10-27 P-20 owner(真机走查 p109/p110):俱乐部发帖不再是页内 textarea,
// 统一调用广场那一个全屏发帖弹窗(cy-post-compose,club-id 非空即俱乐部模式,带草稿)。
// 本文件钉:俱乐部模式的请求体与原俱乐部页一致、草稿按团分槽、引用被拒只撤引用、可以只发图;
// 以及俱乐部页 / 广场页都只挂这一个组件、页内不再有 textarea 发帖框。

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const root = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(root, rel), 'utf8');
const clone = (value) => JSON.parse(JSON.stringify(value));

function mount(props) {
  let definition;
  const requests = [], events = [], tips = [], drafts = [], picks = [];
  const app = {
    getUserID: () => 'member-A', getAvatar: () => '', getNickname: () => '',
    getAuthorization: () => 'Bearer t',
    chooseImage(cb, left, options) { picks.push({ left, options }); cb(['a.jpg']); },
    sendRequest(options) { const task = { abort() {} }; requests.push(options); return task; },
  };
  const wx = { getWindowInfo: () => ({ windowWidth: 375 }) };
  vm.runInNewContext(read('pages/square/components/cy/post-compose/index.js'), {
    getApp: () => app, Component: (value) => { definition = value; }, wx, console,
    require(id) {
      if (id.includes('community-post-api')) return { createClient: () => ({ request() {}, captureUpload() {} }) };
      if (id.includes('pro-editor-draft')) return {
        loadDraft(w, identity) { drafts.push(['load', identity.topicId]); return { status: 'empty' }; },
        saveDraft(w, payload) { drafts.push(['save', payload.topicId]); },
        removeDraft(w, identity) { drafts.push(['remove', identity.topicId]); },
      };
      if (id.includes('publish-intent')) return { begin() { return { key: 'k' }; }, settle() {}, discard() {} };
      if (id.includes('/toast')) { const t = (text) => tips.push(text); t.undo = () => {}; return t; }
      if (id.includes('nav-safe-area')) return { resolveMenuChrome: () => ({ sheetTop: 60 }) };
      if (id.includes('datetime')) return { chinaParts: () => null };
      if (id.includes('location-manager')) return {};
      throw new Error('Unexpected module ' + id);
    },
  });
  const instance = Object.assign({
    data: Object.assign(clone(definition.data), { show: false, editPost: null, prefill: '', clubId: '', canAnnounce: false, postRef: null }, props),
    setData(patch) { Object.assign(this.data, patch); },
    triggerEvent(name, detail) { events.push([name, detail]); }, selectComponent: () => null,
  }, definition.methods);
  definition.lifetimes.attached.call(instance);
  instance.data.show = true;
  definition.observers.show.call(instance, true);
  return { instance, requests, events, tips, drafts, picks, definition };
}

test('俱乐部模式发到 /api/club/post/create,请求体与原俱乐部页一致', () => {
  const h = mount({ clubId: 36, canAnnounce: true, postRef: { refType: 1, refId: 9, name: '夜走' } });
  assert.deepEqual(h.drafts[0], ['load', 'club_post_36_member-A'], '草稿按团分槽,不与广场那份互相覆盖');
  h.instance.onInput({ detail: { value: '  周末一起  ' } });
  h.instance.onPickImage();
  assert.equal(h.picks[0].left, 9, '俱乐部帖最多 9 张');
  assert.equal(h.picks[0].options, undefined, '俱乐部帖沿用原俱乐部页的上传口径');
  h.instance.onToggleAnnouncement();
  h.instance.onPublish();
  assert.equal(h.requests.length, 1);
  const req = h.requests[0];
  assert.equal(req.url, '/api/club/post/create');
  assert.deepEqual(JSON.parse(req.data), { clubId: 36, content: '周末一起', images: 'a.jpg', type: 2, refType: 1, refId: 9 });
  req.success({ code: '200' });
  assert.deepEqual(h.drafts.at(-1), ['remove', 'club_post_36_member-A']);
  assert.deepEqual(h.events.map((e) => e[0]), ['published', 'close']);
  assert.equal(h.instance.data.content, '');
  assert.equal(h.instance.data.asAnnouncement, false);
});

test('俱乐部模式:没有公告权限时恒为普通动态;可以只发图', () => {
  const h = mount({ clubId: 36 });
  h.instance.onToggleAnnouncement();
  assert.equal(h.instance.data.asAnnouncement, false);
  assert.equal(h.instance.data.canSubmit, false);
  h.instance.onPickImage();
  assert.equal(h.instance.data.canSubmit, true, '「说点什么，或加张图」');
  h.instance.onPublish();
  assert.equal(JSON.parse(h.requests[0].data).type, 0);
});

test('俱乐部模式:引用被拒只撤引用,正文保留', () => {
  const h = mount({ clubId: 36, postRef: { refType: 2, refId: 3, name: '模板' } });
  h.instance.onInput({ detail: { value: '带上记录' } });
  h.instance.onPublish();
  h.requests[0].success({ code: '500', errorCode: 'CLUB_POST_REF_NOT_JOINED' });
  assert.deepEqual(h.events.map((e) => e[0]), ['refclear']);
  assert.equal(h.instance.data.content, '带上记录');
  assert.equal(h.instance.data.submitting, false);
});

test('广场模式不受影响:仍走社区帖并且必须有正文', () => {
  const h = mount({});
  assert.deepEqual(h.drafts[0], ['load', 'square_post_member-A']);
  h.instance.onPickImage();
  assert.equal(h.picks[0].left, 6);
  assert.equal(h.picks[0].options.bizType, 'COMMUNITY_POST');
  assert.equal(h.instance.data.canSubmit, false, '广场帖只有图不能发');
});

test('发帖弹窗是全屏:发帖与草稿两层都把 full 档抬到整屏,上限仍按胶囊实测', () => {
  const js = read('pages/square/components/cy/post-compose/index.js');
  assert.match(js, /--cy-comp-sheet-max-h: calc\(100vh - ' \+ Math\.round\(top\) \+ 'px\); --cy-comp-sheet-full: 100vh;/);
});

test('俱乐部页只挂同一个全屏发帖弹窗(广场列表页 2026-10-29 已删),页内不再有 textarea 发帖框', () => {
  const club = read('pages/club/detail/index.wxml');
  assert.match(club, /<cy-post-compose show="\{\{composerOpen\}\}" club-id="\{\{club\.id\}\}"/);
  assert.doesNotMatch(club, /class="post-composer"|class="post-input"|bindtap="onPostSubmit"/);
  const clubJson = JSON.parse(read('pages/club/detail/index.json'));
  assert.equal(clubJson.usingComponents['cy-post-compose'], '/pages/square/components/cy/post-compose/index');
  assert.equal(clubJson.componentPlaceholder['cy-post-compose'], 'view', '跨分包引用必须带占位');
});

// 2026-10-27 P-21 owner(真机走查 p112):帖文里关联的路线卡用主题封面,不再是一大块黑色路线底图。
test('帖文卡的关联路线卡优先用主题封面,路线预览图只做兜底', () => {
  const card = read('components/cy/post-card/index.wxml');
  assert.match(card, /<cy-feed-play-card[\s\S]{0,120}cover="\{\{post\.sportCover \|\| post\.playCover \|\| post\.routePreviewImg\}\}"/);
});

// 2026-10-27 P-22 owner(真机走查 p113):创建开场动画铺满整屏,顶栏透明不挡住最上面那一截圆圈。
test('创建开场页顶栏用透明 overlay 档,画布铺满整屏', () => {
  const wxml = read('pages/publish/template-intro/index.wxml');
  assert.match(wxml, /<cy-nav-bar overlay \/>/);
  assert.match(read('pages/publish/template-intro/index.wxss'), /\.ti-field\s*\{[^}]*position:\s*fixed;[^}]*top:\s*0;[^}]*height:\s*100vh/s);
});
