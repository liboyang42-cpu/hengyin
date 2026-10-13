const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {mount,bundle,reply,request,flush,ROOT,LIKE}=require('../fixtures/community-caller-harness.cjs');

test('帖文收藏刷新502保留旧卡片与操作，失败后停止下拉动画',async()=>{
 const h=mount(LIKE);let stops=0;h.wx.stopPullDownRefresh=()=>stops++;
 h.instance.getPostFavoriteList();await flush();reply(request(h,'/me/bookmarks'),{items:[bundle(11)],hasMore:false,nextCursor:11});await flush();
 const previous=h.instance.data.postList;h.instance.onPullDownRefresh();await flush();
 assert.deepEqual(h.instance.data.postList,previous);assert.equal(h.instance.data.loading,true);assert.equal(h.instance.data.refreshing,true);assert.equal(stops,0);
 request(h,'/me/bookmarks').successStatusAbnormal({statusCode:502});await flush();
 assert.deepEqual(h.instance.data.postList,previous);assert.equal(h.instance.data.loading,false);assert.equal(h.instance.data.refreshing,false);assert.equal(stops,1);assert.ok(h.toasts.length>0,'有旧卡片时失败仍可见');
 const view=fs.readFileSync(path.join(ROOT,'pages/mylike/mylike.wxml'),'utf8');assert.doesNotMatch(view,/<cy-inline-error[^>]*staleError/);assert.match(view,/bind:detail="goPost"/);assert.match(view,/bind:favorite="postUnlike"/);
});

test('并发取消收藏按ID删除，顺序或逆序回包均保留未取消项',async()=>{
 for(const order of [[0,1],[1,0]]){
  const h=mount(LIKE);h.instance.data.postList=[{id:33},{id:22},{id:11}];
  h.instance.postUnlike({detail:{index:0}});h.instance.postUnlike({detail:{index:1}});await flush();
  const writes=h.requests.filter(r=>r.method==='DELETE');assert.equal(writes.length,2);
  for(const index of order){reply(writes[index],bundle(index===0?33:22,{viewerBookmarked:false}));await flush();}
  assert.deepEqual(Array.from(h.instance.data.postList,x=>x.id),[11]);
 }
});
