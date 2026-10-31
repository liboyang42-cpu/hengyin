'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
const { load, post, comment, tick, root } = require('../helpers/community-square-vm');
function actions() {
  const file = path.join(root, 'pages/square/components/cy/post-actions/index.js'); let definition;
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), { Component: value => { definition = value; }, require: createRequire(file) });
  const events = [], opened = [], instance = { ...definition.methods, data: {postId:'77',summary:'原目标'},
    selectComponent:()=>({open:(...args)=>opened.push(args),busyOn(){}}), triggerEvent(name, detail) {
      events.push([name, detail]); if(name==='close') instance.data.postId='88';
    } };
  return {instance,events,opened};
}
test('post menu captures ID before close mutates its bound properties, including delayed destructive confirmation', () => {
  const h=actions();h.instance.onEdit();assert.equal(h.events[1][1].id,'77');
  h.instance.data.postId='77';h.instance.onDelete();assert.equal(h.opened[0][1].id,'77');
  h.instance.data.postId='99';h.instance.onDeleteConfirmed();assert.equal(h.events.at(-1)[1].id,'77');
});
// 2026-10-29:广场列表页(pages/square/list)已删,列表侧的待删行目标用例随页撤走。
test('detail cannot dispatch an old action after route/owner changes or a mismatched event ID', async () => {
  for(const transition of ['id','owner','route','unload']) {
    const h=load('detail');await h.ready(post(),[comment()]);h.page.openPostActions();const before=h.requests.length;
    if(transition==='owner')h.changeOwner();if(transition==='route')h.page.data.id=88;if(transition==='unload')h.page._unloaded=true;
    h.page.onPostAction({detail:{action:'delete',id:transition==='id'?'88':'77'}});await tick();
    assert.equal(h.requests.length,before);
  }
});
test('pending comment report hides only captured comment ID after list reordering', async () => {
  const h=load('detail');await h.ready(post(),[comment(501),comment(502,2)]);
  h.page.reportComment({currentTarget:{dataset:{index:0}}});h.modals[0].success({confirm:true});await tick();
  await h.answer('/api/v1/community/capabilities',{reporting:{policyVersion:'p1',reasons:[{code:'SPAM',label:'垃圾广告'}]}});
  h.page.onReportReasonSelect({detail:{index:0}});await tick();
  const request=h.asked('/api/v1/community/reports')[0];assert.ok(request);
  h.page.data.list=[h.page.data.list[1],h.page.data.list[0],{id:503}];
  await h.answer('/api/v1/community/reports',{id:1,...request.data});
  assert.deepEqual(h.page.data.list.map(row=>row.id),[502,503]);
});
