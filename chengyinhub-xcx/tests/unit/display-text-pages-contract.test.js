// 服务端文本过滤器(HTMLFilter,经 XssFilter 作用于 /api/* 的 POST/PUT/PATCH 请求体)会把 x<y 存成 x&lt;y。
// 这些页面把用户文字按纯文本展示,必须在数据映射处解码一次;编辑回填用解码值,重新提交的是原文,不会越存越长。
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const vm = require('../helpers/ui-sandbox-vm');

const ROOT = path.resolve(__dirname, '../..');
const RAW = 'a&lt;b 且 c>d';
const SHOWN = 'a<b 且 c>d';
const flush = () => new Promise(resolve => setImmediate(resolve));

function load(file, routes) {
  const calls = [];
  let definition;
  const app = {
    globalData: { statusBarHeight: 20, navBarHeight: 44 },
    canUseCommunity: () => true,
    getUserID: () => 990005, getAvatar: () => '', getNickname: () => '测试', getPageSize: () => 10,
    getImgUrl: url => url, getAuthorization: () => 'token', isDevEnv: () => false,
    getRequestErrorMessage: (r, fallback) => (r && r.msg) || fallback, getTotalPage: (n, s) => Math.ceil(n / s),
    sendRequest(o) {
      calls.push(o);
      const key = Object.keys(routes).find(prefix => String(o.url).startsWith(prefix));
      const task = { abort() {} };
      if (!key) return task;
      const route = routes[key];
      const reply = Array.isArray(route) ? route.shift() : route;
      if (reply !== undefined) queueMicrotask(() => { o.success(reply); if (o.complete) o.complete(); });
      return task;
    },
    tips() {},
  };
  const wx = {
    getStorageSync: () => '', setStorageSync() {}, removeStorageSync() {},
    getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 20 }),
    getWindowInfo: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 20 }),
    getMenuButtonBoundingClientRect: () => ({ bottom: 64, top: 24, height: 32, left: 280, right: 367, width: 87 }),
    hideTabBar() {}, showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {}, stopPullDownRefresh() {},
    setNavigationBarTitle() {}, setNavigationBarColor() {}, setBackgroundColor() {}, nextTick(fn) { fn(); },
    createSelectorQuery: () => ({ in() { return this; }, select() { return this; }, boundingClientRect() { return this; }, exec() {} }),
    showModal: o => queueMicrotask(() => o.success({ confirm: true })),
  };
  const absolute = path.join(ROOT, file);
  vm.runInNewContext(fs.readFileSync(absolute, 'utf8'), {
    console, getApp: () => app, wx, Page: d => { definition = d; }, Component: d => { definition = d; },
    require: createRequire(absolute), setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {},
  }, { filename: absolute });
  const page = Object.assign({}, definition, definition.methods || {});
  page.data = JSON.parse(JSON.stringify(definition.data || {}));
  page.triggerEvent = () => {};
  page.selectComponent = () => null;
  page.createSelectorQuery = wx.createSelectorQuery;
  page.setData = (patch, done) => {
    Object.entries(patch).forEach(([key, value]) => {
      const parts = key.replace(/\[(\d+)\]/g, '.$1').split('.');
      let obj = page.data;
      parts.slice(0, -1).forEach(p => { if (!obj[p]) obj[p] = {}; obj = obj[p]; });
      obj[parts.at(-1)] = value;
    });
    if (done) done();
  };
  return { page, calls };
}

function bundle(id, body) {
  return { post: { id, authorId: 990005, authorNickname: '测试', body, audience:'PUBLIC',postType:'MOMENT',commentPolicy:'EVERYONE',locationPrecision:'NONE',version: 1, lifecycle: 'PUBLISHED', viewerLiked: 0, viewerBookmarked: 0, likeCount: 0 }, media: [], references: [], viewerFollowing: false,viewerIsAuthor:true,topicCodes:[],mentions:[] };
}
const ok = data => ({ code: 200, data });

// 2026-10-29:广场列表页(pages/square/list)已删,列表侧用例随页撤走;详情与评论仍按解码值展示。
test('帖文详情 / 评论:帖文正文与评论按解码后的纯文本展示', async () => {
  const detail = load('pages/square/detail/index.js', {
    '/api/v1/community/posts/56/comments': ok({ items: [{ id: 9, postId: 56, body: 'x&lt;y &amp; z', authorId: 1 }], hasMore: false, nextCursor: null }),
    '/api/v1/community/posts/56': ok(bundle(56, RAW)),
  });
  detail.page.data.id = 56; detail.page.data.userId = 990005;
  detail.page.getData(); await flush();
  assert.equal(detail.page.data.info.contents, SHOWN);
  detail.page.getList(1, true); await flush();
  assert.equal(detail.page.data.list[0].contents, 'x<y & z');
});

test('广场帖文编辑:提交的是解码后的原文,不带回 &lt;(不会越存越长)', async () => {
  const replies = [
    { id: 3, title: '规范', content_json: '{"rules":[{"code":"A","title":"真实体验"}]}' }, {},
    bundle(56, RAW), {...bundle(56,SHOWN.replace('<','&lt;')),post:{...bundle(56,SHOWN.replace('<','&lt;')).post,version:2}}, {...bundle(56,SHOWN.replace('<','&lt;')),post:{...bundle(56,SHOWN.replace('<','&lt;')).post,version:2}},
  ];
  const calls = [];
  const app = {
    globalData: {}, getUserID: () => 990005,
    sendRequest(o) { calls.push(o); const r = replies.shift(); queueMicrotask(() => o.success({ code: 200, data: r })); return { abort() {} }; },
  };
  const storage=new Map();
  const wxApi = {getStorageSync:key=>storage.get(key),setStorageSync:(key,value)=>storage.set(key,JSON.parse(JSON.stringify(value))),removeStorageSync:key=>storage.delete(key), showModal: o => queueMicrotask(() => o.success({ confirm: true })) };
  const client = require('../../utils/community-post-api.js').createClient(app, wxApi);
  const prefill = client.normalizePost(bundle(56, RAW)).contents;
  assert.equal(prefill, SHOWN);
  let result;
  client.request({ url: '/api/creativesquare/action', data: { id: 56, contents: prefill }, success: r => { result = r; } });
  for (let i = 0; i < 6; i++) await flush();
  const patch = calls.find(c => c.method === 'PATCH');
  assert.ok(patch, '应发出 PATCH');
  assert.equal(patch.data.body, SHOWN);
  assert.equal(result.code, 200);
  assert.equal(result.data.contents, SHOWN);
});

test('俱乐部页:帖子、评论、修改历史、简介与审核原因解码;编辑帖子回填并提交原文', async () => {
  const h = load('pages/club/detail/index.js', {
    '/api/club/post/list': ok([{ id: 7, authorMemberId: 990005, content: RAW, version: 2 }]),
    '/api/club/post/comment/list': ok([{ id: 1, content: 'x&lt;y', createTime: '2026-10-15T10:00:00' }]),
    '/api/club/post/history': ok([{ id: 2, content: 'p&lt;q', snapshotVersion: 0 }]),
    '/api/club/detail': ok({ id: 5, name: 'A&lt;B 社', description: '人数&lt;10', myJoinReviewReason: '请写&lt;理由', memberId: 1 }),
    '/api/club/post/update': ok({}),
  });
  h.page.data.clubId = 5; h.page.data.myMemberId = 990005;
  h.page.loadPosts(); await flush();
  assert.equal(h.page.data.posts[0].contents, SHOWN);
  assert.equal(h.page.data.posts[0].content, SHOWN);
  // 真实流程由 onToggleComments 先置 openCommentPost 再读;BL-196 起回包时已切到别的动态会被丢弃
  h.page.setData({ openCommentPost: 7 });
  h.page.loadComments(7); await flush();
  assert.equal(h.page.data.comments[0].content, 'x<y');
  h.page.onViewPostHistory({ currentTarget: { dataset: { id: 7 } } }); await flush();
  assert.equal(h.page.data.postHistoryRows[0].content, 'p<q');

  h.page.onEditPost({ currentTarget: { dataset: { id: 7 } } });
  assert.equal(h.page.data.postEditText, SHOWN);
  h.page.submitPostEdit(); await flush();
  const update = h.calls.find(c => c.url === '/api/club/post/update');
  assert.equal(JSON.parse(update.data).content, SHOWN);
});

test('俱乐部资料:enrichClub 解码名称、简介、审核原因;编辑表单回填解码值', async () => {
  const h = load('components/cy/scene-club-edit/index.js', {
    '/api/club/detail': ok({
      // PublicClubVO.from/getters: explicit nullable DTO fields, no updateTime.
      id: 5, memberId: 1, name: 'A&lt;B 社', logo: null, cover: null,
      description: '人数&lt;10 且 &gt;5', clubTypes: null, activityPrefs: null,
      clubType: null, city: '上海', keywords: null, style: null, leaderName: null,
      level: null, tags: null, memberCount: 1, topicCount: 0,
      prioritySignupEnabled: null, memberDiscountPrice: null, memberReservedQuota: null,
      isJoined: true, isOwner: true, joinPolicy: null, joinPolicySupported: true,
      memberPostAllowed: 1, merchantUndertakeOpen: 1, myJoinStatus: null,
      myJoinReviewReason: '', viewerIsAdmin: false, viewerIdentityUnavailable: false,
      pendingJoinRequestCount: 0, nonOwnerMemberCount: 0,
    }),
  });
  h.page.validate = () => {}; h.page.syncDerived = () => {};
  h.page.data.clubId = 5;
  h.page.fetchDetail(); await flush();
  assert.equal(h.page.data.name, 'A<B 社');
  assert.equal(h.page.data.description, '人数<10 且 >5');

  const detail = load('pages/club/detail/index.js', {
    '/api/club/detail': ok({ id: 5, name: 'A&lt;B 社', description: '人数&lt;10', myJoinReviewReason: '请写&lt;理由', memberId: 1, isOwner: true }),
  });
  detail.page.data.clubId = 5;
  detail.page.loadMembers = () => {}; detail.page.loadClubAccess = () => {};
  detail.page.loadDetail(); await flush();
  assert.equal(detail.page.data.club.name, 'A<B 社');
  assert.equal(detail.page.data.club.description, '人数<10');
  assert.equal(detail.page.data.club.myJoinReviewReason, '请写<理由');
});

test('发现页全站帖文:俱乐部帖与社区帖正文都解码', async () => {
  // 2026-10-29:俱乐部 tab 改名发现,帖文流 = 俱乐部帖(scope=all) + 社区帖(feeds/LATEST)按时间合并
  const h = load('pages/talent/list/index.js', {
    '/api/club/post/feed': ok({ rows: [{ id: 1, content: RAW, createTime: '2026-10-15 10:00:00' }], hasMore: false }),
    '/api/v1/community/feeds/LATEST': ok({ items: [bundle(56, RAW)], hasMore: false, nextCursor: null }),
  });
  h.page.loadFeed(); await flush(); await flush(); await flush();
  assert.equal(h.page.data.feed.length, 2);
  h.page.data.feed.forEach(row => assert.equal(row.contents, SHOWN));
});

test('入会申请:申请理由解码', async () => {
  const h = load('pages/club/join-requests/index.js', {
    '/api/club/join-requests': ok([{ id: 1, memberId: 2, joinMessage: '我会&lt;准时&gt;到', joinTime: '2026-10-15T10:00:00' }]),
  });
  h.page.data.clubId = 5;
  h.page.loadRequests(); await flush();
  assert.equal(h.page.data.requests[0].joinMessageText, '“我会<准时>到”');
});

test('私信:气泡读 displayContent(解码),图片地址不动,重复 decorate 不二次解码;会话预览解码', () => {
  const chat = load('subpackageB/pages/im/chat/index.js', {});
  chat.page.fetchTopics = () => {};
  const once = chat.page.decorate([
    { id: 1, msgType: 1, content: '&amp;lt;b&amp;gt; 与 x&lt;y', createTime: '2026-10-15 10:00:00' },
    { id: 2, msgType: 2, content: 'https://e.com/a.png?x=1&amp;y=2', createTime: '2026-10-15 10:01:00' },
  ]);
  assert.equal(once[0].displayContent, '&lt;b&gt; 与 x<y');
  assert.equal(once[0].content, '&amp;lt;b&amp;gt; 与 x&lt;y');
  assert.equal(once[1].displayContent, 'https://e.com/a.png?x=1&amp;y=2');
  const twice = chat.page.decorate(once);
  assert.equal(twice[0].displayContent, '&lt;b&gt; 与 x<y');
  const wxml = fs.readFileSync(path.join(ROOT, 'subpackageB/pages/im/chat/index.wxml'), 'utf8');
  assert.equal((wxml.match(/<text wx:else>\{\{item\.displayContent\}\}<\/text>/g) || []).length, 2);
  assert.doesNotMatch(wxml, /<text wx:else>\{\{item\.content\}\}<\/text>/);

  const list = load('subpackageB/pages/im/list/index.js', {});
  assert.equal(list.page.fmtPreview({ lastMsgType: 1, lastMsgText: 'x&lt;y\n第二行' }), 'x<y 第二行');
  assert.equal(list.page.fmtPreview({ lastMsgType: 2, lastMsgText: 'x&lt;y' }), '[图片]');
});

test('玩法详情 / 我的玩法 / 玩法编辑回填:描述与规则解码', () => {
  const util = require('../../pages/templatedetail/utils/templateDetail.js');
  const raw = { id: 1, title: 'T&lt;1', description: '人数&lt;10', ruleInstructions: '第一步 a&lt;b\n第二步', reason: '请改&lt;x' };
  const normalized = util.normalizeTemplateDetail(raw);
  assert.equal(normalized.info.title, 'T<1');
  assert.equal(normalized.info.description, '人数<10');
  assert.equal(normalized.info.reason, '请改<x');
  assert.deepEqual(util.splitRuleSteps(normalized.info.ruleInstructions), ['第一步 a<b', '第二步']);
  assert.equal(raw.description, '人数&lt;10');

  const mine = load('subpackageMember/mytemplate/mytemplate.js', {});
  const item = mine.page.decorateItem({ id: 1, title: 'T&lt;1', description: 'd&lt;e', reason: 'r&lt;s', marketReason: 'm&lt;n' });
  assert.deepEqual([item.title, item.description, item.reason, item.marketReason], ['T<1', 'd<e', 'r<s', 'm<n']);

  const src = fs.readFileSync(path.join(ROOT, 'pages/publish/temp/index.js'), 'utf8');
  assert.match(src, /const formData = decodeDisplayFields\(\{/);
  assert.match(src, /\}, DECODED_FORM_TEXT\);/);
  const list = src.match(/const DECODED_FORM_TEXT = \[([^\]]+)\]/)[1];
  for (const key of ['title', 'description', 'ruleInstructions', 'storyText']) assert.ok(list.includes("'" + key + "'"), key);
});

test('主题详情:主题、章节、站点描述解码', () => {
  const h = load('pages/topic/index/index.js', {});
  const chapters = h.page.processChaptersList([{ name: '第&lt;1章', description: 'c&lt;d', nodes: [{ name: 'n', description: 'x&lt;y' }] }]);
  assert.equal(chapters[0].name, '第<1章');
  assert.equal(chapters[0].description, 'c<d');
  assert.equal(chapters[0].nodes[0].description, 'x<y');
  const src = fs.readFileSync(path.join(ROOT, 'pages/topic/index/index.js'), 'utf8');
  assert.match(src, /for \(const key of \['name', 'subtitle', 'description'\]\) res\.data\[key\] = decodeDisplayText\(res\.data\[key\]\)/);
});

test('商家评价:评价正文与商家回复解码(回复编辑框也从这里回填)', () => {
  const vmodel = require('../../pages/merchant/reviews/view-model.js');
  const page = vmodel.shapePage({ mode: 'public', total: 1, pageNum: 1, pageSize: 20, hasMore: false, items: [{
    id: 1, version: 0, rating: 5, imageUrls: [], status: 'VISIBLE', verifiedRedemption: true, canReply: false, canReport: false,
    content: '比预期&lt;好', merchantReply: '谢谢&amp;欢迎' }] }, 'public', 1, 20);
  assert.ok(page, '评价页数据形状有效');
  const row = page.items[0];
  assert.equal(row.content, '比预期<好');
  assert.equal(row.merchantReply, '谢谢&欢迎');
});
