/** Compose uses real canonical transport; edit preserves full source relations and never touches the new-post draft. */
const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const {mount,bundle,reply,request,flush,editOpen,ROOT,COMPOSE}=require('../fixtures/community-caller-harness.cjs');
const read=()=>fs.readFileSync(path.join(ROOT,COMPOSE),'utf8');
async function saved(h, original, body) {
 h.instance.onInput({detail:{value:body}});h.instance.onPublish();await flush();reply(request(h,'/posts/77'),original);await flush();
 const patch=request(h,'/posts/77');assert.equal(patch.method,'PATCH');
 reply(patch,{...original,post:{...original.post,body,version:original.post.version+1}});await flush();return patch;
}
async function created(h) {
 h.instance.onPublish();await flush();reply(request(h,'/guidelines/active'),{id:1,contentJson:{rules:[{title:'规则',content:'真实规则'}]}});await flush();
 h.modals.at(-1).success({confirm:true});await flush();reply(request(h,'/guidelines/ack'),{});await flush();
 const create=h.requests.findLast(r=>r.url==='/api/v1/community/posts');assert.ok(create);const original=bundle(77,{...create.data,id:77,authorId:1,version:0,lifecycle:'DRAFT'});
 reply(create,original);await flush();const publish=request(h,'/publish');assert.ok(publish);reply(publish,{...original,post:{...original.post,version:1,lifecycle:'CHECKING'}});await flush();return create;
}

test('渲染层编辑态标题、提交键、草稿与附件边界保持；负控缺条件为红',()=>{
 const w=fs.readFileSync(path.join(ROOT,COMPOSE.replace('.js','.wxml')),'utf8');
 const check=s=>{assert.match(s,/cmp-bar-title">\{\{editMode \? '编辑帖文' : '新建帖文'\}\}/);assert.match(s,/wx:if="\{\{!editMode\}\}" bindtap="onOpenDrafts"/);assert.match(s,/class="cmp-tools" wx:if="\{\{!editMode\}\}"/);assert.match(s,/aria-label="\{\{editMode \? '保存' : '发布'\}\}"/)};
 check(w);const bad=w.replace('class="cmp-tools" wx:if="{{!editMode}}"','class="cmp-tools"');assert.notEqual(bad,w);assert.throws(()=>check(bad),assert.AssertionError);
});

test('编辑按真实id/版本保存正文，媒体关系保持且不碰新建草稿；清媒体负控为红',async()=>{
 const original=bundle();original.media=[{id:9,mediaType:'IMAGE',objectKey:'https://images.invalid/owned'}];original.references=[{referenceType:'ACTIVITY',referenceId:9}];original.topicCodes=['CITY'];original.mentions=[2];
 const h=mount(COMPOSE);await editOpen(h,original);const patch=await saved(h,original,'changed');
 assert.equal(patch.url,'/api/v1/community/posts/77');assert.equal(patch.data.expectedVersion,2);assert.equal(patch.data.body,'changed');assert.deepEqual(Array.from(patch.data.mediaIds),[9]);assert.equal(patch.data.references[0].referenceId,9);assert.deepEqual(Array.from(patch.data.topicCodes),['CITY']);assert.deepEqual(Array.from(patch.data.mentionedMemberIds),[2]);assert.equal(h.draftCalls.length,0);assert.equal(h.events.at(-1).name,'close');
 const bad=read().replace('changes: { body: content }','changes: { body: content, mediaPaths: [] }');assert.notEqual(bad,read());const b=mount(COMPOSE,{source:bad});await editOpen(b,original);b.instance.onInput({detail:{value:'changed'}});b.instance.onPublish();await flush();reply(request(b,'/posts/77'),original);await flush();const removed=request(b,'/posts/77');assert.deepEqual(Array.from(removed.data.mediaIds),[]);assert.throws(()=>assert.deepEqual(Array.from(removed.data.mediaIds),[9]),assert.AssertionError);
});

test('编辑态关闭不读写新建草稿槽位',async()=>{const h=mount(COMPOSE);await editOpen(h,bundle());h.definition.observers.show.call(h.instance,false);assert.equal(h.draftCalls.length,0)});

test('新建prefill保留，实际规范确认后草稿创建带稳定clientRequestId且无原帖id',async()=>{
 const h=mount(COMPOSE,{props:{prefill:'半截草稿'}});h.definition.observers.show.call(h.instance,true);assert.equal(h.instance.data.editMode,false);assert.equal(h.instance.data.content,'半截草稿');const create=await created(h);assert.equal('id' in create.data,false);assert.ok(create.data.clientRequestId);assert.equal(create.data.body,'半截草稿');assert.equal(h.events.find(e=>e.name==='published').detail.lifecycle,'CHECKING');
});

test('同宿主发过一帖后再写新帖，关闭仍存草稿',async()=>{
 const h=mount(COMPOSE,{props:{prefill:'第一篇'}});h.definition.observers.show.call(h.instance,true);await created(h);h.definition.observers.show.call(h.instance,false);h.instance.data.prefill='';h.definition.observers.show.call(h.instance,true);h.instance.applyContent('第二篇没发');h.definition.observers.show.call(h.instance,false);const saves=h.draftCalls.filter(c=>c[0]==='saveDraft');assert.equal(saves.length,1);assert.equal(saves[0][2].formData.contents,'第二篇没发');
});
