/**
 * H050/H051 · 帖文「···」操作接线契约(square 详情 + 广场列表)。
 *
 * 组件(chengyinhub-xcx/components/cy/post-actions)与内部危险确认闸早已完工并有
 * post-actions-sheet-contract 钉住,但全仓零调用 —— 用户发错帖/想删帖没有入口。
 *
 * 本契约钉两件事,且**渲染层与行为层都钉**:
 *   ① 渲染层:详情与列表都挂上 <cy-post-actions>(前者还有编辑用的 cy-post-compose);
 *   ② 行为层:delete 发 /api/creativesquare/delete、edit 以编辑态打开新建面板并带原帖 id、
 *      report 仍走既有举报确认。
 * 负控:删掉渲染入口 / 变异请求 URL / 掐掉分发分支,对应断言必须真红。
 */
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');

const ROOT = path.resolve(__dirname, '../..');
const read = (rel) => fs.readFileSync(path.join(ROOT, rel), 'utf8');

const POST_ACTIONS_TAG = /<cy-post-actions id="post-actions"[\s\S]{0,200}?show="\{\{postActionShow\}\}"[\s\S]{0,200}?bind:select="onPostAction"[\s\S]{0,200}?bind:close="closePostActions"\s*\/>/;

function assertDetailWired(wxml) {
  assert.match(wxml, POST_ACTIONS_TAG, '详情必须挂三合一操作弹层,show/select/close 三根线齐全');
  assert.match(wxml, /<cy-post-actions id="post-actions"[^>]*owner="\{\{isMine\}\}"/,
    'owner 必须来自 isMine:编辑/删除只给作者');
  // A-09:溢出入口不再按「关联帖/作者」设条件 —— 非本人纯文本帖也要能在这里举报
  // (组件 owner=false 时只渲染举报行,不会露出编辑/删除;见 post-actions/index.wxml)。
  assert.match(wxml, /<view class="ph-more" catchtap="openPostActions"/,
    '溢出入口必须对所有人可达,否则非本人纯文本帖在详情页没有举报路');
  assert.doesNotMatch(wxml, /ph-more"[^>]*wx:if/,
    '溢出入口不许再挂 hasPlayCover/isMine 条件(A-09 回退会重现「详情页举报不了」)');
  assert.match(wxml, /<cy-post-compose[^>]*edit-post="\{\{composeEdit\}\}"/,
    '编辑要复用新建面板的编辑态(edit-post),不能再造一个编辑器');
  assert.doesNotMatch(wxml, /reportSquareFromActions/,
    '旧的「只有举报」自绘弹层必须退役(三合一组件替代它)');
}

function assertListWired(wxml) {
  assert.match(wxml, POST_ACTIONS_TAG, '广场列表必须挂三合一操作弹层,三根线齐全');
  assert.match(wxml, /<cy-post-actions id="post-actions"[^>]*owner="\{\{currentActionOwner\}\}"/,
    'owner 必须来自当前行作者判定');
  assert.match(wxml, /<cy-post-compose[^>]*edit-post="\{\{composeEdit\}\}"/,
    '列表页的编辑同样走 edit-post');
  assert.doesNotMatch(wxml, /bmShow/,
    '旧的 index 版举报弹层必须整体退役(bmShow/bmClose 不许留半截)');
}

test('渲染层:详情与列表都接上 cy-post-actions(编辑走 cy-post-compose 编辑态)', () => {
  const detailWxml = read('pages/square/detail/index.wxml');
  const listWxml = read('pages/square/list/index.wxml');
  const detailJson = JSON.parse(read('pages/square/detail/index.json'));
  const listJson = JSON.parse(read('pages/square/list/index.json'));

  assertDetailWired(detailWxml);
  assertListWired(listWxml);

  assert.equal(detailJson.usingComponents['cy-post-actions'], '/pages/square/components/cy/post-actions/index');
  assert.equal(detailJson.usingComponents['cy-post-compose'], '/pages/square/components/cy/post-compose/index');
  assert.equal(listJson.usingComponents['cy-post-actions'], '/pages/square/components/cy/post-actions/index');

  // 负控:把渲染入口从 wxml 里删掉,同一条断言必须真红
  const stripped = detailWxml.replace(/<cy-post-actions[\s\S]*?\/>/, '');
  assert.notEqual(stripped, detailWxml, '负控锚点失效:详情 cy-post-actions 未命中');
  assert.throws(() => assertDetailWired(stripped), assert.AssertionError,
    '删掉渲染入口后必须变红 —— 只断言 data 的契约会放过这种回归');

  const strippedList = listWxml.replace(/<cy-post-actions[\s\S]*?\/>/, '');
  assert.notEqual(strippedList, listWxml, '负控锚点失效:列表 cy-post-actions 未命中');
  assert.throws(() => assertListWired(strippedList), assert.AssertionError);
});


const {load,post,tick}=require('../helpers/community-square-vm');
test('详情:作者删除按canonical DELETE+真实version,成功不可用;负控换typed方法真红',async()=>{const h=load('detail');await h.ready();h.page.openPostActions();h.page.onPostAction({detail:{action:'delete',id:'77'}});await tick();const write=h.asked('/api/v1/community/posts/77').at(-1);assert.equal(write.method,'DELETE');assert.equal(write.data.expectedVersion,3);assert.match(write.data.requestId,/^square-/);await h.answer(write.url,null);assert.equal(h.page.data.detailState,'empty');assert.equal(h.page.data.postActionShow,false);const source=read('pages/square/detail/index.js'),broken=source.replace('this.communityClient().deletePost({','this.communityClient().detail({');assert.notEqual(broken,source);const bad=load('detail',{source:broken});await bad.ready();bad.page.openPostActions();bad.page.onPostAction({detail:{action:'delete',id:'77'}});await tick();assert.throws(()=>assert.equal(bad.asked('/api/v1/community/posts/77').at(-1).method,'DELETE'),assert.AssertionError)});
test('详情:编辑预填真实id/正文/关联/版本;举报走确认;删除分发负控真红',async()=>{const h=load('detail');const b=post();b.references=[{referenceType:'TOPIC',referenceId:9,snapshotJson:'{"title":"关联"}'}];await h.ready(b);h.page.openPostActions();h.page.onPostAction({detail:{action:'edit',id:'77'}});assert.equal(h.page.data.composeShow,true);assert.equal(h.page.data.composeEdit.id,'77');assert.equal(h.page.data.composeEdit.contents,'原文');assert.equal(h.page.data.composeEdit.dataId,9);assert.equal(h.page.data.composeEdit.dataType,2);assert.equal(h.page.data.composeEdit.version,3);h.page.onPostAction({detail:{action:'report',id:h.page.data.currentActionId || h.page.data.id}});assert.equal(h.modals.length,1);assert.equal(h.page.data.postActionShow,false);const source=read('pages/square/detail/index.js'),broken=source.replace("if (action === 'delete') { this.deleteSquarePost(); return; }",'');assert.notEqual(broken,source);const bad=load('detail',{source:broken});await bad.ready();bad.page.openPostActions();bad.page.onPostAction({detail:{action:'delete',id:'77'}});await tick();assert.throws(()=>assert.ok(bad.requests.some(r=>r.method==='DELETE')),assert.AssertionError)});
test('列表:more由真实viewer能力定位,编辑预填/关闭,按ID删除,举报taxonomy全链',async()=>{const h=load('list');h.page.data.list=[h.page.postView(post(11)),h.page.postView(post(12,2))];h.page.data.userId=1;h.page.showAction({detail:{index:1}});assert.equal(h.page.data.currentActionId,'12');assert.equal(h.page.data.currentActionOwner,false);h.page.closePostActions();h.page.showAction({detail:{index:0}});assert.equal(h.page.data.currentActionOwner,true);h.page.onPostAction({detail:{action:'edit',id:h.page.data.currentActionId}});assert.equal(h.page.data.composeEdit.id,'11');assert.equal(h.page.data.composeEdit.contents,'原文');h.page.onComposeClose();assert.equal(h.page.data.composeShow,false);assert.equal(h.page.data.composeEdit,null);h.page.showAction({detail:{index:0}});h.page.onPostAction({detail:{action:'delete',id:h.page.data.currentActionId}});await tick();assert.equal(h.asked('/api/v1/community/posts/11')[0].method,'DELETE');await h.answer('/api/v1/community/posts/11',null);assert.deepEqual(h.page.data.list.map(r=>r.id),[12]);assert.equal(h.page.data.postActionShow,false);h.page.showAction({detail:{index:0}});h.page.onPostAction({detail:{action:'report',id:h.page.data.currentActionId || h.page.data.id}});assert.equal(h.modals.length,1);h.modals[0].success({confirm:true});await tick();await h.answer('/api/v1/community/capabilities',{reporting:{policyVersion:'synthetic',reasons:[{code:'SERVER_REASON',label:'服务端原因'}]}});h.page.onReportReasonSelect({detail:{index:0}});await tick();const report=h.asked('/api/v1/community/reports')[0];assert.equal(report.method,'POST');assert.equal(report.data.targetId,12);assert.equal(report.data.reasonCode,'SERVER_REASON');assert.equal(report.data.policyVersion,'synthetic')});
test('A-09:非本人纯文本帖仍可举报,组件owner=false只显示举报行',async()=>{const h=load('detail');await h.ready(post(77,2));assert.equal(h.page.data.isMine,false);h.page.openPostActions();assert.equal(h.page.data.postActionShow,true);h.page.onPostAction({detail:{action:'report',id:h.page.data.currentActionId || h.page.data.id}});assert.equal(h.modals.length,1);assert.equal(h.page.data.composeShow,false);assert.match(read('pages/square/detail/index.wxml'),/<cy-post-actions[^>]*owner="\{\{isMine\}\}"/)});
test('列表:编辑必须带真实编辑态,关闭后清理不泄漏到下次打开',()=>{const h=load('list');h.page.data.list=[h.page.postView(post(11))];h.page.showAction({detail:{index:0}});h.page.onPostAction({detail:{action:'edit',id:h.page.data.currentActionId}});assert.equal(h.page.data.composeShow,true);assert.equal(h.page.data.composeEdit.id,'11');assert.equal(h.page.data.composeEdit.version,3);h.page.onComposeClose();assert.equal(h.page.data.composeShow,false);assert.equal(h.page.data.composeEdit,null)});

// Exercise the real component events against synchronous parent/prop updates, not just handlers.
const vm = require('node:vm');
const { createRequire } = require('node:module');
function mountActions(h, route) {
  function component(rel) {
    const file=path.join(ROOT,rel);let definition;
    vm.runInNewContext(read(rel),{Component:value=>{definition=value},require:createRequire(file),console},{filename:file});
    return Object.assign({},definition.methods,{data:JSON.parse(JSON.stringify(definition.data||{})),setData(patch){Object.assign(this.data,patch)}});
  }
  const actions=component('pages/square/components/cy/post-actions/index.js');
  const danger=component('components/cy/danger-confirm/index.js');
  const events=[];
  const sync=()=>Object.assign(actions.data,route==='list'?{postId:h.page.data.currentActionId,summary:h.page.data.currentActionSummary,owner:h.page.data.currentActionOwner}:{postId:String(h.page.data.id||''),summary:h.page.data.info?.contents||'',owner:h.page.data.isMine});
  const setData=h.page.setData;h.page.setData=function(patch,callback){setData.call(this,patch,callback);sync()};
  actions.selectComponent=()=>danger;
  actions.triggerEvent=(name,detail)=>{events.push({name,detail});if(name==='close')h.page.closePostActions();if(name==='select')h.page.onPostAction({detail})};
  danger.triggerEvent=(name,detail)=>{if(name==='confirm')actions.onDeleteConfirmed({detail})};
  h.page.selectComponent=()=>actions;sync();
  return{actions,danger,events,open(index=0){if(route==='list')h.page.showAction({detail:{index}});else h.page.openPostActions();sync()}};
}
test('真实组件链:列表编辑在close同步清父状态和props后仍打开原帖',()=>{
 const h=load('list');h.page.data.list=[h.page.postView(post(11)),h.page.postView(post(12))];const c=mountActions(h,'list');c.open();c.actions.onEdit();
 assert.equal(h.page.data.currentActionId,'');assert.equal(c.actions.data.postId,'');assert.equal(h.page.data.postActionShow,false);
 assert.equal(c.events.at(-1).detail.id,'11');assert.equal(h.page.data.composeShow,true);assert.equal(h.page.data.composeEdit.id,'11');assert.equal(h.page.data.composeEdit.version,3);
});
test('真实组件链:删除确认保留原id和摘要,列表重排后仅删已确认帖',async()=>{
 const h=load('list');h.page.data.list=[h.page.postView(post(11)),h.page.postView(post(12))];const c=mountActions(h,'list');c.open();c.actions.onDelete();
 assert.equal(c.actions.data.postId,'');assert.equal(c.danger._params.id,'11');assert.ok(c.danger._params.name.includes('原文'));assert.equal(h.requests.length,0);
 h.page.data.list.reverse();c.danger.onConfirm();await tick();const write=h.asked('/api/v1/community/posts/11')[0];assert.equal(write.method,'DELETE');assert.equal(write.data.expectedVersion,3);
 await h.answer(write.url,null);assert.deepEqual(h.page.data.list.map(row=>row.id),[12]);assert.equal(c.danger.data.stage,'done');
});
test('真实组件链:举报在close后保留id,成功只移除目标而不依赖旧index',async()=>{
 const h=load('list');h.page.data.list=[h.page.postView(post(11,2)),h.page.postView(post(12,2))];const c=mountActions(h,'list');c.open();c.actions.onReport();
 assert.equal(c.actions.data.postId,'');assert.equal(c.events.at(-1).detail.id,'11');assert.equal(h.modals.length,1);h.modals[0].success({confirm:true});await tick();
 await h.answer('/api/v1/community/capabilities',{reporting:{policyVersion:'synthetic',reasons:[{code:'SERVER_REASON',label:'服务端原因'}]}});h.page.onReportReasonSelect({detail:{index:0}});await tick();
 const write=h.asked('/api/v1/community/reports')[0];assert.equal(write.data.targetId,11);h.page.data.list.reverse();await h.answer(write.url,{id:1});assert.deepEqual(h.page.data.list.map(row=>row.id),[12]);
});
test('真实组件链:取消删除和缺少危险组件均不产生请求',()=>{
 const h=load('list');h.page.data.list=[h.page.postView(post(11))];const c=mountActions(h,'list');c.open();c.actions.onDelete();c.danger.onCancel();assert.equal(h.requests.length,0);assert.equal(c.events.some(e=>e.name==='select'),false);
 c.actions.selectComponent=()=>null;c.open();c.actions.onDelete();assert.equal(h.requests.length,0);assert.equal(c.events.some(e=>e.name==='select'),false);
});
test('真实组件链:错误确认key或缺少确认快照id不能借当前props删除',()=>{
 const h=load('list');h.page.data.list=[h.page.postView(post(11))];const c=mountActions(h,'list');c.open();
 c.actions.onDeleteConfirmed({detail:{key:'other',params:{id:'11'}}});c.actions.onDeleteConfirmed({detail:{key:'club.post.delete',params:{}}});
 assert.equal(h.requests.length,0);assert.equal(c.events.some(e=>e.name==='select'),false);
});
for(const route of ['list','detail'])for(const invalidation of ['owner','unload'])test(`真实组件链:${route}删除确认前${invalidation}变化拒弃旧动作`,async()=>{
 const h=load(route);if(route==='detail')await h.ready();else h.page.data.list=[h.page.postView(post(11))];const c=mountActions(h,route);c.open();c.actions.onDelete();
 if(invalidation==='owner')h.changeOwner();else h.page._unloaded=true;const patchesBefore=h.patches.length;c.danger.onConfirm();await tick();assert.equal(h.requests.some(r=>r.method==='DELETE'),false);assert.equal(h.patches.length,patchesBefore);assert.equal(c.danger.data.stage,'confirm');
});
test('真实组件链:详情切换帖子后旧确认不得删除新目标',async()=>{
 const h=load('detail');await h.ready();const c=mountActions(h,'detail');c.open();c.actions.onDelete();h.page.setData({id:'88',info:{id:88,version:4}});c.danger.onConfirm();await tick();assert.equal(h.requests.some(r=>r.method==='DELETE'),false);
});
test('真实组件链:缺id只关闭菜单,已离开列表的目标不借其他行执行',async()=>{
 const h=load('list');h.page.data.list=[h.page.postView(post(11)),h.page.postView(post(12))];const c=mountActions(h,'list');c.open();c.actions.data.postId='';c.actions.onEdit();
 assert.equal(h.page.data.postActionShow,false);assert.equal(h.page.data.composeShow,false);assert.equal(c.events.some(e=>e.name==='select'),false);
 c.open();c.actions.onDelete();h.page.data.list=[h.page.postView(post(12))];c.danger.onConfirm();await tick();assert.equal(h.requests.length,0);assert.deepEqual(h.page.data.list.map(row=>row.id),[12]);assert.equal(c.danger.data.busy,false);assert.equal(c.danger.data.stage,'error');
});
