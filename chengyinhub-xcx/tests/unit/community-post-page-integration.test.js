const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { createRequire } = require('node:module');
const vm = require('../helpers/ui-sandbox-vm');
const ROOT = path.resolve(__dirname, '../..');
function bundle(id = 56, extra = {}) { return { post: Object.assign({ id, authorId: 990005, authorNickname: '测试', body: '内容', audience: 'PUBLIC', postType: 'MOMENT', commentPolicy: 'EVERYONE', locationPrecision: 'NONE', version: 1, lifecycle: 'PUBLISHED', viewerLiked: 0, viewerBookmarked: 0, likeCount: 0 }, extra), media: [], references: [], viewerFollowing: false, viewerIsAuthor: true, topicCodes: [], mentions: [] }; }
function load(file, replies) {
  const calls = [], storage = new Map(); let definition, state;
  const app = {
    canUseCommunity: () => true, // Current-account server-open fixture.
    globalData: {}, getUserID: () => 990005,
    ...(file === 'subpackageMember/components/cy/profile/index.js' ? { getAuthorization: () => 'Bearer existing-member-fixture' } : {}), getAvatar: () => '', getNickname: () => '测试', getPageSize: () => 1,
    getRequestErrorMessage: (r, fallback) => r && r.msg || fallback, getTotalPage: (n,s) => Math.ceil(n/s),
    sendRequest(o) { calls.push(o); let reply = o.method==='GET' && /\/posts\/[0-9]+$/.test(o.url) && state ? JSON.parse(JSON.stringify(state)) : replies.shift(); if(typeof reply==='function')reply=reply(o,state); if(reply && reply.post && (o.method==='PATCH'||o.method==='POST' && /\/posts(?:\/[0-9]+\/publish)?$/.test(o.url)))state=reply; if (reply === undefined) throw new Error('Unexpected HTTP '+o.url);
      const task = { aborted: false, abort() { this.aborted = true; } };
      queueMicrotask(() => { if (!task.aborted) { o.success(reply.code ? reply : { code: 200, data: reply }); if (o.complete) o.complete(); } }); return task; }, tips() {} };
  const wx = { getStorageSync: key => storage.get(key), setStorageSync(key,value) { storage.set(key,JSON.parse(JSON.stringify(value))); }, removeStorageSync(key) {storage.delete(key);}, getSystemInfoSync: () => ({ windowWidth: 375, windowHeight: 812, statusBarHeight: 20 }), getMenuButtonBoundingClientRect: () => ({ bottom: 64 }),
    hideTabBar() {}, showToast() {}, hideToast() {}, showLoading() {}, hideLoading() {}, stopPullDownRefresh() {},
    showModal: o => queueMicrotask(() => o.success({ confirm: true })), showActionSheet: o => queueMicrotask(() => o.success({ tapIndex: 0 })) };
  const absolute = path.join(ROOT,file);
  vm.runInNewContext(fs.readFileSync(absolute,'utf8'), { console, getApp: () => app, wx,
    Page: d => definition = d, Component: d => definition = d, require: createRequire(absolute),
    setTimeout() {}, clearTimeout() {}, setInterval() {}, clearInterval() {} }, { filename: absolute });
  const page = Object.assign({}, definition, definition.methods || {});
  page.data = JSON.parse(JSON.stringify(definition.data)); page.triggerEvent = () => {};
  page.setData = (patch, done) => { Object.entries(patch).forEach(([key,value]) => { const parts=key.replace(/\[(\d+)\]/g,'.$1').split('.');let obj=page.data;parts.slice(0,-1).forEach(p => { if(!obj[p])obj[p]={};obj=obj[p];});obj[parts.at(-1)]=value; }); if(done)done(); };
  return { page,calls, app, wx };
}
const flush = () => new Promise(resolve => setImmediate(resolve));
const created = o => ({...bundle(70,{...o.data,lifecycle:'DRAFT',version:0}),media:o.data.mediaIds.map(id=>({id})),references:o.data.references});
const checked = (_o,state) => ({...state,post:{...state.post,lifecycle:'CHECKING',version:state.post.version+1}});
// 2026-10-29「动态」列表页已删;社区帖文的公开流改由发现页(pages/talent/list)与俱乐部帖文合并读取。
test('real discover caller reads canonical LATEST bundles alongside the whole-site club feed', async () => {
  const h=load('pages/talent/list/index.js',[{rows:[],hasMore:false},{items:[bundle()],hasMore:false,nextCursor:56}]);
  h.page.loadFeed();await flush();await flush();
  assert.equal(h.calls[0].url,'/api/club/post/feed');assert.equal(JSON.parse(h.calls[0].data).scope,'all');
  assert.equal(h.calls[1].url,'/api/v1/community/feeds/LATEST');
  assert.equal(h.page.data.feed[0].contents,'内容');assert.equal(h.page.data.feed[0].source,'community');assert.equal(h.page.data.feedState,'ready');
});
test('real detail caller reads same shared ID and server like result', async () => {
  const h=load('pages/square/detail/index.js',[bundle(),bundle(56,{viewerLiked:1,likeCount:8})]);h.page.data.id=56;h.page.data.userId=990005;
  h.page.getData();await flush();assert.equal(h.page.data.info.version,1);assert.equal(h.calls[0].url,'/api/v1/community/posts/56');
  h.page.lickClick({currentTarget:{dataset:{type:1}}});await flush();assert.equal(h.page.data.info.likeCount,8);assert.equal(h.page.data.info.isLiked,1);
});
test('real favorites caller uses server bookmark cursor and cancellation removes item after success', async () => {
  const h=load('pages/mylike/mylike.js',[{items:[bundle(56,{viewerBookmarked:1})],hasMore:true,nextCursor:56},{items:[bundle(55)],hasMore:false,nextCursor:55},bundle()]);
  h.page.getPostFavoriteList(false);await flush();assert.equal(h.calls[0].url,'/api/v1/community/me/bookmarks');assert.equal(h.page.data.hasMorePosts,true);
  h.page.data.pageConfig.pageNum=2;h.page.getPostFavoriteList(false);await flush();assert.equal(h.calls[1].data.cursor,56);assert.equal(h.page.data.hasMorePosts,false);
  h.page.postUnlike({currentTarget:{dataset:{}},detail:{index:0}});await flush();assert.deepEqual(h.page.data.postList.map(r=>r.id),[55]);
});
test('real profile own caller preserves pending state and uses own scope without actor parameter', async () => {
  const h=load('subpackageMember/components/cy/profile/index.js',[{items:[bundle(56,{lifecycle:'CHECKING'})],hasMore:false,nextCursor:56}]);
  h.page.data.isSelf=true;h.page.data.page_no=1;h.page.getCreativesquareList();await flush();
  assert.equal(h.calls[0].url,'/api/v1/community/me/posts');assert.equal(h.calls[0].data.authorId,undefined);assert.equal(h.page.data.list[0].status,0);assert.equal(h.page.data.list[0].lifecycle,'CHECKING');
});
// 2026-10-27 P-20 owner:广场行内发布框撤掉,新建帖只走 cy-post-compose(下一条 real compose caller 覆盖规范确认 / 草稿 / 发布)。
test('real compose caller submits canonical draft/publish and preserves pending lifecycle', async () => {
  const h=load('pages/square/components/cy/post-compose/index.js',[{id:3,title:'规范',content_json:'{"rules":[{"code":"AUTHENTICITY","title":"真实体验：禁止伪造、搬运和刷量"}]}'},{},created,checked]);
  h.page.lifetimes.attached.call(h.page);h.page.observers.show.call(h.page,true);
  h.page.data.content='组件文案';h.page.data.picList=[];h.page.onPublish();await flush();
  assert.equal(h.calls[2].url,'/api/v1/community/posts');assert.equal(h.calls[4].url,'/api/v1/community/posts/70/publish');assert.equal(h.page.data.submitting,false);
});
test('real roam share caller uploads receipt and maps the ended own session to canonical ROUTE', async () => {
  const h=load('pages/roam/index.js',[{id:3,title:'规范',content_json:'{"rules":[{"code":"AUTHENTICITY","title":"真实体验：禁止伪造、搬运和刷量"}]}'},{},{id:12},created,checked]);
  h.app.canUseCommunity=()=>true; // Explicit current-account server gate fixture.
  h.page._shareScope=()=>()=>true;h.page._activeShareSessionId=()=>38;h.page._shareCopy=()=> '本地漫游';h.page._drawShareCard=()=>Promise.resolve('/tmp/unit-card.png');h.page.data.share={idx:0};
  h.wx.showModal=o=>queueMicrotask(()=>o.success({confirm:true,content:'本地分享'}));
  h.app.createPageBoundOperation=()=>({isAborted:()=>false,onAbort(){},attach(){},finish(){}});
  h.app.getUploadClient=()=>({uploadAll(paths,options){assert.equal(options.bizType,'COMMUNITY_POST');const url=options.mapResult({url:'/profile/card.png',uploadReceipt:'unit-private',mimeType:'image/png',byteSize:123});queueMicrotask(()=>options.onDone({ok:true,results:[url]}));return {abort(){}};}});
  const result=await h.page._shareToSquare();assert.equal(result.posted,true);assert.equal(result.postId,70);
  assert.equal(h.calls[2].url,'/api/v1/community/media/register');assert.equal(h.calls[2].data.uploadReceipt,'unit-private');assert.deepEqual(h.calls[3].data.references,[{referenceType:'ROUTE',referenceId:38}]);
});

test('actual detail comment follow readback uses canonical comments and authoritative following', async () => {
  const row={id:8,post_id:56,author_id:990011,body:'本地评论',viewer_following:1,version:1};
  const h=load('pages/square/detail/index.js',[{}, {items:[row],hasMore:false,nextCursor:8}]);
  h.page.data.id=56;h.page.data.list=[{id:8,memberId:990011,isFollowTheUser:0}];
  h.page.commentFollowClick({currentTarget:{dataset:{index:0}}});await flush();
  assert.equal(h.calls[0].url,'/api/user/follow/action');
  assert.equal(h.calls[1].url,'/api/v1/community/posts/56/comments');assert.equal(h.calls[1].method,'GET');
  assert.ok(!Object.hasOwn(h.calls[1].data,'cursor'));assert.equal(h.page.data.list[0].isFollowTheUser,1);
  assert.equal(h.page._commentFollowRequests['990011'],undefined);
});

const reportReasons = Array.from({ length: 13 }, (_, i) => ({ code: 'REASON_' + (i + 1), label: '原因' + (i + 1) }));
const reportingCapability = { reporting: { policyVersion: 'server-policy-13', reasons: reportReasons } };
function reportFixture(kind, replies = [reportingCapability, o => ({id:72,...o.data})]) {
  const h = load('pages/square/detail/index.js', replies);
  h.page.data.id = 59; h.page.data.list = [{ id: kind === 'comment' ? 3 : 59 }]; h.page._actionIndex = 0; h.page.data.currentActionId = '59';
  h.wx.showActionSheet = o => o.fail({ errMsg: 'showActionSheet:fail parameter error: itemList should not be large than 6' });
  h.startReport = () => kind === 'comment' ? h.page.reportComment({ currentTarget: { dataset: { index: 0 } } }) : h.page.reportSquare();
  return h;
}
function actualReasonSheet(page) {
  let definition;
  const file = path.join(ROOT, 'components/cy/option-sheet/index.js');
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Component: value => definition = value }, { filename: file });
  const sheet = Object.assign({}, definition.methods, { data: { rows: [], picked: -1, defaultIndex: -1 },
    setData: patch => Object.assign(sheet.data, patch), triggerEvent(name, detail) {
      if (name === 'select') page.onReportReasonSelect({ detail });
      else if (name === 'cancel') page.onReportReasonCancel();
    } });
  definition.observers.items.call(sheet, page.data.reportReasonItems); definition.observers.show.call(sheet, true);
  return sheet;
}
// 2026-10-29「动态」列表页已删,举报入口只剩详情页(帖文 + 评论)
for (const kind of ['detail', 'comment']) {
  for (const index of [0, 6, 12]) test('actual ' + kind + ' report selects reason ' + (index + 1) + ' from all thirteen', async () => {
    const h = reportFixture(kind); h.startReport(); await flush();
    assert.equal(h.page.data.reportReasonShow, true); assert.deepEqual(h.page.data.reportReasonItems, reportReasons.map(r => r.label));
    assert.equal(h.calls.length, 1, 'selection does not submit before confirmation');
    const sheet = actualReasonSheet(h.page); sheet.onRow({ currentTarget: { dataset: { index } } });
    assert.equal(h.calls.length, 1); sheet.onConfirm(); sheet.onConfirm(); await flush();
    const reports = h.calls.filter(c => c.url === '/api/v1/community/reports'); assert.equal(reports.length, 1);
    assert.equal(reports[0].data.reasonCode, reportReasons[index].code); assert.equal(reports[0].data.policyVersion, 'server-policy-13');
    assert.equal(reports[0].data.targetType, kind === 'comment' ? 'COMMENT' : 'CONTENT');
    assert.equal(reports[0].data.targetId, kind === 'comment' ? 3 : 59); assert.equal(h.page.data.reportReasonShow, false);
  });
  test('actual ' + kind + ' report cancel does not POST', async () => {
    const h = reportFixture(kind, [reportingCapability]); h.startReport(); await flush(); actualReasonSheet(h.page).onClose(); await flush();
    assert.equal(h.calls.length, 1); assert.equal(h.page.data.reportReasonShow, false);
  });
}
test('actual duplicate report clicks only load one taxonomy and submit once', async () => {
  const h = reportFixture('detail'); h.startReport(); h.startReport(); await flush();
  assert.equal(h.calls.length, 1); const sheet = actualReasonSheet(h.page); sheet.onRow({ currentTarget: { dataset: { index: 12 } } }); sheet.onConfirm(); await flush();
  assert.equal(h.calls.filter(c => c.method === 'POST').length, 1);
});
test('actual unloaded report page cancels reason selection without POST', async () => {
  const h = reportFixture('detail', [reportingCapability]); h.startReport(); await flush(); h.page.onUnload(); await flush();
  assert.equal(h.calls.length, 1); assert.equal(h.page.data.reportReasonShow, false);
});
