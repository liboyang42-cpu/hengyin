'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { createClient } = require('../../utils/community-post-api');
const tick = () => new Promise(resolve => setImmediate(resolve));
const copy = value => JSON.parse(JSON.stringify(value));
function harness(settings = {}, shared = {}) {
  const store = shared.store || new Map(), state = shared.state || { bundle: null };
  const calls = [], dialogs = []; let owner = '990005', failed = false, rejected = false, writeCount = 0, readFailed = false, advancedGuideline = false;
  const bundle = body => ({ post: { id: 70, authorId: 990005, version: 0, lifecycle: 'DRAFT', ...copy(body), safetyLabelsJson: JSON.stringify(body.safetyLabels || []) },
    media: (body.mediaIds || []).map(id => ({ id })), references: copy(body.references || []), viewerIsAuthor: true, viewerFollowing: false,
    topicCodes: copy(body.topicCodes || []), mentions: copy(body.mentionedMemberIds || []) });
  const wxApi = { getStorageSync: key => store.get(key), setStorageSync(key, value) {
    if (settings.storageFailure || ++writeCount===settings.storageFailureOnWrite) throw Error('synthetic storage'); store.set(key, copy(value));
  }, removeStorageSync: key => store.delete(key), showModal(options) { dialogs.push(options); assert.ok(Array.from(options.confirmText || '').length<=4,'native modal confirmText limit'); queueMicrotask(() => options.success({ confirm: settings.confirm !== false })); } };
  const app = { globalData: {}, getUserID: () => owner, tips() {}, sendRequest(options) {
    calls.push(options); queueMicrotask(() => {
      const key = options.method + ' ' + options.url;
      if(settings.rejectNext===key){settings.rejectNext=null;return options.success({code:400,msg:'明确拒绝'});}
      if(!rejected && settings.rejectOnce===key){rejected=true;return options.success({code:400,msg:'明确拒绝'});}
      if(!failed && settings.failBeforeWrite===key){failed=true;return options.fail({errMsg:'lost before commit'});}
      if ((settings.readFails || settings.readFailsOnce && !readFailed) && options.method === 'GET' && options.url.includes('/posts/')) { readFailed=true; return options.fail({ errMsg: 'read failed' }); }
      let data;
      if (options.url.endsWith('/guidelines/active')) data = { id: state.guidelineId || 3, title: '真实规范', content_json: '{"rules":[{"title":"真实体验"}]}' };
      else if (options.url.endsWith('/guidelines/ack')) {
        const pending=[...store.values()].find(value=>value.ackBody);assert.deepEqual(pending.ackBody,options.data,'ACK wire persisted before HTTP');
        const body=options.data;state.ackByKey=state.ackByKey||{};
        if(body.guidelineVersionId!==(state.guidelineId||3)) return options.success({code:409,msg:'请确认当前有效的社区规范'});
        const alreadyAcked=Object.values(state.ackByKey).includes(body.guidelineVersionId);
        if(!alreadyAcked && Object.hasOwn(state.ackByKey,body.requestId)) return options.success({code:409,msg:'请求号已被使用'});
        if(!alreadyAcked)state.ackByKey[body.requestId]=body.guidelineVersionId;data={};
      }
      else if (options.url.endsWith('/media/register')) data = { id: 12 };
      else if (options.url.endsWith('/drafts')) data = settings.draftsPage || { items: state.bundle ? [copy(state.bundle)] : [], hasMore: false, nextCursor: state.bundle ? 70 : null };
      else if (key === 'POST /api/v1/community/posts') { if(settings.discardCreated)data={intentDiscarded:true};else {state.bundle = bundle(options.data); data = copy(state.bundle);} }
      else if (options.url.endsWith('/publish')) { state.bundle.post.lifecycle = 'CHECKING'; state.bundle.post.version += 1; data = copy(state.bundle); }
      else if (options.method === 'PATCH') { const previousLifecycle=state.bundle.post.lifecycle; state.bundle = bundle({ ...state.bundle.post, ...options.data }); state.bundle.post.version = options.data.expectedVersion + 1; state.bundle.post.lifecycle = previousLifecycle==='DRAFT'?'DRAFT':'CHECKING'; data = copy(state.bundle); }
      else if (options.method === 'GET') data = copy(state.bundle);
      else throw Error('unexpected ' + key);
      if(options.url.endsWith('/guidelines/active') && settings.advanceGuidelineAfterRead && !advancedGuideline){advancedGuideline=true;state.guidelineId=4;}
      if (!failed && settings.loseResponse === key) { failed = true; options.fail({ errMsg: 'response lost after commit' }); }
      else options.success({ code: 200, data });
    }); return { abort() {} };
  } };
  const client = createClient(app, wxApi);
  const request = data => new Promise(resolve => client.request({ url: '/api/creativesquare/action', intentScope: 'test', data, success: resolve, fail: resolve }));
  return { client, request, calls, dialogs, store, state, bundle, owner: value => { owner = value; } };
}
const form = { contents: '保存的正文', pics: '', request_id: 'publish-original-request-12345' };

test('actual create persists complete original wire and uses independent draft/published reads before success', async () => {
  const h = harness(); const result = await h.request(form);
  assert.equal(result.code, 200); assert.equal(result.data.lifecycle, 'CHECKING');
  assert.deepEqual(h.calls.map(c => c.method + ' ' + c.url), ['GET /api/v1/community/guidelines/active', 'POST /api/v1/community/guidelines/ack',
    'POST /api/v1/community/posts', 'GET /api/v1/community/posts/70', 'POST /api/v1/community/posts/70/publish', 'GET /api/v1/community/posts/70']);
  assert.equal(h.calls[2].data.clientRequestId, form.request_id); assert.equal(h.store.size, 0);
});
test('cold recovery of committed create finds original owner draft and does not create again', async () => {
  const a = harness({ loseResponse: 'POST /api/v1/community/posts' }); const first = await a.request(form); assert.equal(first.transportFailure, true);
  const originalWire = copy(a.calls.find(c => c.url.endsWith('/posts')).data);
  const b = harness({}, a); const result = await b.request(form);
  assert.equal(result.code, 200); assert.equal(b.calls[0].url, '/api/v1/community/drafts');
  assert.equal(b.calls.filter(c => c.method === 'POST' && c.url.endsWith('/posts')).length, 0);
  assert.deepEqual(a.state.bundle.post.clientRequestId, originalWire.clientRequestId);
});
test('cold recovery of a committed publish reads final state without a second publish', async () => {
  const a = harness({ loseResponse: 'POST /api/v1/community/posts/70/publish' }); await a.request(form);
  const b = harness({}, a); assert.equal((await b.request(form)).code, 200);
  assert.deepEqual(b.calls.map(c => c.method), ['GET']); assert.equal(b.store.size, 0);
});
test('current owner CHECKING edit preserves references/media and recovers committed CAS without a second PATCH', async () => {
  const a = harness({ loseResponse: 'PATCH /api/v1/community/posts/70' });
  a.state.bundle = a.bundle({ ...form, body: '原文', audience: 'PRIVATE', commentPolicy: 'EVERYONE', postType: 'MOMENT',
    locationPrecision: 'APPROXIMATE', approximateGeohash: 'wx4g', mediaIds: [12], references: [{ referenceType: 'ACTIVITY', referenceId: 27 }, { referenceType: 'TOPIC', referenceId: 9 }] });
  a.state.bundle.post.lifecycle = 'CHECKING'; a.state.bundle.post.version = 3;
  await a.request({ id: 70, contents: '新的正文', data_id: 27, data_type: 1 });
  const patch = a.calls.find(c => c.method === 'PATCH'); assert.equal(patch.data.expectedVersion, 3);
  assert.deepEqual(patch.data.mediaIds, [12]); assert.equal(patch.data.references.length, 2); assert.equal(patch.data.approximateGeohash, 'wx4g');
  const b = harness({}, a); const result = await b.request({ id: 70, contents: '新的正文', data_id: 27, data_type: 1 });
  assert.equal(result.code, 200); assert.equal(result.data.version, 4); assert.deepEqual(b.calls.map(c => c.method), ['GET']);
});
test('changed input after unknown confirms original result and marks subsequent input for caller preservation', async () => {
  const a = harness({ loseResponse: 'POST /api/v1/community/posts/70/publish' }); await a.request(form);
  const b = harness({}, a); const result = await b.request({ ...form, contents: '这份新输入不能覆盖旧提交', request_id: 'new-click-key-must-not-replace' });
  assert.equal(result.code, 200); assert.equal(result.recoveredPrevious, true); assert.equal(result.data.contents, form.contents);
  assert.equal(b.calls.some(c => c.method !== 'GET'), false); assert.match(b.dialogs[0].content, /本次新输入会保留/);
});
test('storage unavailable or authoritative read failure prevents subsequent writes', async () => {
  const storage = harness({ storageFailure: true }); assert.equal((await storage.request(form)).code, 507); assert.equal(storage.calls.length, 0);
  const a = harness({ loseResponse: 'POST /api/v1/community/posts/70/publish' }); await a.request(form);
  const b = harness({ readFails: true }, a); const failed = await b.request(form);
  assert.equal(failed.transportFailure, true); assert.equal(b.calls.length, 1); assert.equal(b.store.size, 1);
});
test('unknown edit with a changed version/effect never overwrites the later authoritative state', async () => {
  const a=harness({loseResponse:'PATCH /api/v1/community/posts/70'});
  a.state.bundle=a.bundle({body:'原文',audience:'PUBLIC',commentPolicy:'EVERYONE',postType:'MOMENT',locationPrecision:'NONE',mediaIds:[],references:[]});
  await a.request({id:70,contents:'同一次编辑'});a.state.bundle.post.version+=1;a.state.bundle.post.body='别处后续修改';
  const b=harness({},a);const result=await b.request({id:70,contents:'同一次编辑'});
  assert.equal(result.code,409);assert.deepEqual(b.calls.map(c=>c.method),['GET']);assert.equal(b.store.size,1);
});
test('mismatched post write response cannot clear the original intent or report publication success', async () => {
  const a=harness({loseResponse:'POST /api/v1/community/posts/70/publish'});await a.request(form);a.state.bundle.post.body='不一致的正文';
  const b=harness({},a);const result=await b.request(form);
  assert.equal(result.code,409);assert.deepEqual(b.calls.map(c=>c.method),['GET']);assert.equal(b.store.size,1);
});
test('cancel changed-input recovery keeps both original server effect and original persisted intent', async () => {
  const a=harness({loseResponse:'POST /api/v1/community/posts/70/publish'});await a.request(form);
  const b=harness({confirm:false},a);const before=copy([...b.store]);const result=await b.request({...form,contents:'不同新输入'});
  assert.equal(result.code,499);assert.equal(b.calls.length,0);assert.equal(b.state.bundle.post.body,form.contents);
  assert.equal([...b.store][0][1].createBody.clientRequestId,before[0][1].createBody.clientRequestId);
});

test('explicit deleted-submit terminal preserves intentDiscarded callback and archives only the original key', async () => {
  const h=harness({discardCreated:true});const result=await h.request(form);
  assert.equal(result.code,409);assert.equal(result.intentDiscarded,true);
  assert.equal(h.calls.filter(c=>c.method==='POST' && c.url.endsWith('/posts')).length,1);
  assert.equal(h.state.bundle,null);assert.equal([...h.store.values()][0].status,'discarded');
  assert.equal([...h.store.keys()].every(key=>key.endsWith(':previous')),true);
});

test('first explicit publish400 keeps the created draft and permits corrected text on that same draft', async () => {
  const h=harness({rejectOnce:'POST /api/v1/community/posts/70/publish'});
  assert.equal((await h.request(form)).code,400);assert.equal(h.state.bundle.post.lifecycle,'DRAFT');
  const result=await h.request({...form,contents:'修正后可提交的正文'});
  assert.equal(result.code,200);assert.equal(result.data.id,70);assert.equal(h.state.bundle.post.body,'修正后可提交的正文');
  assert.equal(h.calls.filter(c=>c.method==='POST'&&c.url.endsWith('/posts')).length,1);
  assert.equal(h.calls.filter(c=>c.method==='PATCH').length,1);
  assert.equal(h.calls.filter(c=>c.url.endsWith('/publish')).length,2);
});
test('first explicit publish400 can resume from the same draft edit ID instead of locking its original create scope', async () => {
  const h=harness({rejectOnce:'POST /api/v1/community/posts/70/publish'});await h.request(form);
  const result=await h.request({id:70,contents:'在原草稿编辑后重提'});
  assert.equal(result.code,200);assert.equal(result.data.id,70);assert.equal(h.state.bundle.post.body,'在原草稿编辑后重提');
  assert.equal(h.calls.filter(c=>c.method==='POST'&&c.url.endsWith('/posts')).length,1);
});
test('first explicit edit400 permits a corrected CAS edit on the original post', async () => {
  const h=harness({rejectOnce:'PATCH /api/v1/community/posts/70'});h.state.bundle=h.bundle({body:'原文',audience:'PUBLIC',postType:'MOMENT',commentPolicy:'EVERYONE',locationPrecision:'NONE',mediaIds:[],references:[]});
  assert.equal((await h.request({id:70,contents:'被拒绝的修改'})).code,400);
  const result=await h.request({id:70,contents:'修正后的修改'});assert.equal(result.code,200);assert.equal(h.state.bundle.post.body,'修正后的修改');
});
test('a prior unknown create retried with400 still retains the original key/body and never creates a new intent', async () => {
  const a=harness({failBeforeWrite:'POST /api/v1/community/posts'});await a.request(form);
  const original=copy([...a.store.values()][0].createBody);
  const b=harness({draftsPage:{items:[],hasMore:false},rejectOnce:'POST /api/v1/community/posts'},a);
  assert.equal((await b.request({...form,contents:'不得替换真正未知的内容'})).code,400);
  assert.deepEqual([...b.store.values()][0].createBody,original);assert.equal([...b.store.values()][0].status,'unknown');
  assert.deepEqual(b.calls.find(c=>c.method==='POST'&&c.url.endsWith('/posts')).data,original);
});
test('a drafts response missing explicit hasMore=false cannot authorize an empty-page retry', async () => {
  const a=harness({failBeforeWrite:'POST /api/v1/community/posts'});await a.request(form);
  const b=harness({draftsPage:{items:[]}},a);assert.equal((await b.request(form)).code,409);
  assert.equal(b.calls.some(c=>c.method==='POST'),false);
});
test('second storage write failure before any HTTP clears only this newly created partial intent and allows retry', async () => {
  const h=harness({storageFailureOnWrite:2});assert.equal((await h.request(form)).code,507);
  assert.equal(h.calls.length,0);assert.equal(h.store.size,0);
  assert.equal((await h.request(form)).code,200);
});

test('cancelling a known-rejection correction does not convert it into an unknown write or discard its draft', async () => {
  const a=harness({rejectOnce:'POST /api/v1/community/posts/70/publish'});await a.request(form);
  const b=harness({confirm:false},a);const before=copy([...a.store]);assert.equal((await b.request({...form,contents:'保留新的输入'})).code,499);
  assert.equal(b.calls.length,0);assert.deepEqual([...b.store],before);assert.equal(b.state.bundle.post.id,70);
});

test('a corrected original draft survives another lost publish response and cold recovery does not call it different input', async () => {
  const a=harness({rejectOnce:'POST /api/v1/community/posts/70/publish',loseResponse:'POST /api/v1/community/posts/70/publish'});
  assert.equal((await a.request(form)).code,400);const corrected={...form,contents:'保留修正后的草稿正文'};
  assert.equal((await a.request(corrected)).transportFailure,true);
  assert.equal([...a.store.values()][0].draftId,70);assert.equal([...a.store.values()][0].input.id,70);
  const b=harness({},a);const result=await b.request(corrected);
  assert.equal(result.code,200);assert.equal(result.recoveredPrevious,undefined);assert.equal(b.calls.length,1);assert.equal(b.calls[0].method,'GET');
  assert.equal(a.calls.filter(c=>c.method==='POST'&&c.url.endsWith('/posts')).length,1);assert.equal(b.state.bundle.post.body,corrected.contents);
});

test('known publish400 with guideline3→4 confirms a new persisted ACK key on the same original draft', async () => {
  const h=harness({rejectOnce:'POST /api/v1/community/posts/70/publish'});assert.equal((await h.request(form)).code,400);
  h.state.guidelineId=4;const result=await h.request({...form,contents:'遵循新规则修正的正文'});
  assert.equal(result.code,200);assert.equal(result.data.id,70);assert.equal(h.state.bundle.post.body,'遵循新规则修正的正文');
  const acks=h.calls.filter(c=>c.url.endsWith('/guidelines/ack'));
  assert.deepEqual(acks.map(c=>c.data.guidelineVersionId),[3,4]);assert.notEqual(acks[0].data.requestId,acks[1].data.requestId);
  assert.equal(h.state.ackByKey[acks[0].data.requestId],3);assert.equal(h.state.ackByKey[acks[1].data.requestId],4);
  assert.equal(h.calls.filter(c=>c.method==='POST'&&c.url.endsWith('/posts')).length,1);
});
test('unknown ACK recovery retains the exact original ACK key and guideline, even if a newer policy now rejects it', async () => {
  const a=harness({loseResponse:'POST /api/v1/community/guidelines/ack'});assert.equal((await a.request(form)).transportFailure,true);
  const original=copy(a.calls.find(c=>c.url.endsWith('/guidelines/ack')).data);a.state.guidelineId=4;
  const b=harness({},a);assert.equal((await b.request(form)).code,409);
  assert.equal(b.calls.length,1);assert.equal(b.calls[0].url,'/api/v1/community/guidelines/ack');assert.deepEqual(b.calls[0].data,original);
  assert.deepEqual([...b.store.values()][0].ackBody,original);assert.equal([...b.store.values()][0].status,'unknown');
});

test('confirmed ACK followed by GET failure is not a pending write and a subsequent PATCH400 can still be corrected', async () => {
  const settings={rejectOnce:'POST /api/v1/community/posts/70/publish'};const h=harness(settings);await h.request(form);
  settings.readFailsOnce=true;const corrected={...form,contents:'第一次修正文案'};
  assert.equal((await h.request(corrected)).transportFailure,true);assert.equal([...h.store.values()][0].status,'prepared');
  settings.rejectNext='PATCH /api/v1/community/posts/70';assert.equal((await h.request(corrected)).code,400);
  assert.equal([...h.store.values()][0].status,'rejected');assert.equal([...h.store.values()][0].draftId,70);
  assert.equal((await h.request({...form,contents:'再次修正文案'})).code,200);assert.equal(h.state.bundle.post.body,'再次修正文案');
  assert.equal(h.calls.filter(c=>c.method==='POST'&&c.url.endsWith('/posts')).length,1);
});
test('a first new ACK409 caused by active guideline change allows only a fresh explicit guideline confirmation', async () => {
  const h=harness({advanceGuidelineAfterRead:true});assert.equal((await h.request(form)).code,409);
  assert.equal(h.calls.some(c=>c.url.endsWith('/posts')),false);
  assert.equal((await h.request(form)).code,200);
  const acks=h.calls.filter(c=>c.url.endsWith('/guidelines/ack'));assert.deepEqual(acks.map(c=>c.data.guidelineVersionId),[3,4]);
  assert.notEqual(acks[0].data.requestId,acks[1].data.requestId);
});
test('a first explicit CREATE400 permits corrected input without treating a known rollback as an unknown draft lookup', async () => {
  const h=harness({rejectOnce:'POST /api/v1/community/posts',draftsPage:{items:[],hasMore:true}});assert.equal((await h.request(form)).code,400);
  assert.equal(h.state.bundle,null);assert.equal((await h.request({...form,contents:'创建前已修正文案'})).code,200);
  assert.equal(h.calls.some(c=>c.url.endsWith('/drafts')),false);
});

function ownerMediaDraft(h, media) {
  h.state.bundle=h.bundle({body:'原文',audience:'PRIVATE',commentPolicy:'EVERYONE',postType:'MOMENT',locationPrecision:'NONE',mediaIds:media.map(m=>m.id),references:[]});
  h.state.bundle.media=copy(media);
}
function readOwnerPost(h) {
  return new Promise(resolve=>h.client.request({url:'/api/creativesquare/info',data:{id:70},success:resolve,fail:resolve}));
}
test('an owner edit preserves the selected media ID when a fresh authorized read renews its URL', async () => {
  const h=harness();const oldUrl='https://owned.example/upload/photo.png?Expires=old';
  ownerMediaDraft(h,[{id:12,derived_object_key:oldUrl}]);
  assert.equal((await readOwnerPost(h)).code,200);
  h.state.bundle.media[0].derived_object_key='https://owned.example/upload/photo.png?Expires=new';
  const result=await h.request({id:70,contents:'只改正文',pics:oldUrl});
  assert.equal(result.code,200);
  assert.deepEqual(h.calls.find(c=>c.method==='PATCH').data.mediaIds,[12]);
  assert.equal(h.calls.some(c=>c.url.endsWith('/media/register')),false);
});
test('renewed image URLs do not reattach a removed selection or change the selected media order', async () => {
  const h=harness();const oldOne='https://owned.example/one.png?old',oldTwo='https://owned.example/two.png?old';
  ownerMediaDraft(h,[{id:12,derived_object_key:oldOne},{id:13,derived_object_key:oldTwo}]);
  await readOwnerPost(h);
  h.state.bundle.media.forEach(m=>{m.derived_object_key+='-renewed';});
  assert.equal((await h.request({id:70,contents:'保留第二张',pics:oldTwo})).code,200);
  assert.deepEqual(h.calls.find(c=>c.method==='PATCH').data.mediaIds,[13]);
  assert.equal(h.calls.some(c=>c.url.endsWith('/media/register')),false);
});
test('cold unknown edit recovery retains original media IDs and never PATCHes again after URL renewal', async () => {
  const a=harness({loseResponse:'PATCH /api/v1/community/posts/70'}),oldUrl='https://owned.example/image.png?old';
  ownerMediaDraft(a,[{id:12,derived_object_key:oldUrl}]);await readOwnerPost(a);
  a.state.bundle.media[0].derived_object_key='https://owned.example/image.png?fresh';
  const input={id:70,contents:'原编辑内容',pics:oldUrl};
  assert.equal((await a.request(input)).transportFailure,true);
  const original=copy(a.calls.find(c=>c.method==='PATCH').data);
  assert.deepEqual([...a.store.values()][0].mediaSelections,{[oldUrl]:12});
  a.state.bundle.media[0].derived_object_key='https://owned.example/image.png?newer';
  const b=harness({},a);assert.equal((await b.request(input)).code,200);
  assert.deepEqual(b.calls.map(c=>c.method),['GET']);
  assert.deepEqual(original.mediaIds,[12]);assert.equal(b.store.size,0);
});
test('a cached media ID removed by the authoritative read fails closed without registration or PATCH', async () => {
  const h=harness(),oldUrl='https://owned.example/deleted.png?old';
  ownerMediaDraft(h,[{id:12,derived_object_key:oldUrl}]);await readOwnerPost(h);
  h.state.bundle.media=[];
  const result=await h.request({id:70,contents:'保留旧图的修改',pics:oldUrl});
  assert.equal(result.code,409);assert.match(result.msg,/原图片已变化/);
  assert.equal(h.calls.some(c=>c.method==='PATCH'||c.url.endsWith('/media/register')),false);
});
test('an old URL with no known media ID cannot be guessed into a renewed attachment', async () => {
  const h=harness();ownerMediaDraft(h,[{id:12,derived_object_key:'https://owned.example/photo.png?new'}]);
  const result=await h.request({id:70,contents:'不能猜测归属',pics:'https://owned.example/photo.png?old'});
  assert.equal(result.code,409);
  assert.equal(h.calls.some(c=>c.method==='PATCH'||c.url.endsWith('/media/register')),false);
});
test('changing accounts cannot use the previous actor media selections', async () => {
  const h=harness(),oldUrl='https://owned.example/private.png?old';
  ownerMediaDraft(h,[{id:12,derived_object_key:oldUrl}]);await readOwnerPost(h);h.owner('990015');
  assert.notEqual((await h.request({id:70,contents:'换号不能编辑旧图',pics:oldUrl})).code,200);
  assert.equal(h.calls.some(c=>c.method==='PATCH'||c.url.endsWith('/media/register')),false);
});
for (const stable of [true,false]) test('unknown media registration reuses exact '+(stable?'canonical key':'legacy URL')+' receipt wire after cold recovery', async () => {
  const a=harness({loseResponse:'POST /api/v1/community/media/register'}),url='https://owned.example/upload/image.png?original';
  const receipt={url,uploadReceipt:'original-owner-bound-receipt',mimeType:'image/png',byteSize:106};
  if(stable)receipt.objectKey='upload/0123456789abcdef0123456789abcdef.png';
  a.client.captureUpload(receipt);const input={...form,pics:url};
  assert.equal((await a.request(input)).transportFailure,true);
  const original=copy(a.calls.find(c=>c.url.endsWith('/media/register')).data);
  assert.equal(original.objectKey,stable?receipt.objectKey:url);
  const b=harness({},a);assert.equal((await b.request(input)).code,200);
  const replay=b.calls.find(c=>c.url.endsWith('/media/register'));
  assert.deepEqual(replay.data,original);assert.deepEqual(b.calls.find(c=>c.url.endsWith('/posts')&&c.method==='POST').data.mediaIds,[12]);
  assert.equal(b.calls.some(c=>/uploadOSS/.test(c.url)),false);
});
