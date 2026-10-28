const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
const components = [];
test.afterEach(() => { components.splice(0).forEach(c => c.stopRecovery()); });
function setup(options = {}) {
  let def, user = 10;
  const requests = [], events = [], storage = options.storage || new Map();
  const wx = { getStorageSync(k) { if (options.storageFailure === 'read') throw Error('storage'); return storage.get(k) || ''; },
    setStorageSync(k,v) { if (options.storageFailure === 'write') throw Error('storage'); storage.set(k, JSON.parse(JSON.stringify(v))); }, removeStorageSync(k) { if (options.removeFailure) throw Error('remove failed'); storage.delete(k); } };
  const realRequire = require('node:module').createRequire(path.join(root, 'subpackageMember/components/cy/coop-invite-sheet/index.js'));
  const app = { getAuthorization: () => options.authorization, getUserID: () => user, getImgUrl: s => s, sendRequest: r => requests.push(r) };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'subpackageMember/components/cy/coop-invite-sheet/index.js'), 'utf8'), {
    Component: c => { def = c; }, getApp: () => app, wx,
    require: name => name.includes('subscribe') ? { request: () => Promise.resolve() } : name.includes('toast') ? () => {} : realRequire(name),
  });
  const c = { data: { ...def.data, topicId: null, topicName: '路线', targetId: 8, targetName: '俱乐部', inviteType: options.inviteType == null ? 1 : options.inviteType, operationScope: 'MERCHANT' }, setData(d) { Object.assign(this.data, d); }, triggerEvent: (name, detail) => events.push({ name, detail }) };
  Object.assign(c, def.methods);
  def.observers.show.call(c, true);
  components.push(c);
  return { c, def, requests, events, storage, options, switchUser: () => { user = 20; } };
}
function topics(s) {
  s.requests.shift().success({ code: 200, data: { rows: [{ id: 1, name: '自己发布 A' }, { id: 2, name: '自己发布 B' }], total: 2 } });
}
function pick(s, id) { s.c.pickTopic({ currentTarget: { dataset: { id } } }); }
function chapter(s, id=11) {
  s.requests.shift().success({ code: 200, data: { chapters: [{ id, name: '学习' }] } });
  s.c.pickChapter({ currentTarget: { dataset: { id } } });
}
test('首页只取自己发布的主题；切换主题清章，乱序响应不串题，保留手写邀约语', () => {
  const s = setup(); assert.equal(s.requests[0].data.is_my, 1); topics(s);
  pick(s, 1); const old = s.requests.shift(); s.c.data.message = '手写内容'; pick(s, 2);
  old.success({ code: 200, data: { chapters: [{ id: 11, name: '旧章' }] } });
  assert.equal(s.c.data.chapters.length, 0); assert.equal(s.c.data.selectedChapterId, null);
  chapter(s, 22); assert.equal(s.c.data.selectedChapterId, 22); assert.equal(s.c.data.message, '手写内容');
});
test('俱乐部沿用主题合同，章节仅进入邀约展示；不发送合作方式或费用', () => {
  const s = setup(); topics(s); pick(s, 1); chapter(s); s.c.send(); s.c.send();
  assert.equal(s.requests.length, 1); const r=s.requests.shift(), body=JSON.parse(r.data);
  assert.equal(r.url, '/api/coop/invite'); assert.equal(body.topicId, 1);
  assert.ok(body.message.includes('学习')); assert.equal(body.chapterId, undefined);
  assert.equal(body.shareMode, undefined); assert.equal(body.fixedFee, undefined);
  r.success({ code: 400, msg: '不可邀约' }); assert.equal(s.events.length, 0);
  assert.equal(s.c.data.selectedChapterId, 11); s.c.send(); const retry = s.requests.shift(); retry.success({ code: 200, data: { ...receiptFor(retry), replayed: false } });
  assert.equal(s.events[0].detail.chapterId, 11);
});
test('商家使用章节承接接口，失败保留输入，账号切换响应不污染', () => {
  const s=setup({ inviteType: 0 }); topics(s); pick(s,1); chapter(s); s.c.data.message='保留我';
  s.c.send(); const r=s.requests.shift(); assert.equal(r.url, '/api/merchant/chapter-application/invite');
  const body=JSON.parse(r.data); assert.equal(body.chapterId,11); assert.equal(body.merchantMemberId,8);
  r.success({code:400,msg:'章节未开放'}); assert.equal(s.c.data.message,'保留我');
  s.c.send(); s.switchUser(); s.requests.shift().success({code:200}); assert.equal(s.events.length,0);
});
test('固定章节验证存在后发送，未选择或加载失败不能发邀约', () => {
  const s=setup(); s.c.send(); assert.equal(s.requests.length,1); topics(s);
  s.c.data.topicId=1; s.c.data.chapterId=11; s.def.observers.show.call(s.c,true);
  assert.equal(s.requests[0].url,'/api/topic/invite-chapters'); chapter(s); assert.equal(s.c.data.selectedChapterId,11);
  s.c.send(); assert.equal(s.requests.length,1);
});
test('关闭弹窗后旧响应不回填；两排横滑无条款选择器', () => {
 const s=setup(); s.def.observers.show.call(s.c,false); s.requests[0].success({code:200,data:{rows:[{id:1,name:'旧主题'}]}});
 assert.equal(s.c.data.topics.length,0);
 const markup=fs.readFileSync(path.join(root,'subpackageMember/components/cy/coop-invite-sheet/index.wxml'),'utf8');
 assert.equal((markup.match(/<scroll-view/g)||[]).length,2); assert.ok(!markup.includes('onMode'));
});
test('回程只匹配原主题且已打开管理弹窗的导览台，首页不被带走', () => {
  const navigations = []; let reads = 0;
  const host = { route: 'pages/topic/merchantinfo/merchantinfo', data: { topicId: 99, clubSheet: { show: true } }, loadProjectHome: () => reads++ };
  let pages = [host, { route: 'pages/merchant/relation/index' }, { route: 'pages/club/detail/index' }];
  const sandbox = { module: { exports: {} }, getCurrentPages: () => pages, wx: { navigateBack: v => navigations.push(v) } };
  vm.runInNewContext(fs.readFileSync(path.join(root,'subpackageMember/utils/coop-invite-return.js'),'utf8'), sandbox);
  const finish = sandbox.module.exports.returnToCoopOrigin;
  assert.equal(finish(98), false);
  assert.equal(finish(99), true);
  assert.equal(navigations[0].delta, 2); assert.equal(reads, 1);
  pages = [{ route: 'pages/index/index' }, { route: 'pages/club/detail/index' }];
  assert.equal(finish(99), false);
});

test('邀约返回不命中已卸载或属于旧账号的同主题页面',()=>{
 const host={route:'pages/topic/merchantinfo/merchantinfo',_projectMemberId:'10',data:{topicId:99,clubSheet:{show:true}},loadProjectHome:()=>reads++};
 let actor=20,reads=0;const navigations=[];
 const sandbox={module:{exports:{}},getApp:()=>({getUserID:()=>actor}),getCurrentPages:()=>[host,{route:'pages/club/detail/index'}],wx:{navigateBack:x=>navigations.push(x)}};
 vm.runInNewContext(fs.readFileSync(path.join(root,'subpackageMember/utils/coop-invite-return.js'),'utf8'),sandbox);const finish=sandbox.module.exports.returnToCoopOrigin;
 assert.equal(finish(99),false);assert.equal(reads,0);assert.equal(navigations.length,0);
 actor=10;host._disposed=true;assert.equal(finish(99),false);
 host._disposed=false;assert.equal(finish(99),true);assert.equal(reads,1);assert.equal(navigations.length,1);
});

test('拍板 2026-10-22:邀约主题列表滤掉已结束/已下架/未过审的主题,整页被滤空时自动翻下一页', () => {
  const s = setup();
  const pad = n => String(n).padStart(2, '0');
  const d = new Date();
  const today = d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate());
  const first = s.requests.shift();
  assert.equal(first.data.is_my, 1);
  first.success({ code: 200, data: { total: 40, rows: [
    { id: 1, name: '已结束', endDate: '2020-01-01', status: 1, userStatus: 1 },
    { id: 2, name: '已下架', endDate: '2999-01-01', status: 1, userStatus: 0 },
    { id: 3, name: '未过审', status: 0, userStatus: 1 },
  ] } });
  assert.equal(s.c.data.topics.length, 0);
  const second = s.requests.shift();
  assert.equal(second.data.pageNum, 2, '整页过期时接着拉下一页');
  second.success({ code: 200, data: { total: 40, rows: [
    { id: 4, name: '今天结束', endDate: today + ' 23:59:59', status: 1, userStatus: 1 },
    { id: 5, name: '在售', endDate: '2999-12-31', status: 1, userStatus: 1 },
    { id: 6, name: '旧回包无字段' },
  ] } });
  assert.deepEqual([...s.c.data.topics.map(t => t.id)], [4, 5, 6]);
});

function ready(options) { const s = setup(options); topics(s); pick(s, 1); chapter(s); return s; }
function receiptFor(request, patch = {}) {
  const p = JSON.parse(request.data);
  return { state: 'CREATED', requestId: p.requestId, inviteId: '9223372036854775807',
    inviteType: 1, topicId: String(p.topicId), toType: 'club', toId: String(p.toId), scope: '', ...patch };
}
test('W4 sheet持久号早于POST，冻结输入；超时后精确查询仅一个POST，正向回执解原锁', () => {
  const s = ready(); s.c.data.message = '原消息'; s.c.send(); const post = s.requests.shift();
  const p = JSON.parse(post.data), stored = [...s.storage.values()][0];
  assert.equal(p.requestId, stored.attemptId); assert.equal(stored.requestIdentityVersion, 1);
  s.c.data.message = '改消息'; assert.match(JSON.parse(post.data).message, /原消息/);
  post.fail({}); assert.equal(s.c.data.unknown, true); s.c.send(); assert.equal(s.requests.length, 0);
  assert.doesNotMatch(JSON.stringify([...s.storage]), /原消息|改消息|payload|message|fixedFee|scope/);
  s.c.queryOriginalInvite(); const get = s.requests.shift();
  assert.equal(get.method, 'GET'); assert.equal(get.url, '/api/coop/invite/request'); assert.equal(get.data.requestId, p.requestId);
  get.success({ code: 200, data: receiptFor(post) });
  assert.equal(s.storage.size, 0); assert.equal(s.c.data.unknown, false); assert.equal(s.c._sent, true);
  get.fail({}); post.fail({}); s.c.send(); assert.equal(s.requests.length, 0); assert.equal(s.events.length, 0);
});
test('W8 sheet精确回读NOT_FOUND后沿用原请求号重发一次，成功即解原锁', () => {
  const s = ready(); s.c.send(); const post = s.requests.shift(); post.fail({});
  const original = JSON.parse(post.data).requestId;
  s.c.queryOriginalInvite(); s.requests.shift().success({ code: 200, data: { state: 'NOT_FOUND', requestId: original } });
  assert.equal(s.c.data.unknown, false, 'NOT_FOUND 不再永久禁用发送');
  assert.equal(s.storage.size, 1, 'NOT_FOUND 不清本机原请求');
  s.c.send(); const resend = s.requests.shift();
  assert.equal(resend.method, 'POST'); assert.equal(JSON.parse(resend.data).requestId, original, '沿用原请求号');
  assert.equal(s.storage.size, 1);
  resend.fail({}); assert.equal(s.c.data.unknown, true); assert.equal(s.storage.size, 1);
  s.c.send(); assert.equal(s.requests.length, 0, '重发结果未知仍须先回读');
  s.c.queryOriginalInvite(); s.requests.shift().success({ code: 200, data: { state: 'NOT_FOUND', requestId: original } });
  s.c.send(); const again = s.requests.shift(); assert.equal(JSON.parse(again.data).requestId, original);
  again.success({ code: 200, data: { ...receiptFor(again), replayed: false } });
  assert.equal(s.storage.size, 0); assert.equal(s.c._sent, true); assert.equal(s.events.at(-1).name, 'sent');
  s.c.send(); assert.equal(s.requests.length, 0);
});
test('W8 sheet精确回读UNAVAILABLE视为已受理：展示结果并清本机原锁，不再重发', () => {
  const s = ready(); s.c.send(); const post = s.requests.shift(); post.fail({});
  s.c.queryOriginalInvite();
  s.requests.shift().success({ code: 200, data: { state: 'UNAVAILABLE', requestId: JSON.parse(post.data).requestId } });
  assert.equal(s.storage.size, 0); assert.equal(s.c.data.unknown, false); assert.equal(s.c._sent, true);
  assert.match(s.c.data.error, /已受理/);
  s.c.send(); assert.equal(s.requests.length, 0);
});
test('W8 sheet他人请求号的NOT_FOUND/UNAVAILABLE不解本机原锁', () => {
  const s = ready(); s.c.send(); s.requests.shift().fail({});
  s.c.discoverOriginalInvites();
  s.requests.shift().success({ code: 200, data: { rows: [{ state: 'UNAVAILABLE', requestId: 'other' }], nextCursor: null, hasMore: false } });
  assert.equal(s.storage.size, 1); assert.equal(s.c.data.unknown, true); assert.ok(!s.c._resumable);
  s.c.send(); assert.equal(s.requests.length, 0);
});
for (const ending of ['wrongkey', 'wrongtopic', 'wrongtarget', 'unsafe', '403', 'network']) {
 test('W4 sheet负向/错配/异常查询继续锁住 ' + ending, () => {
  const s = ready(); s.c.send(); const post = s.requests.shift(); post.fail({}); s.c.queryOriginalInvite();
  const get = s.requests.shift(), before = JSON.stringify([...s.storage]);
  let receipt = receiptFor(post);
  if (ending === 'wrongkey') receipt.requestId = 'wrong';
  if (ending === 'wrongtopic') receipt.topicId = '2';
  if (ending === 'wrongtarget') receipt.toId = '9';
  if (ending === 'unsafe') receipt.inviteId = 9007199254740992;
  if (ending === '403') get.successStatusAbnormal({ code: 403 }, 403);
  else if (ending === 'network') get.fail({});
  else get.success({ code: 200, data: receipt });
  assert.equal(JSON.stringify([...s.storage]), before); assert.equal(s.c.data.unknown, true);
  s.c.send(); assert.equal(s.requests.length, 0); assert.equal(s.events.length, 0);
 });
}
test('W4 sheet关闭重开或新实例仍可精确查询；旧无绑定请求不假冒服务端原请求', () => {
 const storage = new Map(), first = ready({ storage }); first.c.send(); const post = first.requests.shift(); post.fail({});
 first.def.observers.show.call(first.c, false);
 const second = ready({ storage }); second.c.queryOriginalInvite(); const get = second.requests.shift();
 assert.equal(get.data.requestId, JSON.parse(post.data).requestId);
 get.success({ code: 200, data: receiptFor(post) }); assert.equal(storage.size, 0);
 const legacy = ready({ storage }); legacy.c.send(); legacy.requests.shift().fail({});
 for (const row of storage.values()) delete row.requestIdentityVersion;
 legacy.c.queryOriginalInvite(); assert.equal(legacy.requests.length, 0); assert.match(legacy.c.data.receiptError, /旧.*人工核对/);
 legacy.c.send(); assert.equal(legacy.requests.length, 0); assert.equal(storage.size, 1);
});
for (const change of ['account', 'target', 'topic', 'scope', 'close']) test('W4 sheet '+change+' 旧GET不清原锁或污染当前面板', () => {
 const s = ready(); s.c.send(); const post = s.requests.shift(); post.fail({}); s.c.queryOriginalInvite(); const get = s.requests.shift();
 const stored = JSON.stringify([...s.storage]);
 if (change === 'account') s.switchUser();
 if (change === 'target') s.c.data.targetId = 9;
 if (change === 'topic') s.c.data.selectedId = 2;
 if (change === 'scope') s.c.data.operationScope = '';
 if (change === 'close') s.def.observers.show.call(s.c, false);
 const data = JSON.stringify(s.c.data);
 get.success({ code: 200, data: receiptFor(post) });
 assert.equal(JSON.stringify([...s.storage]), stored); assert.equal(JSON.stringify(s.c.data), data);
});
for (const fault of ['read', 'write']) test('W4 sheet存储'+fault+'故障未提交，修复后仅主动提交', () => {
 const options = { storageFailure: fault }, s = ready(options); s.c.send();
 assert.equal(s.requests.length, 0); assert.equal(s.c.data.unknown, false); assert.match(s.c.data.error, /本次未提交/);
 options.storageFailure = ''; assert.equal(s.requests.length, 0); s.c.send(); assert.equal(s.requests.length, 1);
 s.requests.shift().fail({}); assert.equal(s.c.data.unknown, true);
});
test('W4 sheet同key409与重复回调不清待办；不同主题请求相互隔离', () => {
 const s = ready(); s.c.send(); const post = s.requests.shift();
 post.success({ code: 409, msg: 'COOP_REQUEST_CONFLICT' });
 post.success({ code: 200, data: { ...receiptFor(post), replayed: false } });
 assert.equal(s.storage.size, 1); assert.equal(s.c.data.unknown, true);
 pick(s, 2); chapter(s, 22); s.c.send(); const other = s.requests.shift(); assert.notEqual(JSON.parse(other.data).requestId, JSON.parse(post.data).requestId);
 other.success({ code: 400, msg: '明确拒绝' }); assert.equal(s.storage.size, 1); assert.equal(s.c.data.unknown, false);
});
test('W4 sheet跨设备只读查找用显式分页，非本对象回执不改发送态', () => {
 const s = ready(); s.c.discoverOriginalInvites(); const first = s.requests.shift();
 first.success({ code: 200, data: { rows: [{ state: 'UNAVAILABLE', requestId: 'unavailable' }], nextCursor: '99', hasMore: true } });
 assert.equal(s.requests.length, 0); assert.equal(s.c._sent, false);
 s.c.moreOriginalInvites(); const more = s.requests.shift(); assert.equal(more.data.before, '99');
 more.success({ code: 200, data: { rows: [{ state: 'CREATED', requestId: 'other-target', inviteId: '80', inviteType: 1,
  topicId: '1', toType: 'club', toId: '9', scope: '' }], nextCursor: '80', hasMore: true } });
 assert.equal(s.c._sent, false); s.c.moreOriginalInvites(); const last = s.requests.shift();
 last.success({ code: 200, data: { rows: [{ state: 'CREATED', requestId: 'current-target', inviteId: '70', inviteType: 1,
  topicId: '1', toType: 'club', toId: '8', scope: '' }], nextCursor: null, hasMore: false } });
 assert.equal(s.c._sent, true); s.c.send(); assert.equal(s.requests.length, 0); assert.equal(s.storage.size, 0);
});
test('W4 sheet商家章节接口不附coop requestId、不查coop回执', () => {
 const s = ready({ inviteType: 0 }); s.c.send(); const post = s.requests.shift();
 assert.equal(post.url, '/api/merchant/chapter-application/invite'); assert.equal(JSON.parse(post.data).requestId, undefined);
 post.fail({}); s.c.queryOriginalInvite(); s.c.discoverOriginalInvites(); s.c.moreOriginalInvites();
 assert.equal(s.requests.length, 0); assert.equal(s.storage.size, 0);
});

test('W4 sheet正向回读删除失败保留原锁与明确待确认提示', () => {
 const options = {}, s = ready(options); s.c.send(); const post = s.requests.shift(); post.fail({}); options.removeFailure = true;
 s.c.queryOriginalInvite(); s.requests.shift().success({ code: 200, data: receiptFor(post) });
 assert.equal(s.storage.size, 1); assert.equal(s.c.data.unknown, true); assert.match(s.c.data.receiptError, /仍待确认/);
 s.c.send(); assert.equal(s.requests.length, 0);
});
test('W4 sheet查询途中token切换，旧回执不结算', () => {
 const options = { authorization: 'a' }, s = ready(options); s.c.send(); const post = s.requests.shift(); post.fail({});
 s.c.queryOriginalInvite(); const get = s.requests.shift(); options.authorization = 'b'; const before = JSON.stringify(s.c.data);
 get.success({ code: 200, data: receiptFor(post) }); assert.equal(s.storage.size, 1); assert.equal(JSON.stringify(s.c.data), before);
});
