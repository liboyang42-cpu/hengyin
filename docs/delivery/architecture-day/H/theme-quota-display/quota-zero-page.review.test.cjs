'use strict';
// Narrow Page-only observation. Backend quota/role transitions are not simulated or proved here.
const { test } = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');
const root = process.env.H_QUOTA_PAGE_ROOT;
if (!root) throw Error('H_QUOTA_PAGE_ROOT must point at the verified frozen xcx source');
test('limited remaining=0 prevents Simple Page from dispatching a new draft', () => {
  let config; const requests = []; const storage = Object.create(null);
  global.getApp = () => ({ getUserID: () => '7', getAuthorization: () => 'local-test-session',
    globalData: {}, sendRequest: r => requests.push(r) });
  global.wx = { getStorageSync:k=>storage[k]||'',setStorageSync:(k,v)=>{storage[k]=v;},
    removeStorageSync:k=>{delete storage[k];},switchTab(){},navigateTo(){},navigateBack(){} };
  global.Page = value => {config=value;};
  const file=path.resolve(root,'pages/publish/simple/index.js');delete require.cache[require.resolve(file)];require(file);
  const page=Object.assign({},config,{data:JSON.parse(JSON.stringify(config.data)),
    setData(patch,done){Object.assign(this.data,patch);if(done)done();}});
  page.onLoad({mode:'2'});assert.equal(requests.length,1);
  assert.equal(requests[0].url,'/api/ai/theme/draft/quota');
  requests.shift().success({code:200,data:{limited:true,limit:3,remaining:0}});
  page.setData({aiIdea:'local-route-idea'});page.generateAiPlan();
  assert.equal(page.data.aiQuotaLoaded,true);assert.equal(page.data.aiRemaining,0);
  assert.equal(requests.length,0,'no draft/prepare/execute dispatch after the zero response');
  assert.match(page.data.aiError,/次数用完/);
});
