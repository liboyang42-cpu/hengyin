const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const root = path.resolve(__dirname, '../..');
function setup() {
  let def, user = 10;
  const requests = [], events = [];
  const app = { getUserID: () => user, getImgUrl: s => s, sendRequest: r => requests.push(r) };
  vm.runInNewContext(fs.readFileSync(path.join(root, 'components/cy/coop-invite-sheet/index.js'), 'utf8'), {
    Component: c => { def = c; }, getApp: () => app,
    require: name => name.includes('subscribe') ? { request: () => Promise.resolve() } : () => {},
  });
  const c = { data: { ...def.data, topicId: null, topicName: '路线', targetId: 8, targetName: '俱乐部', inviteType: 1, operationScope: 'MERCHANT' }, setData(d) { Object.assign(this.data, d); }, triggerEvent: (name, detail) => events.push({ name, detail }) };
  Object.assign(c, def.methods);
  def.observers.show.call(c, true);
  return { c, def, requests, events, switchUser: () => { user = 20; } };
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
  assert.equal(s.c.data.selectedChapterId, 11); s.c.send(); s.requests.shift().success({ code: 200 });
  assert.equal(s.events[0].detail.chapterId, 11);
});
test('商家使用章节承接接口，失败保留输入，账号切换响应不污染', () => {
  const s=setup(); topics(s); pick(s,1); chapter(s); s.c.data.inviteType=0; s.c.data.message='保留我';
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
 const markup=fs.readFileSync(path.join(root,'components/cy/coop-invite-sheet/index.wxml'),'utf8');
 assert.equal((markup.match(/<scroll-view/g)||[]).length,2); assert.ok(!markup.includes('onMode'));
});
test('回程只匹配原主题且已打开管理弹窗的导览台，首页不被带走', () => {
  const navigations = []; let reads = 0;
  const host = { route: 'pages/topic/merchantinfo/merchantinfo', data: { topicId: 99, clubSheet: { show: true } }, loadProjectHome: () => reads++ };
  let pages = [host, { route: 'pages/merchant/relation/index' }, { route: 'pages/club/detail/index' }];
  const sandbox = { module: { exports: {} }, getCurrentPages: () => pages, wx: { navigateBack: v => navigations.push(v) } };
  vm.runInNewContext(fs.readFileSync(path.join(root,'utils/coop-invite-return.js'),'utf8'), sandbox);
  const finish = sandbox.module.exports.returnToCoopOrigin;
  assert.equal(finish(98), false);
  assert.equal(finish(99), true);
  assert.equal(navigations[0].delta, 2); assert.equal(reads, 1);
  pages = [{ route: 'pages/index/index' }, { route: 'pages/club/detail/index' }];
  assert.equal(finish(99), false);
});
