'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { createRequire } = require('node:module');
function mount(name, initialUid = '') {
  const file = path.resolve(__dirname, `../../subpackageP3/pages/${name}/index/index.js`);
  const localRequire = createRequire(file);
  let definition, uid = initialUid, logins = 0;
  const requests = [];
  const app = { globalData: { authorization: 'one' }, getUserID: () => uid,
    sendRequest: r => requests.push(r), firstLogin: () => { logins++; uid = 7; return Promise.resolve({ok: true}); },
    reLogin: () => { logins++; return Promise.resolve({ok: true}); } };
  vm.runInNewContext(fs.readFileSync(file, 'utf8'), {
    getApp: () => app, Page: p => { definition = p; }, require: localRequire,
    wx: { getWindowInfo: () => ({windowWidth: 390}), navigateTo() {} }
  });
  const page = Object.assign({}, definition, { data: JSON.parse(JSON.stringify(definition.data)),
    setData(p, cb) {Object.assign(this.data,p); if(cb) cb();} });
  page.onLoad();
  const reply = (r, id) => r.success({code:200,data:{total:1,list:[{id,picUrl:'photo',title:'卡'}]}});
  return { page, requests, reply, app, switchUid: value => {uid=value;}, loginCount: () => logins };
}
for (const name of ['stamp-album','object-cards']) {
  test(`${name}: guest has login action, no private request or fabricated empty`, async () => {
    const h=mount(name); assert.equal(h.requests.length,0); assert.equal(h.page.data.readAccess,'login');
    assert.equal(h.page.data.loaded,false); await h.page.loginToRead(); assert.equal(h.loginCount(),1);
    assert.equal(h.requests.length,1); h.reply(h.requests[0],1); assert.equal(h.page.data.loaded,true);
  });
  test(`${name}: true 401/403 clear private content; 500 remains ordinary failure`, () => {
    for (const callback of ['success','fail','successStatusAbnormal']) {
      for (const code of [401,403,500]) {
        const h=mount(name,7); h.requests[0][callback]({code}, callback==='success'?undefined:code);
        assert.equal(h.page.data.readAccess,code===401?'login':code===403?'denied':'');
        assert.equal(h.page.data.error,code===500); assert.equal(h.page.data.loaded,false);
        assert.equal(h.page.data.loading,false);
      }
    }
  });
  test(`${name}: switching account clears prior content and rejects all old callbacks`, () => {
    const h=mount(name,7); h.reply(h.requests[0],1); assert.equal(h.page.data.loaded,true);
    h.switchUid(8); h.page.onShow(); assert.equal(h.page.data.loaded,false);
    assert.equal((h.page.data.items || h.page.data.cards).length,0);
    const next=h.requests[1]; h.reply(h.requests[0],99); h.requests[0].fail({code:401});
    h.requests[0].successStatusAbnormal({code:403},403); assert.equal(h.page.data.readAccess,'');
    h.reply(next,2); assert.equal((h.page.data.items || h.page.data.cards)[0].id,2);
  });
  test(`${name}: login finishing after unload cannot request the retired page`, async () => {
    const h=mount(name); let resolve;
    h.app.firstLogin=()=>new Promise(r=>{resolve=r;}); const pending=h.page.loginToRead();
    h.page.onUnload();h.switchUid(7);resolve({ok:true});await pending;assert.equal(h.requests.length,0);
  });
}
