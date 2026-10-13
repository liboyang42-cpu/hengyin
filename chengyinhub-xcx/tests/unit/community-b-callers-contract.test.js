const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const {mount,bundle,reply,request,flush,editOpen,ROOT,COMPOSE,LIKE,PROFILE}=require('../fixtures/community-caller-harness.cjs');
test('bookmarks使用真实游标，下一页失败保留列表及同游标',async()=>{
 const h=mount(LIKE);h.instance.getPostFavoriteList();await flush();
 const first=request(h,'/me/bookmarks');assert.equal(first.method,'GET');
 reply(first,{items:[bundle(77)],hasMore:true,nextCursor:77});await flush();
 assert.equal(h.instance.data.hasMorePosts,true);h.instance.onReachBottom();await flush();
 const second=request(h,'/me/bookmarks');assert.equal(second.data.cursor,77);second.fail({msg:'offline'});await flush();
 assert.equal(h.instance.data.postList.length,1);h.instance.onReachBottom();await flush();assert.equal(request(h,'/me/bookmarks').data.cursor,77);
});

test('bookmarks旧账号回包不污染新账号，缺hasMore不得假成功',async()=>{
 const h=mount(LIKE);h.instance.getPostFavoriteList();await flush();const old=request(h,'/me/bookmarks');
 h.switchOwner(2);reply(old,{items:[bundle()],hasMore:false,nextCursor:77});await flush();assert.equal(h.instance.data.postList.length,0);
 h.instance.getPostFavoriteList();await flush();reply(request(h,'/me/bookmarks'),{items:[bundle()]});await flush();assert.equal(h.instance.data.postList.length,0);
});

test('取消收藏按真实状态回读，失败保留；重试复用原命令',async()=>{
 const h=mount(LIKE);h.instance.data.postList=[{id:77,version:2,isBookmarked:true}];
 const event={detail:{index:0}};h.instance.postUnlike(event);await flush();const a=request(h,'/actions/BOOKMARK');assert.equal(a.method,'DELETE');
 a.fail({msg:'unknown'});await flush();assert.equal(h.instance.data.postList.length,1);h.instance.postUnlike(event);await flush();const b=request(h,'/actions/BOOKMARK');assert.equal(b.data.requestId,a.data.requestId);
 reply(b,bundle(77,{viewerBookmarked:false}));await flush();assert.equal(h.instance.data.postList.length,0);
});

test('profile本人和他人使用各自真实接口，target变更弃旧页',async()=>{
 const h=mount(PROFILE);h.instance.data.isSelf=true;h.instance.data.activeTab='posts';h.instance.getCreativesquareList();await flush();assert.ok(request(h,'/me/posts'));
 h.instance.data.isSelf=false;h.instance.data.userId=8;h.instance.getCreativesquareList();await flush();const b=request(h,'/feeds/LATEST');assert.equal(b.data.authorId,8);
 reply(request(h,'/me/posts'),{items:[bundle(77)],hasMore:false,nextCursor:77});await flush();assert.equal(h.instance.data.list.length,0);
 reply(b,{items:[bundle(66,{authorId:8})],hasMore:false,nextCursor:66});await flush();assert.equal(h.instance.data.list[0].id,66);
});

test('不感兴趣仅精确applied回执隐藏，未知重试同key',async()=>{
 const h=mount(PROFILE);h.instance.data.list=[{id:77,version:2,isLiked:false},{id:66,version:1,isLiked:true}];
 const event={currentTarget:{dataset:{index:0,type:'2'}}};h.instance.lickClick(event);await flush();const first=request(h,'/actions');assert.equal(first.data.actionType,'NOT_INTERESTED');
 reply(first,{postId:66,action:'NOT_INTERESTED',applied:true});await flush();assert.equal(h.instance.data.list.length,2);
 h.instance.lickClick(event);await flush();const retry=request(h,'/actions');assert.equal(retry.data.requestId,first.data.requestId);reply(retry,{postId:77,action:'NOT_INTERESTED',applied:true});await flush();assert.deepEqual(Array.from(h.instance.data.list,x=>x.id),[66]);
});

test('profile点赞/收藏采用服务端计数并按ID更新，未知能力不猜',async()=>{
 const h=mount(PROFILE);h.instance.data.list=[{id:77,version:2,isLiked:false,likeCount:4}];h.instance.lickClick({currentTarget:{dataset:{index:0,type:1}}});await flush();
 h.instance.data.list.unshift({id:66,version:2});reply(request(h,'/actions'),bundle(77,{viewerLiked:true,likeCount:91}));await flush();assert.equal(h.instance.data.list[1].likeCount,91);assert.equal(h.instance.data.list[0].id,66);
 const count=h.requests.length;h.instance.favoriteClick({currentTarget:{dataset:{index:0}}});await flush();assert.equal(h.requests.length,count);
});

test('编辑保留所有未改关系，在途新正文keepOpen且真实version事件',async()=>{
 const h=mount(COMPOSE),original=bundle();original.topicCodes=['CITY'];original.mentions=[2];original.media=[{id:9,media_type:'FILE',object_key:'https://file.invalid/owned'}];
 await editOpen(h,original);h.instance.onInput({detail:{value:'saved'}});h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();const patch=request(h,'/posts/77');assert.equal(patch.method,'PATCH');assert.deepEqual(Array.from(patch.data.mediaIds),[9]);assert.deepEqual(Array.from(patch.data.topicCodes),['CITY']);
 h.instance.onInput({detail:{value:'unsent'}});reply(patch,{...original,post:{...original.post,body:'saved',version:3}});await flush();assert.equal(h.instance.data.content,'unsent');assert.equal(h.events.at(-1).detail.keepOpen,true);assert.equal(h.events.at(-1).detail.version,3);
});

test('真实精确选点确认PRIVATE，清位置不自动公开；上传同client真实receipt',async()=>{
 const h=mount(COMPOSE);h.definition.observers.show.call(h.instance,true);h.instance.onPickLocation();h.pick({latitude:31.2,longitude:121.5,name:'真实选择地点'});assert.equal(h.modals.at(-1).title,'精确地点仅自己可见');h.modals.at(-1).success({confirm:true});assert.equal(h.instance.data.postAudience,'PRIVATE');h.instance.onClearLocation();assert.equal(h.instance.data.postAudience,'PRIVATE');
 h.instance.onPickImage();assert.equal(h.app.upload.opts.bizType,'COMMUNITY_POST');const url=h.app.upload.opts.mapResult({url:'https://images.invalid/one.png',uploadReceipt:'test-receipt',byteSize:23,mimeType:'image/png'});h.app.upload.cb([url]);assert.equal(h.instance.data.picList[0],url);
});

test('状态徽章使用真实lifecycleLabel；不感兴趣标签明确',()=>{
 const card=fs.readFileSync(path.join(ROOT,'components/cy/post-card/index.wxml'),'utf8');assert.match(card,/post\.lifecycleLabel/);assert.match(card,/post\.status == 2/);
 const profile=fs.readFileSync(path.join(ROOT,'subpackageMember/components/cy/profile/index.wxml'),'utf8');assert.match(profile,/aria-label="不感兴趣"/);assert.match(profile,/wx:if="\{\{item\.lifecycleLabel\}\}">\{\{item\.lifecycleLabel\}\}/);
});

for(const change of ['account','detach','target'])test(`不感兴趣${change}后旧回包不得隐藏或提示`,async()=>{
 const h=mount(PROFILE);h.instance.data.list=[{id:77,version:2}];h.instance.lickClick({currentTarget:{dataset:{index:0,type:2}}});await flush();const write=request(h,'/actions');
 if(change==='account')h.switchOwner(2);else if(change==='detach')h.instance._profileAttached=false;else h.instance.data.userId=99;
 reply(write,{postId:77,action:'NOT_INTERESTED',applied:true});await flush();assert.equal(h.instance.data.list.length,1);assert.equal(h.toasts.length,0);
});

test('不感兴趣applied=false和transport失败均保留，不依据200隐藏',async()=>{
 const h=mount(PROFILE);h.instance.data.list=[{id:77,version:2}];const event={currentTarget:{dataset:{index:0,type:2}}};h.instance.lickClick(event);await flush();reply(request(h,'/actions'),{postId:77,action:'NOT_INTERESTED',applied:false});await flush();assert.equal(h.instance.data.list.length,1);assert.equal(h.toasts.length,1);
 h.instance.lickClick(event);await flush();request(h,'/actions').fail({errMsg:'timeout'});await flush();assert.equal(h.instance.data.list.length,1);assert.equal(h.toasts.length,2);
});

test('收藏请求跨列表刷新后可重试相同命令，不被旧busy永久锁住',async()=>{
 const h=mount(LIKE);h.instance.data.postList=[{id:77}];h.instance.postUnlike({detail:{index:0}});await flush();const old=request(h,'/actions/BOOKMARK');
 h.instance.getPostFavoriteList();await flush();reply(request(h,'/me/bookmarks'),{items:[bundle()],hasMore:false,nextCursor:77});await flush();
 h.instance.postUnlike({detail:{index:0}});await flush();const retry=request(h,'/actions/BOOKMARK');assert.notEqual(retry,old);assert.equal(retry.data.requestId,old.data.requestId);
 reply(old,bundle(77,{viewerBookmarked:false}));await flush();assert.equal(h.instance.data.postList.length,1);reply(retry,bundle(77,{viewerBookmarked:false}));await flush();assert.equal(h.instance.data.postList.length,0);
});

test('取消精确地点选择保留公开，无raw坐标写入；旧页选点回调无影响',()=>{
 const h=mount(COMPOSE);h.definition.observers.show.call(h.instance,true);h.instance.onPickLocation();h.pick({latitude:31.2,longitude:121.5,name:'选择地点'});h.modals.at(-1).success({confirm:false});assert.equal(h.instance.data.postAudience,'PUBLIC');assert.equal(h.instance.data.address,'');
 h.instance.onPickLocation();h.definition.observers.show.call(h.instance,false);h.pick({latitude:31.3,longitude:121.5,name:'旧地点'});assert.equal(h.modals.length,1);assert.equal(h.instance.data.address,'');
});

test('旧账号上传回执与裸URL均不得进入新账号表单',()=>{
 const h=mount(COMPOSE);h.definition.observers.show.call(h.instance,true);h.instance.onPickImage();const opts=h.app.upload.opts;assert.throws(()=>opts.mapResult({url:'https://images.invalid/a.png'}));h.switchOwner(2);assert.throws(()=>opts.mapResult({url:'https://images.invalid/a.png',uploadReceipt:'receipt',byteSize:1,mimeType:'image/png'}));h.app.upload.cb(['https://images.invalid/a.png']);assert.equal(h.instance.data.picList.length,0);
});

for(const change of ['account','reopen','detach'])test(`编辑${change}后迟到成功不清表单不触发事件`,async()=>{
 const h=mount(COMPOSE),original=bundle();await editOpen(h,original);h.instance.onInput({detail:{value:'submitted'}});h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();const write=request(h,'/posts/77');
 if(change==='account')h.switchOwner(2);else if(change==='detach')h.definition.lifetimes.detached.call(h.instance);else {h.definition.observers.show.call(h.instance,false);h.definition.observers.show.call(h.instance,true);h.instance.onInput({detail:{value:'new generation'}});}
 const expected=h.instance.data.content;reply(write,{...original,post:{...original.post,body:'submitted',version:3}});await flush();assert.equal(h.instance.data.content,expected);assert.equal(h.events.length,0);
});

test('编辑超时保留新输入，重试先回读同版本，明确重试才发送原wire',async()=>{
 const h=mount(COMPOSE),original=bundle();await editOpen(h,original);h.instance.onInput({detail:{value:'saved'}});h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();const first=request(h,'/posts/77');first.fail({errMsg:'timeout'});await flush();assert.equal(h.instance.data.content,'saved');assert.equal(h.instance.data.submitting,false);
 h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();assert.equal(h.requests.filter(r=>r.method==='PATCH').length,1);assert.match(h.toasts.at(-1),/核对后重试/);
 h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();const retry=request(h,'/posts/77');assert.equal(retry.method,'PATCH');assert.deepEqual(retry.data,first.data);
});

test('编辑超时后回读N+1同内容只读确认；新输入不能被旧成功清掉',async()=>{
 const h=mount(COMPOSE),original=bundle();await editOpen(h,original);h.instance.onInput({detail:{value:'saved'}});h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();request(h,'/posts/77').fail({errMsg:'timeout'});await flush();
 h.instance.onPublish();await flush();h.instance.onInput({detail:{value:'unsent'}});reply(request(h,'/posts/77'),{...original,post:{...original.post,body:'saved',version:3}});await flush();assert.equal(h.requests.filter(r=>r.method==='PATCH').length,1);assert.equal(h.instance.data.content,'unsent');assert.equal(h.events.at(-1).detail.keepOpen,true);
});

test('缺真实author能力或错误id的详情不得解锁编辑',async()=>{
 for(const invalid of [{...bundle(),viewerIsAuthor:undefined},bundle(88),bundle(77,{authorId:2})]) {
  const h=mount(COMPOSE);await editOpen(h,invalid);h.instance.onPublish();await flush();assert.equal(h.requests.filter(r=>r.method==='PATCH').length,0);assert.notEqual(h.instance._editReady,true);
 }
});

test('编辑回读期间新输入不被详情覆盖，权限确认后正常保存',async()=>{
 const h=mount(COMPOSE);h.instance.data.editPost={id:77,contents:'old summary'};h.definition.observers.show.call(h.instance,true);await flush();h.instance.onInput({detail:{value:'typed meanwhile'}});reply(request(h,'/posts/77'),bundle());await flush();assert.equal(h.instance.data.content,'typed meanwhile');assert.equal(h.instance._editReady,true);
});

test('profile同时点赞收藏逆序回包互不覆盖已确认状态及计数',async()=>{
 const h=mount(PROFILE);h.instance.data.list=[{id:77,version:2,isLiked:false,isBookmarked:false,likeCount:4,bookmarkCount:2}];
 h.instance.lickClick({currentTarget:{dataset:{index:0,type:1}}});h.instance.favoriteClick({currentTarget:{dataset:{index:0}}});await flush();
 const writes=h.requests.filter(r=>r.url.endsWith('/actions'));const like=writes.find(r=>r.data.actionType==='LIKE'),bookmark=writes.find(r=>r.data.actionType==='BOOKMARK');
 reply(bookmark,bundle(77,{viewerLiked:true,viewerBookmarked:true,likeCount:8,bookmarkCount:7}));await flush();reply(like,bundle(77,{viewerLiked:true,viewerBookmarked:false,likeCount:8,bookmarkCount:2}));await flush();
 assert.equal(h.instance.data.list[0].isLiked,true);assert.equal(h.instance.data.list[0].isBookmarked,true);assert.equal(h.instance.data.list[0].bookmarkCount,7);assert.equal(h.instance.data.list[0].likeCount,8);
});

test('冷重开编辑从持久unknown完整wire回读成功，不再PATCH',async()=>{
 const original=bundle(),h=mount(COMPOSE);await editOpen(h,original);h.instance.onInput({detail:{value:'saved'}});h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();request(h,'/posts/77').fail({errMsg:'timeout'});await flush();h.definition.lifetimes.detached.call(h.instance);
 const cold=mount(COMPOSE,{storage:h.storage});const saved={...original,post:{...original.post,body:'saved',version:3}};await editOpen(cold,saved);cold.instance.onPublish();await flush();reply(request(cold,'/posts/77'),saved);await flush();assert.equal(cold.requests.filter(r=>r.method==='PATCH').length,0);assert.equal(cold.events.find(e=>e.name==='published').detail.version,3);
});

test('profile游标下一页失败保留原页，重试相同cursor且不按页码猜hasMore',async()=>{
 const h=mount(PROFILE);h.instance.data.isSelf=true;h.instance.data.activeTab='posts';h.instance.getCreativesquareList();await flush();reply(request(h,'/me/posts'),{items:[bundle(77)],hasMore:true,nextCursor:77});await flush();
 h.instance.onFeedReachBottom();await flush();const next=request(h,'/me/posts');assert.equal(next.data.cursor,77);next.fail({msg:'offline'});await flush();assert.equal(h.instance.data.list.length,1);assert.equal(h.instance.data.hasMore,true);
 h.instance.onFeedReachBottom();await flush();assert.equal(request(h,'/me/posts').data.cursor,77);reply(request(h,'/me/posts'),{items:[bundle(66)],hasMore:false,nextCursor:66});await flush();assert.deepEqual(Array.from(h.instance.data.list,x=>x.id),[77,66]);assert.equal(h.instance.data.hasMore,false);
});

test('真实发布wire仅EXACT+PRIVATE与geohash，无裸经纬度且清除地点保持PRIVATE',async()=>{
 const h=mount(COMPOSE);h.definition.observers.show.call(h.instance,true);h.instance.onInput({detail:{value:'location post'}});h.instance.onPickLocation();h.pick({latitude:31.2,longitude:121.5,name:'选择地点'});h.modals.at(-1).success({confirm:true});
 h.instance.onPublish();await flush();reply(request(h,'/guidelines/active'),{id:1,contentJson:{rules:[{title:'真实规则'}]}});await flush();h.modals.at(-1).success({confirm:true});await flush();reply(request(h,'/guidelines/ack'),{});await flush();const create=h.requests.find(r=>r.url==='/api/v1/community/posts');assert.equal(create.data.audience,'PRIVATE');assert.equal(create.data.locationPrecision,'EXACT');assert.equal(create.data.approximateGeohash.length,9);assert.equal('latitude' in create.data,false);assert.equal('longitude' in create.data,false);assert.equal('lat' in create.data,false);assert.equal('lng' in create.data,false);
});

test('未知编辑结果后新输入再保存不能替换旧pending完整wire或静默新写',async()=>{
 const h=mount(COMPOSE),original=bundle();await editOpen(h,original);h.instance.onInput({detail:{value:'saved'}});h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();request(h,'/posts/77').fail({errMsg:'timeout'});await flush();
 const intent=require(path.join(ROOT,'utils/publish/publish-intent.js'));const previous=intent.pending(h.wx,'square:compose:edit:77',1);assert.equal(previous.input.body,'saved');
 h.instance.onInput({detail:{value:'new unsent'}});h.instance.onPublish();await flush();const current=intent.pending(h.wx,'square:compose:edit:77',1);assert.equal(current.key,previous.key,'必须先对账原意图');assert.equal(current.input.body,'saved');assert.equal(h.instance.data.content,'new unsent');assert.equal(h.requests.filter(r=>r.method==='PATCH').length,1);
});

test('核对旧编辑成功保留新正文，取消不启动恢复',async()=>{
 const original=bundle(),h=mount(COMPOSE);await editOpen(h,original);h.instance.onInput({detail:{value:'old submitted'}});h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();request(h,'/posts/77').fail({errMsg:'timeout'});await flush();
 h.instance.onInput({detail:{value:'new unsent'}});h.instance.onPublish();await flush();assert.equal(h.modals.at(-1).title,'先核对上次提交');assert.equal(h.instance.data.submitting,false);const before=h.requests.length;h.modals.at(-1).success({confirm:false});await flush();assert.equal(h.requests.length,before);
 h.instance.onPublish();await flush();h.modals.at(-1).success({confirm:true});await flush();reply(request(h,'/posts/77'),{...original,post:{...original.post,body:'old submitted',version:3}});await flush();assert.equal(h.instance.data.content,'new unsent');assert.equal(h.events.at(-1).detail.keepOpen,true);assert.equal(h.events.at(-1).detail.version,3);assert.match(h.toasts.at(-1),/旧提交已确认/);assert.equal(h.requests.filter(r=>r.method==='PATCH').length,1);
});

test('新建未知发布显式核对原wire，保留新正文图片关联与PRIVATE选点',async()=>{
 const h=mount(COMPOSE,{props:{prefill:'old submitted'}});h.definition.observers.show.call(h.instance,true);h.instance.onPublish();await flush();reply(request(h,'/guidelines/active'),{id:1,contentJson:{rules:[{title:'规则'}]}});await flush();h.modals.at(-1).success({confirm:true});await flush();reply(request(h,'/guidelines/ack'),{});await flush();const create=h.requests.find(r=>r.url==='/api/v1/community/posts');const original=bundle(77,{...create.data,id:77,authorId:1,version:0,lifecycle:'DRAFT'});reply(create,original);await flush();const publish=request(h,'/publish');publish.fail({errMsg:'timeout'});await flush();
 h.instance.onInput({detail:{value:'new unsent'}});h.instance.onPickImage();const url=h.app.upload.opts.mapResult({url:'https://images.invalid/new.png',uploadReceipt:'new-receipt',byteSize:12,mimeType:'image/png'});h.app.upload.cb([url]);h.instance.data.activityList=[{activityId:9,name:'选择活动',dataType:1}];h.instance.onPickActivity({currentTarget:{dataset:{index:0}}});h.instance.onPickLocation();h.pick({latitude:31.2,longitude:121.5,name:'选择地点'});h.modals.at(-1).success({confirm:true});const before=JSON.stringify([h.instance.data.content,h.instance.data.picList,h.instance.data.selectedActivity,h.instance.data.address,h.instance.data.postAudience]);
 h.instance.onPublish();await flush();assert.equal(h.modals.at(-1).title,'先核对上次提交');h.modals.at(-1).success({confirm:true});await flush();reply(request(h,'/posts/77'),original);await flush();const replay=request(h,'/publish');assert.deepEqual(replay.data,publish.data);reply(replay,{...original,post:{...original.post,version:1,lifecycle:'CHECKING'}});await flush();
 assert.equal(JSON.stringify([h.instance.data.content,h.instance.data.picList,h.instance.data.selectedActivity,h.instance.data.address,h.instance.data.postAudience]),before);assert.equal(h.events.at(-1).detail.keepOpen,true);assert.equal(h.requests.filter(r=>r.url==='/api/v1/community/posts').length,1);assert.equal(h.requests.filter(r=>r.url.endsWith('/media/register')).length,0);assert.equal(h.events.some(e=>e.name==='close'),false);
});


test('恢复确认弹窗在换号或重开后不再发出旧账号请求',async()=>{
 for(const change of ['account','reopen']) {
  const h=mount(COMPOSE),original=bundle();await editOpen(h,original);h.instance.onInput({detail:{value:'old submitted'}});h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();request(h,'/posts/77').fail({errMsg:'timeout'});await flush();h.instance.onInput({detail:{value:'new unsent'}});h.instance.onPublish();await flush();const prompt=h.modals.at(-1);assert.equal(prompt.title,'先核对上次提交');
  if(change==='account')h.switchOwner(2);else {h.definition.observers.show.call(h.instance,false);h.definition.observers.show.call(h.instance,true);await flush();}
  const count=h.requests.length;prompt.success({confirm:true});await flush();assert.equal(h.requests.length,count);assert.equal(h.events.length,0);
 }
});


test('换号后旧打开表单不能以新账号提交旧正文',async()=>{
 const h=mount(COMPOSE,{props:{prefill:'old owner text'}});h.definition.observers.show.call(h.instance,true);h.switchOwner(2);h.instance.onPublish();await flush();assert.equal(h.requests.length,0);assert.equal(h.instance.data.content,'old owner text');assert.match(h.toasts.at(-1),/身份已变化/);assert.equal(h.instance.data.submitting,false);
});


test('换号后旧收藏和主页卡片不能代表新账号发出互动',async()=>{
 for(const rel of [LIKE,PROFILE]) {
  const h=mount(rel);h.instance.communityClient();h.switchOwner(2);h.instance.data.postList=[{id:77,version:2}];h.instance.data.list=[{id:77,version:2,isLiked:false}];
  if(rel===LIKE)h.instance.postUnlike({detail:{index:0}});else h.instance.lickClick({currentTarget:{dataset:{index:0,type:2}}});
  await flush();assert.equal(h.requests.length,0);assert.match(h.toasts.at(-1),/身份已变化/);
 }
});

async function pendingDeletedCreate(h) {
 h.definition.observers.show.call(h.instance,true);h.instance.onInput({detail:{value:'preserved body'}});h.instance.onPublish();await flush();
 reply(request(h,'/guidelines/active'),{id:1,contentJson:{rules:[{title:'真实规则'}]}});await flush();h.modals.at(-1).success({confirm:true});await flush();reply(request(h,'/guidelines/ack'),{});await flush();
 const first=h.requests.findLast(r=>r.url==='/api/v1/community/posts');first.fail({errMsg:'timeout'});await flush();h.instance.onPublish();await flush();const retry=h.requests.findLast(r=>r.url==='/api/v1/community/posts');assert.deepEqual(retry.data,first.data);return {first,retry};
}
async function nextNewCreate(h) {
 for(let i=0;i<5;i++) {
  await flush();const latest=h.requests.at(-1);
  if(latest.url==='/api/v1/community/posts')return latest;
  if(latest.url.endsWith('/guidelines/active')) {reply(latest,{id:1,contentJson:{rules:[{title:'真实规则'}]}});await flush();h.modals.at(-1).success({confirm:true});}
  else if(latest.url.endsWith('/guidelines/ack'))reply(latest,{});
  else assert.fail('unexpected restart stage '+latest.url);
 }
 assert.fail('restart did not reach new create');
}

test('DELETED明确回执取消时保留当前表单及原key，确认后先同key核验再新key同body',async()=>{
 const h=mount(COMPOSE),{first,retry}=await pendingDeletedCreate(h);const intent=require(path.join(ROOT,'utils/publish/publish-intent.js'));
 reply(retry,{intentDiscarded:true});await flush();const prompt=h.modals.at(-1);assert.equal(prompt.title,'保留当前内容作为新帖');assert.equal(intent.pending(h.wx,'square:compose',1).key,first.data.clientRequestId);assert.equal(h.instance.data.content,'preserved body');assert.equal(h.instance.data.submitting,false);
 const count=h.requests.length;prompt.success({confirm:false});await flush();assert.equal(h.requests.length,count);assert.equal(intent.pending(h.wx,'square:compose',1).key,first.data.clientRequestId);
 h.instance.onPublish();await flush();reply(h.requests.at(-1),{intentDiscarded:true});await flush();h.modals.at(-1).success({confirm:true});await flush();const verify=h.requests.at(-1);assert.deepEqual(verify.data,first.data);reply(verify,{intentDiscarded:true});
 const fresh=await nextNewCreate(h);assert.notEqual(fresh.data.clientRequestId,first.data.clientRequestId);assert.equal(fresh.data.body,first.data.body);const draft=bundle(88,{...fresh.data,id:88,authorId:1,version:0,lifecycle:'DRAFT'});reply(fresh,draft);await flush();reply(request(h,'/publish'),{...draft,post:{...draft.post,version:1,lifecycle:'CHECKING'}});await flush();assert.equal(h.events.find(e=>e.name==='published').detail.id,88);assert.equal(h.events.find(e=>e.name==='published').detail.keepOpen,false);assert.equal(h.events.at(-1).name,'close');
});

for(const response of [{code:404,data:{}},{code:409,data:{}},{code:200,data:{intentDiscarded:false}},{code:200,data:{intentDiscarded:true,extra:true}},{code:200,data:bundle(77,{lifecycle:'REMOVED'})}])test(`非唯一删除回执不重启不清key ${response.code}:${JSON.stringify(response.data).slice(0,35)}`,async()=>{
 const h=mount(COMPOSE),{first,retry}=await pendingDeletedCreate(h);reply(retry,response.data,response.code);await flush();assert.equal(h.modals.some(m=>m.title==='保留当前内容作为新帖'),false);assert.equal(h.instance.data.content,'preserved body');assert.equal(require(path.join(ROOT,'utils/publish/publish-intent.js')).pending(h.wx,'square:compose',1).key,first.data.clientRequestId);assert.equal(h.requests.filter(r=>r.url==='/api/v1/community/posts').length,2);
});

for(const change of ['input','account','reopen'])test(`删除后重发确认对${change}变化失效`,async()=>{
 const h=mount(COMPOSE),{retry}=await pendingDeletedCreate(h);reply(retry,{intentDiscarded:true});await flush();const prompt=h.modals.at(-1);assert.equal(prompt.title,'保留当前内容作为新帖');
 if(change==='input')h.instance.onInput({detail:{value:'changed after prompt'}});else if(change==='account')h.switchOwner(2);else {h.definition.observers.show.call(h.instance,false);h.definition.observers.show.call(h.instance,true);await flush();}
 const count=h.requests.length;prompt.success({confirm:true});await flush();assert.equal(h.requests.length,count);assert.equal(h.events.length,0);
});

async function deletedCreateWithImage(h) {
 h.definition.observers.show.call(h.instance,true);h.instance.onInput({detail:{value:'image body'}});
 h.instance.onPickImage();const url=h.app.upload.opts.mapResult({url:'https://images.invalid/old.png',uploadReceipt:'old-receipt',byteSize:12,mimeType:'image/png'});h.app.upload.cb([url]);
 h.instance.onPickLocation();h.pick({latitude:31.2,longitude:121.5,name:'私密地点'});h.modals.at(-1).success({confirm:true});h.instance.data.activityList=[{activityId:9,name:'原活动',dataType:1}];h.instance.onPickActivity({currentTarget:{dataset:{index:0}}});
 h.instance.onPublish();await flush();reply(request(h,'/guidelines/active'),{id:1,contentJson:{rules:[{title:'真实规则'}]}});await flush();h.modals.at(-1).success({confirm:true});await flush();reply(request(h,'/guidelines/ack'),{});await flush();reply(request(h,'/media/register'),{id:9});await flush();
 const first=h.requests.findLast(r=>r.url==='/api/v1/community/posts');first.fail({errMsg:'timeout'});await flush();h.instance.onPublish();await flush();reply(h.requests.at(-1),{intentDiscarded:true});await flush();h.modals.at(-1).success({confirm:true});await flush();reply(h.requests.at(-1),{intentDiscarded:true});await flush();return first;
}

test('旧图片删除终态保留key及私密表单，短提示明确现有移除和重传入口',async()=>{
 const h=mount(COMPOSE),first=await deletedCreateWithImage(h);assert.equal(h.toasts.at(-1),'请移除旧图，重新选图上传');assert.equal(h.instance.data.content,'image body');assert.equal(h.instance.data.picList[0],'https://images.invalid/old.png');assert.equal(h.instance.data.selectedActivity.activityId,9);assert.equal(h.instance.data.postAudience,'PRIVATE');assert.equal(h.instance.data.submitting,false);
 assert.equal(require(path.join(ROOT,'utils/publish/publish-intent.js')).pending(h.wx,'square:compose',1).key,first.data.clientRequestId);assert.equal(h.requests.filter(r=>r.url.endsWith('/media/register')).length,1);assert.equal(h.events.length,0);
});

test('删除后移除旧图并用现有SDK新receipt上传，才正常登记新媒体与新帖',async()=>{
 const h=mount(COMPOSE),first=await deletedCreateWithImage(h);h.instance.onClearPic({currentTarget:{dataset:{inx:0}}});h.instance.onPickImage();const freshUrl=h.app.upload.opts.mapResult({url:'https://images.invalid/fresh.png',uploadReceipt:'fresh-receipt',byteSize:18,mimeType:'image/png'});h.app.upload.cb([freshUrl]);h.instance.onPublish();await flush();
 const handledRequests=new Set(h.requests.slice(0,-1)),handledPrompts=new Set(h.modals.slice(0,-1));let fresh;
 for(let i=0;i<12 && !fresh;i++) {
  await flush();const prompt=h.modals.at(-1),req=h.requests.at(-1);
  if(prompt && !handledPrompts.has(prompt) && ['先核对上次提交','保留当前内容作为新帖','社区发布规范'].includes(prompt.title)){handledPrompts.add(prompt);prompt.success({confirm:true});continue;}
  if(!req || handledRequests.has(req))continue;handledRequests.add(req);
  if(req.url==='/api/v1/community/posts') {if(req.data.clientRequestId===first.data.clientRequestId)reply(req,{intentDiscarded:true});else fresh=req;}
  else if(req.url.endsWith('/guidelines/active'))reply(req,{id:1,contentJson:{rules:[{title:'真实规则'}]}});
  else if(req.url.endsWith('/guidelines/ack'))reply(req,{});
  else if(req.url.endsWith('/media/register')){assert.equal(req.data.uploadReceipt,'fresh-receipt');assert.equal(req.data.objectKey,freshUrl);reply(req,{id:17});}
  else assert.fail('unexpected media restart stage '+req.url);
 }
 assert.ok(fresh,'正常重新上传后应能继续创建');assert.notEqual(fresh.data.clientRequestId,first.data.clientRequestId);assert.deepEqual(Array.from(fresh.data.mediaIds),[17]);assert.equal(fresh.data.body,'image body');assert.equal(fresh.data.audience,'PRIVATE');assert.equal(fresh.data.references[0].referenceId,9);assert.equal(fresh.data.locationPrecision,'EXACT');
 const draft=bundle(88,{...fresh.data,id:88,authorId:1,version:0,lifecycle:'DRAFT'});draft.media=[{id:17,mediaType:'IMAGE',objectKey:freshUrl}];draft.references=fresh.data.references;reply(fresh,draft);await flush();reply(request(h,'/publish'),{...draft,post:{...draft.post,version:1,lifecycle:'CHECKING'}});await flush();assert.equal(h.events.find(e=>e.name==='published').detail.id,88);assert.equal(h.events.at(-1).name,'close');
});

test('本人不感兴趣确认后回读myPosts，只展示服务端返回的过滤结果',async()=>{
 const h=mount(PROFILE);h.instance.data.isSelf=true;h.instance.data.list=[{id:77,version:2},{id:66,version:2}];h.instance.lickClick({currentTarget:{dataset:{index:0,type:2}}});await flush();reply(request(h,'/actions'),{postId:77,action:'NOT_INTERESTED',applied:true});await flush();const read=request(h,'/me/posts');assert.ok(read);assert.equal(h.requests.filter(r=>r.url.includes('/feeds/LATEST')).length,0);reply(read,{items:[bundle(66)],hasMore:false,nextCursor:66});await flush();assert.deepEqual(Array.from(h.instance.data.list,x=>x.id),[66]);
});

test('profile游标追加只发送新增路径，重复行不回传旧页，刷新移除旧分页字段',async()=>{
 const h=mount(PROFILE);h.instance.data.isSelf=true;h.instance.getCreativesquareList();await flush();reply(request(h,'/me/posts'),{items:[bundle(77)],hasMore:true,nextCursor:77});await flush();
 const patches=[],setData=h.instance.setData;h.instance.setData=function(p){patches.push(p);setData.call(this,p)};
 h.instance.getCreativesquareList(true);await flush();assert.equal(request(h,'/me/posts').data.cursor,77);
 reply(request(h,'/me/posts'),{items:[bundle(77),bundle(66)],hasMore:false,nextCursor:66});await flush();
 assert.deepEqual(Array.from(h.instance.data.list,r=>r.id),[77,66]);assert.equal(patches.some(p=>Object.hasOwn(p,'list')),false);assert.equal(patches.filter(p=>Object.hasOwn(p,'list[1]')).length,1);
 h.instance.reloadFeed();assert.equal(patches.some(p=>Object.hasOwn(p,'page_no')),false);assert.equal(Object.hasOwn(h.instance.data,'page_no'),false);
});
