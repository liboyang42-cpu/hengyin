const test = require('node:test'); const assert = require('node:assert/strict'); const path = require('node:path');
const fs = require('node:fs'); const Module = require('node:module'); const { execFileSync } = require('node:child_process');
const file = path.resolve(__dirname, '../../scripts/uiaudit/build-action-ledger.js');
let g;
if (process.env.COMMUNITY_LEDGER_BASELINE) {
 const m = new Module(file, module); m.filename = file; m.paths = Module._nodeModulePaths(path.dirname(file));
 m._compile(execFileSync('git', ['show', 'HEAD:chengyinhub-xcx/scripts/uiaudit/build-action-ledger.js'], {encoding:'utf8'}), file); g=m.exports;
} else g=require(file);
// 2026-10-29 R2:广场列表页已删,真实点赞改用帖文详情的同名 handler。
const real = () => g.targetsForHandler(path.resolve(__dirname,'../../pages/square/detail/index.js'),'lickClick');
test('real like resolves only canonical enable/cancel and remains write',()=>{
 const t=real(); assert.deepEqual(t.primaryApis, ['/api/v1/community/posts/{param}/actions','/api/v1/community/posts/{param}/actions/LIKE']);
 assert.ok(t.apiContracts.some(c=>c.method==='DELETE')); assert.ok(g.inferEffectClasses('lickClick','community.request({})',new Map(),t).includes('write'));
});
const map=url=>g.communityRequestContracts(`community.request({url:'${url}',data:{id:4}})`,['community']).contracts;
test('bookmark and comment likes keep distinct POST and DELETE',()=>{
 for(const u of ['/api/creativesquare/bookmark','/api/comment/like'])assert.deepEqual(map(u).map(c=>c.method),['POST','DELETE']);
 assert.ok(map('/api/creativesquare/bookmark')[1].url.endsWith('/BOOKMARK'));
});
test('actual feed handlers resolve only their genuine branches',()=>{
 for (const [file,handler,expected] of [
  ['pages/mylike/mylike.js','getPostFavoriteList',['/api/v1/community/me/bookmarks']],
  ['subpackageMember/components/cy/profile/index.js','getCreativesquareList',['/api/v1/community/feeds/LATEST','/api/v1/community/me/posts']],
 ]) assert.deepEqual(g.targetsForHandler(path.resolve(__dirname,'../..',file),handler).primaryApis,expected);
});
test('publish retains guideline ordering, edit version and conditional publish',()=>{
 const cs=map('/api/creativesquare/action'); assert.deepEqual(cs.slice(0,2).map(c=>[c.url,c.method]),[['/api/v1/community/guidelines/active','GET'],['/api/v1/community/guidelines/ack','POST']]);
 assert.ok(cs.some(c=>c.method==='PATCH')); assert.ok(cs.some(c=>c.adapterBranch==='draft-only-publish')); assert.ok(cs.some(c=>c.adapterBranch==='new-upload-receipt'));
});
test('report requires taxonomy before confirmed report',()=>assert.deepEqual(map('/api/comment/report').map(c=>c.method),['GET','POST']));
test('unknown commands and nonliteral URLs fail closed; unrelated passthrough untouched',()=>{
 assert.throws(()=>map('/api/comment/unknown'),/未登记/);
 assert.throws(()=>g.communityRequestContracts('community.request({url:dynamic})',['community']),/literal/);
 assert.deepEqual(map('/api/user/info'),[]);
 assert.deepEqual(g.communityRequestContracts("other.request({url:'/api/comment/unknown'})",['community']).contracts,[]);
});
test('adapter dependency already changes source digest',()=>{
 const c={entry:{wxml:'pages/square/detail/index.wxml',js:'pages/square/detail/index.js'}};
 assert.ok(g.sourceFilesFor(c).includes('utils/community-post-api.js'));
 assert.notEqual(g.sourceDigestFor(c),g.sourceDigestFor(c,{'utils/community-post-api.js':'changed adapter'}));
});

test('canonical actions preserve self boundary without weakening owned edits',()=>{
 for (const c of map('/api/creativesquare/like').concat(map('/api/creativesquare/bookmark'),map('/api/comment/like'))) assert.equal(g.isSelfOnlyWriteApi(c.url),true);
 assert.equal(g.isSelfOnlyWriteApi('/api/v1/community/posts/{param}'),false);
 assert.equal(g.isSelfOnlyWriteApi('/api/v1/community/posts/{param}/comments/{param}'),false);
});
test('self relation keeps visibility failures separate from owner denial',()=>{
 const branches=g.requiredBranchesFor('write','write',[],['write'],'self');
 assert.ok(branches.includes('error')); assert.ok(!branches.includes('forbidden-owner'));
 const owned=g.requiredBranchesFor('write','write',[],['write'],'owned-resource');
 assert.ok(owned.includes('forbidden-owner'));
 // A visible other-author post permits the actor's own relation; ownership writes remain separate.
 for (const url of ['/api/v1/community/posts/{param}', '/api/v1/community/posts/{param}/comments/{param}'])
   assert.equal(g.isSelfOnlyWriteApi(url), false);
});

test('comments and string literals cannot bind fake adapters or fake requests',()=>{
 const file=path.resolve(__dirname,'../../pages/square/detail/index.js');
 const declaration="const community = require('../../../utils/community-post-api.js').createClient(app, wx);";
 assert.deepEqual(g.communityAliasesForSource(declaration,file),['community']);
 for(const fake of ['// '+declaration, '/* '+declaration+' */',JSON.stringify(declaration)])
   assert.deepEqual(g.communityAliasesForSource(fake,file),[]);
 const request="community.request({url:'/api/comment/unknown'})";
 for(const fake of ['// '+request,'/* '+request+' */',JSON.stringify(request)])
   assert.deepEqual(g.communityRequestContracts(fake,['community']).contracts,[]);
 assert.throws(()=>g.communityRequestContracts(request,['community']),/未登记/);
});

test('mutually exclusive actions have separate request plans, never a combined write chain',()=>{
 const p=g.communityRequestPlan(map('/api/creativesquare/like'));
 assert.equal(p.requiredRequestsByBranch['enable-LIKE'].length,1);
 assert.equal(p.requiredRequestsByBranch['disable-LIKE'][0].method,'DELETE');
 assert.equal(p.requiredRequests.length,1);
});
test('publish plans keep versions, exact media arity and ordered guideline/publish stages',()=>{
 const p=g.communityRequestPlan(map('/api/creativesquare/action'));
 assert.deepEqual(p.requiredRequests.map(r=>r.method),['GET','POST','POST','POST']);
 assert.deepEqual(p.requiredRequestsByBranch['edit-published-media-0'].map(r=>r.method),['GET','POST','GET','PATCH']);
 assert.equal(p.requiredRequestsByBranch['create-media-2'].filter(r=>r.urlTemplate.endsWith('/media/register')).length,2);
 assert.equal(p.requiredRequestsByBranch['edit-draft-media-0'].at(-1).urlTemplate,'/api/v1/community/posts/{param}/publish');
 assert.deepEqual(p.requiredRequestOutcomes['stage-2-error'],['success','error']);
 const report=g.communityRequestPlan(map('/api/comment/report'));
 assert.deepEqual(report.requiredRequests.map(r=>r.method),['GET','POST']);
 assert.notDeepEqual(report.requiredRequests.slice().reverse(),report.requiredRequests);
});

test('roam shorthand req(url,method,data) maps canonical publish without legacy targets',()=>{
 const t=g.targetsForHandler(path.resolve(__dirname,'../../pages/roam/index.js'),'shareTo');
 assert.ok(t.primaryApis.includes('/api/v1/community/posts'));
 assert.ok(!t.primaryApis.some(u=>u.startsWith('/api/creativesquare/')));
});

test('real follow keeps its normal write before canonical comment readback',()=>{
 const t=g.targetsForHandler(path.resolve(__dirname,'../../pages/square/detail/index.js'),'commentFollowClick');
 assert.ok(t.primaryApis.includes('/api/user/follow/action'));
 assert.ok(!t.primaryApis.includes('/api/comment/list'));
 const p=g.communityRequestPlan(t.apiContracts);
 assert.deepEqual(p.requiredRequests.map(r=>[r.urlTemplate,r.method,r.kind]),[
  ['/api/user/follow/action','POST','write'],['/api/v1/community/posts/{param}/comments','GET','read']]);
});

test('real delete with conditional detail readback keeps DELETE as required authority',()=>{
 const t=g.targetsForHandler(path.resolve(__dirname,'../../pages/square/detail/index.js'),'deleteComment');
 const p=g.communityRequestPlan(t.apiContracts);
 for(const branch of ['success','error','timeout','duplicate-trigger'])
  assert.deepEqual(p.requiredRequestsByBranch[branch].map(r=>[r.urlTemplate,r.method]),
   [['/api/v1/community/posts/{param}/comments/{param}','DELETE']]);
 assert.deepEqual(p.requiredRequestsByBranch.detail.map(r=>r.method),['GET']);
});

test('GET-only evidence cannot satisfy the real delete success authority',()=>{
 const t=g.targetsForHandler(path.resolve(__dirname,'../../pages/square/detail/index.js'),'deleteComment');
 const p=g.communityRequestPlan(t.apiContracts);
 const {authorityRequestPlan}=require('../automator/action-evidence-runner');
 assert.throws(()=>authorityRequestPlan({id:'delete-comment',...p},'success',
  {requests:[{urlTemplate:'/api/v1/community/posts/{param}',method:'GET'}]}),/未命中静态有序请求合同/);
 assert.doesNotThrow(()=>authorityRequestPlan({id:'delete-comment',...p},'success',
  {requests:p.requiredRequests}));
});

test('write authority selection is independent of contract ordering and reads stay reads',()=>{
 const write=map('/api/comment/delete'), read=map('/api/creativesquare/info');
 for(const cs of [read.concat(write),write.concat(read)])
  assert.deepEqual(g.communityRequestPlan(cs).requiredRequests.map(r=>r.method),['DELETE']);
 assert.deepEqual(g.communityRequestPlan(read).requiredRequests.map(r=>r.method),['GET']);
 const unknown=map('/api/comment/add').concat(map('/api/creativesquare/delete'))
  .map(c=>({...c,adapterBranch:'unknown-'+c.method}));
 assert.throws(()=>g.communityRequestPlan(unknown),/ambiguous community write authority/);
});
