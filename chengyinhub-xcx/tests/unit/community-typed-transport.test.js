const test=require('node:test'),assert=require('node:assert/strict');
const {createClient}=require('../../utils/community-post-api');
const view=require('../../utils/community-post-view');
const tick=()=>new Promise(r=>setImmediate(r));
const bundle=()=>({post:{id:59,authorId:990005,version:3,lifecycle:'PUBLISHED',body:'正文',viewerLiked:0,viewerBookmarked:0,likeCount:2,bookmarkCount:2},media:[],references:[],mentions:[],topicCodes:[],viewerIsAuthor:true,viewerCanComment:true,viewerFollowing:false});
function harness(){let actor=990005,alive=true;const calls=[],results=[],failures=[],complete=[];
 const app={getUserID:()=>actor,sendRequest(o){calls.push(o);return{abort(){o.fail({errMsg:'abort'})}}}};
 const client=createClient(app,{});const invoke=(method,args={})=>client[method]({...args,current:()=>alive&&actor===990005,autoErrorToast:false,success:r=>results.push(r),fail:r=>failures.push(r),complete:()=>complete.push(1)});
 return{client,calls,results,failures,complete,invoke,setActor(v){actor=v},unload(){alive=false;client.dispose()}};
}
for(const [method,url] of [['feed','/api/v1/community/feeds/LATEST'],['myPosts','/api/v1/community/me/posts'],['bookmarks','/api/v1/community/me/bookmarks']])
 test(method+' reads canonical cursor data without manufactured totals',async()=>{const h=harness();h.invoke(method,{query:{limit:10}});await tick();assert.equal(h.calls[0].url,url);assert.equal(h.calls[0].method,'GET');h.calls[0].success({code:200,data:{items:[bundle()],hasMore:false,nextCursor:59}});await tick();assert.equal(h.results[0].data.hasMore,false);assert.equal(h.results[0].data.total,undefined)});
test('missing hasMore/cursor, nonadvancing and inconsistent empty pages fail closed',async()=>{
 for(const data of [{items:[],nextCursor:null},{items:[],hasMore:false},{items:[],hasMore:true,nextCursor:50},{items:[bundle()],hasMore:true,nextCursor:59}]){
  const h=harness();h.invoke('feed',{query:{cursor:59}});await tick();h.calls[0].success({code:200,data});await tick();assert.equal(h.results[0].code,502);
 }
});
test('comments retain whole roots and replies, validate final root cursor and reject orphans',async()=>{
 const root=(id)=>({id,post_id:59,author_id:990005,version:0,parent_id:null,root_id:null,body:'root'});
 const reply={id:40,post_id:59,author_id:990012,version:0,parent_id:33,root_id:33,body:'reply'};
 const h=harness();h.invoke('comments',{postId:59,query:{limit:2,cursor:44}});await tick();h.calls[0].success({code:200,data:{items:[root(33),reply,root(22)],hasMore:true,nextCursor:22}});await tick();assert.equal(h.results[0].data.items.length,3);
 for(const items of [[reply],[root(33),{...reply,root_id:99}]]){const bad=harness();bad.invoke('comments',{postId:59});await tick();bad.calls[0].success({code:200,data:{items,hasMore:false,nextCursor:33}});await tick();assert.equal(bad.results[0].code,502)}
});
test('actions use exact command/direction and preserve real negative-feedback receipt',async()=>{
 const h=harness();h.invoke('addPostAction',{postId:59,command:{actionType:'NOT_INTERESTED',requestId:'stable-action-1'}});await tick();assert.equal(h.calls[0].method,'POST');h.calls[0].success({code:200,data:{postId:59,action:'NOT_INTERESTED',applied:true}});await tick();assert.equal(h.results[0].data.applied,true);
 const b=harness();b.invoke('removePostAction',{postId:59,actionType:'LIKE',command:{requestId:'stable-unlike-1'}});await tick();assert.equal(b.calls[0].url,'/api/v1/community/posts/59/actions/LIKE');assert.equal(b.calls[0].method,'DELETE');b.calls[0].success({code:200,data:bundle()});await tick();assert.equal(b.results[0].data.post.likeCount,2);
 const bad=harness();bad.invoke('addPostAction',{postId:59,command:{actionType:'NOT_INTERESTED',requestId:'stable-action-2'}});await tick();bad.calls[0].success({code:200,data:{postId:59,action:'NOT_INTERESTED',applied:1}});await tick();assert.equal(bad.results[0].code,502);
});
test('addComment sends only caller body, parent and stable key; root/repliedTo remain server-derived',async()=>{
 const h=harness();h.invoke('addComment',{postId:59,command:{clientRequestId:'stable-comment-1',body:'回复',parentId:3,rootId:999,repliedToMemberId:777}});await tick();assert.deepEqual(h.calls[0].data,{clientRequestId:'stable-comment-1',body:'回复',parentId:3});h.calls[0].success({code:200,data:{approvalState:'VISIBLE',lifecycle:'CHECKING',post:bundle()}});await tick();assert.equal(h.results[0].data.lifecycle,'CHECKING');assert.equal(h.results[0].data.commentId,undefined);
});
test('delete methods require actual version and cannot silently substitute version zero',async()=>{
 const h=harness();h.invoke('deletePost',{postId:59,command:{requestId:'stable-delete-1'}});await tick();assert.equal(h.calls.length,0);assert.equal(h.results[0].code,502);
 const ok=harness();ok.invoke('deleteComment',{postId:59,commentId:3,command:{requestId:'stable-delete-2',expectedVersion:7}});await tick();assert.equal(ok.calls[0].url,'/api/v1/community/posts/59/comments/3');assert.deepEqual(ok.calls[0].data,{requestId:'stable-delete-2',expectedVersion:7});ok.calls[0].success({code:200});await tick();assert.equal(ok.results[0].code,200);
});
test('late callbacks after owner switch, generation invalidation and dispose cannot alter caller state',async()=>{
 for(const phase of ['owner','dispose']){const h=harness();h.invoke('detail',{postId:59});await tick();if(phase==='owner')h.setActor(990012);else h.unload();h.calls[0].success({code:200,data:bundle()});await tick();assert.equal(h.results.length,0);assert.equal(h.failures.length,0);assert.equal(h.complete.length,0)}
});
test('upload capture requires actual owner-bound receipt; cannot manufacture from a URL',()=>{
 const h=harness();assert.throws(()=>h.client.captureUpload({url:'/image'}),e=>e.code===409);
 const image={url:'/image',uploadReceipt:'synthetic-unit-receipt',byteSize:64,mimeType:'image/png'};assert.equal(h.client.captureUpload(image),'/image');h.setActor(990012);assert.throws(()=>h.client.captureUpload(image),e=>e.code===409);
});
test('view preserves authority/version and keeps exact owner location out of card data',()=>{
 const b=bundle();b.post.approximateGeohash='wtw3sjq6g';b.post.poiName='private';delete b.viewerCanComment;delete b.viewerFollowing;
 const row=view.toPostView(b);assert.equal(row.version,3);assert.equal(row.viewerCanComment,undefined);assert.equal(row.isFollowTheUser,undefined);assert.equal(row.approximateGeohash,undefined);assert.equal(row.poiName,undefined);
 const raw={id:3,post_id:59,author_id:990012,version:7,body:'回复',parent_id:1,root_id:1,viewer_liked:1};const comment=view.toCommentView(raw);assert.equal(comment.version,7);assert.equal(comment.rootId,1);assert.equal(comment.isLiked,true);assert.equal(comment.isFollowTheUser,undefined);
});
test('final page cursor must still advance and cannot repeat the previous page',async()=>{const h=harness();h.client.feed({query:{cursor:59},success:r=>h.results.push(r)});await tick();h.calls[0].success({code:200,data:{items:[bundle()],hasMore:false,nextCursor:59}});await tick();assert.equal(h.results[0].code,502)});
test('interaction with missing viewer state or count is unknown and cannot be accepted as zero',async()=>{for(const field of ['viewerLiked','likeCount']){const h=harness();h.invoke('addPostAction',{postId:59,command:{actionType:'LIKE',requestId:'synthetic-request-1'}});await tick();const data=bundle();delete data.post[field];h.calls[0].success({code:200,data});await tick();assert.equal(h.results[0].code,502);assert.equal(h.results[0].unknown,true)}});
