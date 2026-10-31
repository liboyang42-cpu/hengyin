const test = require('node:test');
const assert = require('node:assert/strict');
const { createClient, normalizePost } = require('../../utils/community-post-api');
function bundle(id = 56, extra = {}) { return { post: Object.assign({ id, authorId: 990005, authorNickname: '本地测试', body: '内容', audience: 'PUBLIC', postType: 'MOMENT', commentPolicy: 'EVERYONE', locationPrecision: 'NONE', version: 1, lifecycle: 'PUBLISHED', viewerLiked: 0, viewerBookmarked: 0, likeCount: 0 }, extra), media: [], references: [], viewerFollowing: false, viewerIsAuthor: true, topicCodes: [], mentions: [] }; }
function harness(replies = []) {
  const calls = [], modals = [], sheets = [], store = new Map();
  let state = null;
  let actor = 990005;
  const app = { globalData: {}, getUserID: () => actor, sendRequest(options) {
    calls.push(options);
    if ((options.method === 'GET' && /\/posts\/[0-9]+$/.test(options.url) && state) || replies.length) {
      let next = options.method === 'GET' && /\/posts\/[0-9]+$/.test(options.url) && state ? JSON.parse(JSON.stringify(state)) : replies.shift();
      if (typeof next === 'function') next = next(options, state);
      if (next.post && (options.method === 'PATCH' || (options.method === 'POST' && /\/posts(?:\/[0-9]+\/publish)?$/.test(options.url)))) state = next;
      queueMicrotask(() => {
      if (next.network) options.fail(next); else options.success(next.code ? next : { code: 200, data: next });
      if (options.complete) options.complete();
    }); }
    return { abort() { this.aborted = true; } };
  } };
  const wx = { getStorageSync: key => store.get(key), setStorageSync: (key, value) => store.set(key, JSON.parse(JSON.stringify(value))), removeStorageSync: key => store.delete(key), showModal(o) { modals.push(o); queueMicrotask(() => {
    if (Array.from(o.confirmText || '').length > 4) o.fail({ errMsg: 'showModal:fail confirmText length should not larger than 4 Chinese characters' });
    else o.success({ confirm: h.confirm });
  }); },
    showActionSheet(o) { sheets.push(o); queueMicrotask(() => h.select === null ? o.fail() : o.success({ tapIndex: h.select })); } };
  const h = { app, calls, modals, sheets, confirm: true, select: 0, client: createClient(app, wx), setActor(id) { actor = id; } };
  h.request = (url, data) => new Promise(resolve => h.client.request({ url, data, selectReportReason: async items => { h.sheets.push({ itemList: items }); if (h.select === null) throw { code: 499, msg: '已取消举报' }; return h.select; }, success: resolve, fail: resolve }));
  return h;
}
test('legacy detail ID is retained while transport reads canonical; viewerFollowing is real bundle state', async () => {
  const h = harness([bundle()]); const res = await h.request('/api/creativesquare/info', { id: 56 });
  assert.equal(h.calls[0].url, '/api/v1/community/posts/56'); assert.equal(res.data.id, 56); assert.equal(res.data.isFollowTheUser, 0);
});
test('normalized media retains stable IDs separately from expiring display URLs without exporting internal keys', () => {
  const b=bundle();b.media=[{id:12,objectKey:'upload/stable.png',derived_object_key:'https://owned.example/stable.png?Expires=old'}];
  const old=normalizePost(b);b.media[0].derived_object_key='https://owned.example/stable.png?Expires=new';
  const renewed=normalizePost(b);
  assert.notEqual(old.pics,renewed.pics);assert.equal(old.media[0].id,12);assert.equal(renewed.media[0].id,12);
  assert.equal(Object.hasOwn(renewed.media[0],'objectKey'),false);assert.equal(renewed.media[0].url,renewed.pics);
});
test('own/bookmark cursor pages do not use fake totals and failure does not advance cursor', async () => {
  const h = harness([{ items: [bundle()], hasMore: true, nextCursor: 56 }, { code: 500, msg: '失败' }, { items: [], hasMore: false, nextCursor: null }]);
  await h.request('/api/creativesquare/list', { favorite_only: 1, pageNum: 1, pageSize: 1 });
  await h.request('/api/creativesquare/list', { favorite_only: 1, pageNum: 2, pageSize: 1 });
  const res = await h.request('/api/creativesquare/list', { favorite_only: 1, pageNum: 2, pageSize: 1 });
  assert.equal(h.calls[0].url, '/api/v1/community/me/bookmarks'); assert.equal(h.calls[1].data.cursor, 56); assert.equal(h.calls[2].data.cursor, 56);
  assert.equal(res.data.hasMore, false); assert.equal(res.data.total, undefined);
  const own = harness([{ items: [], hasMore: false, nextCursor: null }]); await own.request('/api/creativesquare/list', { is_my: 1, pageNum: 1 }); assert.equal(own.calls[0].url, '/api/v1/community/me/posts');
});
test('bookmark and cancel preserve request IDs and canonical result', async () => {
  const h = harness([bundle(56, { viewerBookmarked: 1 }), bundle()]);
  const res = await h.request('/api/creativesquare/bookmark', { id: 56, bookmark: 1, request_id: 'bookmark-1' });
  await h.request('/api/creativesquare/bookmark', { id: 56, bookmark: 0, request_id: 'bookmark-0' });
  assert.equal(res.data.isBookmarked, 1); assert.equal(h.calls[0].method, 'POST'); assert.equal(h.calls[0].data.requestId, 'bookmark-1');
  assert.equal(h.calls[1].method, 'DELETE'); assert.equal(h.calls[1].url, '/api/v1/community/posts/56/actions/BOOKMARK');
});
test('LIKE state comes from canonical and legacy type2 cannot silently become LIKE', async () => {
  const h = harness([bundle(), bundle(56, { viewerLiked: 1, likeCount: 5 })]); await h.request('/api/creativesquare/info', { id: 56 });
  const res = await h.request('/api/creativesquare/like', { id: 56, type: 1 }); assert.equal(res.data.likeCount, 5);
  const count = h.calls.length; const rejected = await h.request('/api/creativesquare/like', { id: 56, type: 2 }); assert.equal(rejected.code, 409); assert.equal(h.calls.length, count);
});
test('actor changes invalidate cached ownership/version/action state', async () => {
  const h = harness([bundle()]); await h.request('/api/creativesquare/info', { id: 56 }); h.setActor(990011);
  const res = await h.request('/api/creativesquare/delete', { id: 56 }); assert.equal(res.code, 409); assert.equal(h.calls.length, 1);
});
test('root-comment pagination preserves replies and server nextCursor', async () => {
  const h = harness([{ items: [{ id: 9, post_id: 56, body: '根', author_id: 990005, version: 0 }, { id: 10, root_id: 9, post_id: 56, body: '回复', author_id: 990011 }], hasMore: true, nextCursor: 9 }]);
  const res = await h.request('/api/comment/list', { owner_id: 56, pageNum: 1, pageSize: 1 }); assert.equal(res.data.rows.length, 2); assert.equal(res.data.hasMore, true); assert.equal(res.data.nextCursor, 9);
});
test('delete uses actual canonical version and comment post scope', async () => {
  const h = harness([bundle(56, { version: 7 }), {}, { items: [{ id: 9, post_id: 56, body: '评论', version: 4 }], hasMore: false, nextCursor: 9 }, {}]);
  await h.request('/api/creativesquare/info', { id: 56 }); await h.request('/api/creativesquare/delete', { id: 56 });
  await h.request('/api/comment/list', { owner_id: 56 }); await h.request('/api/comment/delete', { id: 9 });
  assert.equal(h.calls[1].data.expectedVersion, 7); assert.equal(h.calls[3].data.expectedVersion, 4); assert.equal(h.calls[3].url, '/api/v1/community/posts/56/comments/9');
});
const guideline = { id: 3, title: '规范', summary: '请阅读', content_json: '{"rules":[{"code":"LOCAL","title":"本地规则"}]}' };
const created = (options) => {
  const b = bundle(70, { ...options.data, lifecycle: 'DRAFT', version: 0 });
  b.media = options.data.mediaIds.map(id => ({ id })); b.references = options.data.references;
  b.topicCodes = options.data.topicCodes || []; b.mentions = options.data.mentionedMemberIds || [];
  return b;
};
const checked = (_options, state) => ({ ...state, post: { ...state.post, lifecycle: 'CHECKING', version: state.post.version + 1 } });
const patched = (options) => {
  const b = bundle(56, { ...options.data, lifecycle: 'CHECKING', version: options.data.expectedVersion + 1, safetyLabelsJson: JSON.stringify(options.data.safetyLabels || []) });
  b.media = options.data.mediaIds.map(id => ({ id })); b.references = options.data.references; b.topicCodes = options.data.topicCodes; b.mentions = options.data.mentionedMemberIds;
  return b;
};

test('guideline cancellation stops before ack/draft/publish', async () => {
  const h = harness([guideline]); h.confirm = false; const res = await h.request('/api/creativesquare/action', { contents: '本地文案', request_id: 'intent-1' });
  assert.equal(res.code, 499); assert.equal(h.calls.length, 1); assert.match(h.modals[0].content, /本地规则/);
});
test('new draft then publish use real version; checking is not reported as published', async () => {
  const h = harness([guideline, {}, created, checked]);
  const res = await h.request('/api/creativesquare/action', { contents: '本地文案', request_id: 'intent-1' });
  assert.deepEqual(h.calls.map(c => c.url), ['/api/v1/community/guidelines/active', '/api/v1/community/guidelines/ack', '/api/v1/community/posts', '/api/v1/community/posts/70', '/api/v1/community/posts/70/publish', '/api/v1/community/posts/70']);
  assert.equal(h.calls[2].data.clientRequestId, 'intent-1'); assert.equal(h.calls[4].data.expectedVersion, 0); assert.equal(res.data.lifecycle, 'CHECKING'); assert.equal(res.data.status, 0);
});
test('unreceipted media and unresolved geo refuse rather than fabricate metadata', async () => {
  const h = harness([guideline, {}]); const res = await h.request('/api/creativesquare/action', { contents: '图片', pics: 'https://local/image' });
  assert.equal(res.code, 409); assert.equal(h.calls.length, 2);
  const geo = harness(); const failed = await geo.request('/api/creativesquare/action', { contents: '地点', address: '地点' }); assert.equal(failed.code, 409); assert.equal(geo.calls.length, 0);
});
test('real upload receipt registers media ID; ended own roam source uses existing ROUTE', async () => {
  const h = harness([guideline, {}, { id: 12 }, created, checked]);
  h.client.captureUpload({ url: '/profile/local.png', mimeType: 'image/png', byteSize: 123, uploadReceipt: 'private-in-memory' });
  await h.request('/api/creativesquare/action', { contents: '分享', pics: '/profile/local.png', data_id: 38, data_type: 3, request_id: 'intent-route' });
  assert.equal(h.calls[2].url, '/api/v1/community/media/register'); assert.equal(h.calls[2].data.uploadReceipt, 'private-in-memory');
  assert.deepEqual(h.calls[3].data.mediaIds, [12]); assert.deepEqual(h.calls[3].data.references, [{ referenceType: 'ROUTE', referenceId: 38 }]);
});
test('editing a published post preserves media/geo/relationships and stops at real CHECKING review', async () => {
  const existing = bundle(56, { locationPrecision: 'APPROXIMATE', cityCode: '110100', approximateGeohash: 'wx4g', audience: 'PUBLIC', commentPolicy: 'EVERYONE', postType: 'MOMENT', safetyLabelsJson: '[]' }); existing.media = [{ id: 12 }]; existing.topicCodes = ['LOCAL']; existing.mentions = [990011];
  const h = harness([guideline, {}, existing, patched]);
  const res = await h.request('/api/creativesquare/action', { id: 56, contents: '改正文' });
  assert.equal(h.calls.length, 5); assert.equal(h.calls[3].method, 'PATCH'); assert.equal(h.calls[3].data.expectedVersion, 1);
  assert.equal(h.calls[3].data.approximateGeohash, 'wx4g'); assert.deepEqual(h.calls[3].data.mediaIds, [12]); assert.deepEqual(h.calls[3].data.topicCodes, ['LOCAL']); assert.equal(res.data.lifecycle, 'CHECKING');
});
test('report uses capability reason chosen by human, cancelling sends no report', async () => {
  const cap = { reporting: { policyVersion: 'actual-v1', reasons: [{ code: 'ACTUAL', label: '真实原因' }] } };
  const h = harness([cap, o => ({id:1,...o.data})]); await h.request('/api/comment/report', { id: 9 }); assert.equal(h.calls[1].data.reasonCode, 'ACTUAL'); assert.equal(h.calls[1].data.targetType, 'COMMENT');
  const cancel = harness([cap]); cancel.select = null; const res = await cancel.request('/api/creativesquare/report', { id: 56 }); assert.equal(res.code, 499); assert.equal(cancel.calls.length, 1);
});
test('malformed envelope is a failure, never an empty successful collection', async () => {
  const h = harness([{ rows: [] }]); const res = await h.request('/api/creativesquare/list', { favorite_only: 1 }); assert.equal(res.code, 500); assert.match(res.msg, /不完整/);
});
test('safe reference projection uses server snapshot; old shared ID remains canonical ID', () => {
  const b = bundle(); b.references = [{ reference_type: 'ROUTE', reference_id: 38, snapshot_json: '{"title":"已结束漫游","distance_m":100}' }];
  const row = normalizePost(b); assert.equal(row.dataType, 3); assert.equal(row.dataId, 38); assert.equal(row.sportName, '已结束漫游'); assert.equal(row.id, 56);
});
test('unknown create retry reuses intent and real registration ID instead of inventing another payload', async () => {
  const h = harness([guideline, {}, { id: 12 }, { network: true, msg: '未知' }, {items:[],hasMore:false,nextCursor:null}, created, checked]);
  h.client.captureUpload({url:'/profile/owned.png',uploadReceipt:'in-memory',byteSize:123,mimeType:'image/png'});
  const form={contents:'同一意图',pics:'/profile/owned.png',request_id:'stable-intent'};
  await h.request('/api/creativesquare/action',form);await h.request('/api/creativesquare/action',form);
  assert.equal(h.calls.filter(c=>c.url.endsWith('/media/register')).length,1);
  const creates=h.calls.filter(c=>c.url==='/api/v1/community/posts');
  assert.equal(creates.length,2);assert.deepEqual(creates[0].data,creates[1].data);
  assert.equal(h.calls[4].url,'/api/v1/community/drafts');assert.equal(h.modals.at(-1).confirmText,'原样重试');
});
test('unknown bookmark retry keeps request identity while success clears the pending command', async () => {
  const h=harness([{code:500,msg:'结果待确认'},bundle(56,{viewerBookmarked:1})]);
  await h.request('/api/creativesquare/bookmark',{id:56,bookmark:1,request_id:'first-key'});
  await h.request('/api/creativesquare/bookmark',{id:56,bookmark:1,request_id:'different-new-click'});
  assert.equal(h.calls[1].data.requestId,'first-key');
});
test('editing preserves secondary references and owner mentions without inventing snapshots', async () => {
  const previous=bundle();previous.references=[{reference_type:'ACTIVITY',reference_id:27},{reference_type:'TOPIC',reference_id:990044}];previous.topicCodes=['LOCAL'];previous.mentions=[990011];previous.post.safetyLabelsJson='[]';
  const h=harness([guideline,{},previous,patched]);
  await h.request('/api/creativesquare/action',{id:56,contents:'改正文',data_id:27,data_type:1});
  assert.deepEqual(h.calls[3].data.references,[{referenceType:'ACTIVITY',referenceId:27},{referenceType:'TOPIC',referenceId:990044}]);
  assert.deepEqual(h.calls[3].data.mentionedMemberIds,[990011]);assert.deepEqual(h.calls[3].data.topicCodes,['LOCAL']);
});
test('cold media intent without receipt stays blocked and instructs own-post readback, never creates another draft', async () => {
  const h=harness([guideline,{}]);const result=await h.request('/api/creativesquare/action',{contents:'冷恢复',pics:'/profile/owned.png',request_id:'existing-intent'});
  assert.equal(result.code,409);assert.match(result.msg,/我的帖文查看发布结果/);assert.equal(h.calls.some(c=>c.url.endsWith('/posts')),false);
});
test('actor switch during publish preflight cannot acknowledge or publish as another member', async () => {
  const h=harness([guideline]);let delivered=0;h.client.request({url:'/api/creativesquare/action',data:{contents:'文案'},success(){delivered++},fail(){delivered++}});
  await Promise.resolve();h.setActor(990011);await new Promise(resolve=>setImmediate(resolve));assert.equal(delivered,0);assert.equal(h.calls.length,1);
});
test('abort of an in-flight publish finishes once without reporting success', async () => {
  const h=harness();let completed=0,succeeded=0;
  const task=h.client.request({url:'/api/creativesquare/action',data:{contents:'文案'},success:()=>succeeded++,complete:()=>completed++});
  await Promise.resolve();task.abort();await new Promise(resolve=>setImmediate(resolve));assert.equal(completed,1);assert.equal(succeeded,0);
});
test('nonpublic lifecycle is retained instead of being forged into an old rejected label', () => {
  for(const lifecycle of ['LIMITED','HIDDEN','REMOVED']){const row=normalizePost(bundle(56,{lifecycle}));assert.equal(row.lifecycle,lifecycle);assert.equal(row.status,lifecycle);assert.notEqual(row.status,2);}
});
test('existing badge displays exact canonical lifecycle labels while preserving legacy rejection', () => {
  const fs=require('node:fs'),path=require('node:path');
  const wxml=fs.readFileSync(path.join(__dirname,'../../components/cy/post-card/index.wxml'),'utf8');
  function assertBadge(source){assert.match(source,/post\.lifecycleLabel \|\| \(!post\.lifecycle && post\.status == 2\)/);assert.match(source,/\{\{post\.lifecycleLabel \|\| '未通过审核 · 仅自己可见'\}\}/);}
  assertBadge(wxml);
  const mutation=wxml.replace("{{post.lifecycleLabel || '未通过审核 · 仅自己可见'}}",'未通过审核 · 仅自己可见');assert.notEqual(mutation,wxml);assert.throws(()=>assertBadge(mutation),assert.AssertionError);
  for(const [lifecycle,label]of Object.entries({DRAFT:'草稿',CHECKING:'审核中',LIMITED:'展示受限',HIDDEN:'已隐藏',REMOVED:'已移除'})){assert.equal(normalizePost(bundle(56,{lifecycle})).lifecycleLabel,label);}
  assert.equal(normalizePost(bundle()).lifecycleLabel,'');
});
test('comment following projection is actual numeric state, unknown is not fabricated', () => {
  const {normalizeComment}=require('../../utils/community-post-api');
  assert.equal(normalizeComment({id:9,viewer_following:1}).isFollowTheUser,1);
  assert.equal(normalizeComment({id:9,viewer_following:0}).isFollowTheUser,0);
  assert.equal(normalizeComment({id:9}).isFollowTheUser,undefined);
});
test('compose-style non-silent publish reports business failure while preserving callback/complete', async () => {
  const h=harness([{code:503,msg:'规范未启用'}]);const notices=[];h.app.tips=msg=>notices.push(msg);let completed=0;
  const response=await new Promise(resolve=>h.client.request({url:'/api/creativesquare/action',data:{contents:'保留文案'},success:resolve,complete:()=>completed++}));
  await new Promise(resolve=>setImmediate(resolve));assert.equal(response.code,503);assert.deepEqual(notices,['规范未启用']);assert.equal(completed,1);
});

test('actual seven guideline rules are readable verbatim without JSON keys or policy codes', async () => {
  const rules = [{"code": "AUTHENTICITY", "title": "真实体验：禁止伪造、搬运和刷量"}, {"code": "USEFULNESS", "title": "帮助下一位探索者：补充难度、花费、无障碍和安全提醒"}, {"code": "LOCAL_PRIVACY", "title": "尊重在地的人：不骚扰、不擅闯、不曝光私人地址或人脸"}, {"code": "SAFETY", "title": "安全先于挑战：危险地点必须标记，禁止鼓励高风险行为"}, {"code": "DISCLOSURE", "title": "利益透明：商单、赠品、主理人或商家身份必须披露"}, {"code": "CIVILITY", "title": "友善讨论：反对攻击、歧视、围攻和人肉"}, {"code": "COPYRIGHT", "title": "尊重版权：图片、路线和文字注明来源"}];
  const h=harness([{id:3,title:'城市探索者公约',summary:'真实、在地、互助、安全探索',content_json:JSON.stringify({rules})}]); h.confirm=false;
  await h.request('/api/creativesquare/action',{contents:'本地文案'});
  assert.equal(h.modals[0].content,'真实、在地、互助、安全探索\n'+rules.map((r,i)=>(i+1)+'. '+r.title).join('\n'));
  for(const rule of rules) assert.ok(!h.modals[0].content.includes(rule.code));
  assert.equal(h.calls.length,1);
});
test('bad JSON and unreadable guideline rules block before modal ack or create', async () => {
  for(const content_json of ['{bad',JSON.stringify({rules:[]}),JSON.stringify({rules:[{code:'ONLY_CODE'}]})]) {
    const h=harness([{id:3,content_json}]);h.confirm=false;const res=await h.request('/api/creativesquare/action',{contents:'本地文案'});
    assert.equal(res.code,503);assert.equal(h.calls.length,1);assert.equal(h.modals.length,0);
  }
});

test('native share keeps existing analytics and route without a silent canonical write', () => {
  const fs=require('node:fs');const path=require('node:path');
  const h=harness();assert.equal(h.client.share,undefined);
  for(const page of ['detail']) { // 2026-10-29:广场列表页已删
    const source=fs.readFileSync(path.join(__dirname,'../../pages/square',page,'index.js'),'utf8');
    const validate=value=>assert.ok(!value.includes('community.share('));
    validate(source);
    assert.ok(source.includes("analytics.track('content_share'"));
    // U-050:转发卡统一由 utils/square-share.js 生成(公开帖才指向详情)
    assert.ok(source.includes('buildSquareShare('));
    assert.throws(()=>validate('community.share(56);'+source));
  }
  const shareSource=fs.readFileSync(path.join(__dirname,'../../pages/square/utils/square-share.js'),'utf8');
  assert.ok(shareSource.includes('/pages/square/detail/index?id='));
  assert.ok(!shareSource.includes('community.share('));
});

test('GET first page omits empty cursor in real query serialization for all shared pagination routes', async () => {
  for(const [url,data] of [['/api/comment/list',{owner_id:56}],['/api/creativesquare/list',{}],['/api/creativesquare/list',{is_my:1}],['/api/creativesquare/list',{favorite_only:1}]]) {
    const h=harness([{items:[],hasMore:true,nextCursor:56},{code:500,msg:'retry'},{items:[],hasMore:false,nextCursor:null}]);
    await h.request(url,{...data,pageNum:1,pageSize:10});
    const query=options=>new URLSearchParams(Object.entries(options.data).map(([key,value])=>[key,String(value)])).toString();
    assert.equal(query(h.calls[0]),'limit=10');
    assert.ok(!Object.hasOwn(h.calls[0].data,'cursor'));
    assert.throws(()=>assert.equal(query({data:{cursor:null,limit:10}}),'limit=10'));
    await h.request(url,{...data,pageNum:2,pageSize:10});await h.request(url,{...data,pageNum:2,pageSize:10});
    assert.equal(query(h.calls[1]),'limit=10&cursor=56');assert.equal(query(h.calls[2]),query(h.calls[1]));
  }
});

test('invalid bundle never commits the next cursor before a successful retry', async () => {
  const h=harness([{items:[bundle(60)],hasMore:true,nextCursor:60},{items:[{}],hasMore:true,nextCursor:50},{items:[bundle(55)],hasMore:true,nextCursor:55},{items:[],hasMore:false,nextCursor:null}]);
  await h.request('/api/creativesquare/list',{pageNum:1,pageSize:1});
  const failed=await h.request('/api/creativesquare/list',{pageNum:2,pageSize:1});assert.notEqual(failed.code,200);
  const skipped=await h.request('/api/creativesquare/list',{pageNum:3,pageSize:1});assert.equal(skipped.code,409);assert.equal(h.calls.length,2);
  await h.request('/api/creativesquare/list',{pageNum:2,pageSize:1});await h.request('/api/creativesquare/list',{pageNum:3,pageSize:1});
  assert.equal(h.calls[2].data.cursor,60);assert.equal(h.calls[3].data.cursor,55);
});


test('activity reference uses current public topic projection, never activity ID or stale snapshot', () => {
  const { normalizePost } = require('../../utils/community-post-api.js');
  const b = bundle();
  b.references = [{ reference_type: 'ACTIVITY', reference_id: 70, topic_id: 900, snapshot_json: '{"legacy":true}' }];
  assert.equal(normalizePost(b).sportTopicId, 900);
  assert.equal(normalizePost(b).dataId, 70);
  for (const topic_id of [null, undefined, 0, -1, 'bad', true, false, {}, 1.5]) {
    b.references = [{ reference_type: 'ACTIVITY', reference_id: 70, topic_id, snapshot_json: '{"topic_id":900}' }];
    assert.equal(normalizePost(b).sportTopicId, undefined);
  }
  b.references = [{ reference_type: 'TOPIC', reference_id: 900, topic_id: 70, snapshot_json: '{}' }];
  assert.equal(normalizePost(b).sportTopicId, 900);
});

test('acknowledged community writes invalidate all cached feeds; reads and failures do not', async () => {
  const h = harness([bundle(), bundle(56, { viewerLiked: 1 }), { code: 500 }, {}, {}]);
  await h.request('/api/creativesquare/info', { id: 56 });
  assert.equal(h.app.globalData.squareListDirtyVersion, undefined);
  await h.request('/api/creativesquare/like', { id: 56, type: 1 });
  assert.equal(h.app.globalData.squareListDirtyVersion, 1);
  assert.equal(h.app.globalData.squareListDirty, true);
  await h.request('/api/creativesquare/bookmark', { id: 56, bookmark: 1 });
  assert.equal(h.app.globalData.squareListDirtyVersion, 1);
  await h.request('/api/comment/add', { owner_id: 56, contents: '回复' });
  await h.request('/api/creativesquare/delete', { id: 56 });
  assert.equal(h.app.globalData.squareListDirtyVersion, 3);
});
test('publish/edit invalidate after committed draft even if publishing later fails', async () => {
  const h = harness([guideline, {}, created, { code: 500 }]);
  assert.equal((await h.request('/api/creativesquare/action', { contents: '文案' })).code, 500);
  assert.equal(h.app.globalData.squareListDirtyVersion, 1);
  const edit = harness([guideline, {}, bundle(70, { lifecycle: 'CHECKING' }), patched]);
  await edit.request('/api/creativesquare/action', { id: 70, contents: '新文案' });
  assert.equal(edit.app.globalData.squareListDirtyVersion, 1);
});
test('committed write with malformed response still invalidates, changed actor does not', async () => {
  const h = harness([{ invalid: true }]);
  assert.equal((await h.request('/api/creativesquare/bookmark', { id: 56, bookmark: 1 })).code, 500);
  assert.equal(h.app.globalData.squareListDirtyVersion, 1);
  const changed = harness();
  const pending = changed.request('/api/comment/add', { owner_id: 56, contents: '内容' });
  changed.setActor(990011);
  changed.calls[0].success({ code: 200, data: {} });
  assert.equal((await pending).code, 409);
  assert.equal(changed.app.globalData.squareListDirtyVersion, undefined);
});
test('guideline cancellation and report submission do not dirty community feeds', async () => {
  const h = harness([guideline]); h.confirm = false;
  await h.request('/api/creativesquare/action', { contents: '文案' });
  assert.equal(h.app.globalData.squareListDirtyVersion, undefined);
  const reported = harness([{ reporting: { reasons: [{ label: '原因', code: 'OTHER' }], policyVersion: 1 } }, {}]);
  await reported.request('/api/creativesquare/report', { id: 56 });
  assert.equal(reported.app.globalData.squareListDirtyVersion, undefined);
});
