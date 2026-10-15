const test=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
function mount(kind){
  let owner='7',definition;const requests=[];
  const app={getUserID:()=>owner,getPageSize:()=>10,getRequestErrorMessage:(r,f)=>r&&r.msg||f,
    sendRequest:o=>{requests.push(o);},firstLogin:()=>{owner='9';return Promise.resolve();}};
  global.getApp=()=>app;global.wx={getStorageSync:()=>'',showToast(){},navigateTo(){},getWindowInfo:()=>({statusBarHeight:20})};
  global.Component=d=>definition=d;global.Page=d=>definition=d;
  const rel=kind==='order'?'subpackageMember/components/cy/scene-member-order-history/index.js':kind==='earnings'?'components/cy/scene-asset-earnings/index.js':kind==='board'?'subpackageP3/pages/growthcenter/leaderboard/index.js':kind==='growth'?'subpackageP3/pages/growthcenter/index/index.js':kind==='income'?'components/cy/scene-asset-income-detail/index.js':kind==='withdraw'?'components/cy/scene-member-withdraw-history/index.js':kind==='invite'?'components/cy/scene-member-invite-history/index.js':kind==='profit'?'components/cy/scene-merchant-profit/index.js':'subpackageP3/pages/badge-wall/index/index.js';
  const file=path.resolve(__dirname,'../..',rel);delete require.cache[file];require(file);
  const page=Object.assign({},definition,definition.methods,{data:JSON.parse(JSON.stringify(definition.data)),setData(patch,done){Object.assign(this.data,patch);if(done)done();}});
  if(kind==='badge')page.data.viewMode='list';
  return {page,app,requests,owner(id){owner=id;},load(){kind==='order'?page.getList(false,true):kind==='earnings'?page.load(true):kind==='board'?page.loadBoard():kind==='growth'?(page.loadOverview(),page.loadMyRank()):kind==='income'||kind==='withdraw'?page.getList():kind==='invite'||kind==='profit'?page.load(true):page.loadData();}};
}
const flush=()=>new Promise(resolve=>setImmediate(resolve));
for(const kind of ['order','badge']){
  const auth=kind==='order'?'orderAuthRequired':'wallAuthRequired';const deny=kind==='order'?'orderAccessDenied':'wallAccessDenied';
  test(kind+' anonymous private read shows login and avoids authenticated APIs until login succeeds',async()=>{
    const h=mount(kind);h.owner('');h.load();assert.equal(h.requests.length,0);assert.equal(h.page.data[auth],true);
    let logins=0;h.app.firstLogin=()=>{logins++;h.owner('9');return Promise.resolve();};
    const retry=kind==='order'?'retryList':'retryWall';h.page[retry]();h.page[retry]();await flush();
    assert.equal(logins,1);assert.equal(h.requests.length,kind==='order'?1:2);
  });
  test(kind+' actual401/403 remain distinct visible prompts; network remains read-failure',async()=>{
    for(const code of [401,403,500]){
      const h=mount(kind);h.load();for(const r of h.requests)r.success({code,msg:'真实响应'});await flush();
      assert.equal(h.page.data[auth],code===401);assert.equal(h.page.data[deny],code===403);
    }
    const h=mount(kind);h.load();for(const r of h.requests)r.fail({errMsg:'offline'});await flush();
    assert.equal(h.page.data[auth],false);assert.equal(h.page.data[deny],false);
  });
  test(kind+' old-account success cannot expose private content after account changes',async()=>{
    const h=mount(kind);h.load();h.owner('99');
    if(kind==='order')h.requests[0].success({code:200,data:{rows:[{id:12,registrationStatus:1}]}});
    else {h.requests[0].success({code:200,data:{medals:[]}});h.requests[1].success({code:200,data:{identity:[{badgeCode:'FIRST_STEP',badgeName:'旧账号',unlocked:true}]}});}
    await flush();assert.equal((h.page.data.list||h.page.data.badges).length,0);
  });
}

for(const kind of ['earnings','board']){
  test(kind+' anonymous login entry only reads current private state after explicit login',async()=>{
    const h=mount(kind);h.owner('');h.load();assert.equal(h.requests.length,0);
    assert.equal(kind==='earnings'?h.page.data.state:h.page.data.boardAuthRequired,kind==='earnings'?'login':true);
    const retry=kind==='earnings'?'refresh':'onRetry';h.page[retry]();h.page[retry]();await flush();
    assert.equal(h.requests.length,1);
  });
  test(kind+' HTTP 401/403 stay visible while HTTP 500 remains generic read-failure',()=>{
    for(const code of [401,403,500]){
      const h=mount(kind);h.load();h.requests[0].successStatusAbnormal({statusCode:code,msg:'真实HTTP错误'});
      if(kind==='earnings')assert.equal(h.page.data.state,code===401?'login':code===403?'permission':'error');
      else {assert.equal(h.page.data.boardAuthRequired,code===401);assert.equal(h.page.data.boardAccessDenied,code===403);}
    }
  });
}
test('order/badge HTTP500 is ordinary read-failure rather than a fabricated login denial',async()=>{
  for(const kind of ['order','badge']){
    const h=mount(kind);h.load();for(const r of h.requests)r.successStatusAbnormal({statusCode:500,msg:'服务不可用'});await flush();
    assert.equal(h.page.data[kind==='order'?'orderAuthRequired':'wallAuthRequired'],false);
    assert.equal(h.page.data[kind==='order'?'orderAccessDenied':'wallAccessDenied'],false);
  }
});

test('growth anonymous overview/rank issue no private reads, explicit login then reads four real sources',async()=>{
  const h=mount('growth');h.owner('');h.load();assert.equal(h.requests.length,0);assert.equal(h.page.data.growthAuthRequired,true);
  h.page.onLoginGrowth();h.page.onLoginGrowth();await flush();assert.equal(h.requests.length,4);
  assert.equal(h.requests.filter(r=>r.url==='/api/growth/leaderboard').length,1);
});
test('growth actual401/403 stay visible; current three ordinary500 responses do not pretend login failure',async()=>{
  for(const code of [401,403,500]){
    const h=mount('growth');h.page.loadOverview();h.requests.forEach(r=>r.success({code,msg:'真实响应'}));await flush();
    assert.equal(h.page.data.growthAuthRequired,code===401);assert.equal(h.page.data.growthAccessDenied,code===403);
  }
});
test('growth account switch clears private badges before reread and rejects old successful snapshots',async()=>{
  const h=mount('growth');h.page.loadOverview();h.page.data.badges=[{code:'PRIVATE_OLD'}];h.page.data.showBadges=true;
  h.owner('99');h.page.onShow();assert.equal(h.page.data.badges.length,0);assert.equal(h.page.data.me,null);
  for(const r of h.requests.slice(0,3))r.success({code:200,data:{badges:[{badgeCode:'PRIVATE_OLD'}]}});await flush();assert.equal(h.page.data.badges.length,0);
});

for(const kind of ['income','withdraw','invite','profit']){
  test(kind+' private history uses explicit login rather than sending anonymous reads',async()=>{
    const h=mount(kind);h.owner('');h.load();assert.equal(h.requests.length,0);assert.equal(h.page.data.authRequired,true);
    const retry=kind==='invite'?'retry':'onRetry';h.page[retry]();h.page[retry]();await flush();assert.equal(h.requests.length,kind==='invite'?2:1);
  });
  test(kind+' actual401/403 have visible auth/permission feedback; ordinary500 remains read-failure',async()=>{
    for(const code of [401,403,500]){
      const h=mount(kind);h.load();h.requests.forEach(r=>r.successStatusAbnormal({statusCode:code},code));await flush();
      assert.equal(h.page.data.authRequired,code===401);assert.equal(h.page.data.accessDenied,code===403);
    }
  });
  test(kind+' account-change foreground clears old private cache and rejects old callbacks',async()=>{
    const h=mount(kind);h.load();if(kind==='invite')h.page.data.groups=[{label:'OLD'}];else if(kind==='profit')h.page.data.topics=[{topicId:8}];else h.page.data.list=[{id:8}];
    h.owner('');h.page.pageLifetimes.show.call(h.page);assert.equal((h.page.data.list||h.page.data.groups||h.page.data.topics).length,0);
    h.requests.forEach(r=>r.success({code:200,data:{rows:[{id:8}],total:1}}));await flush();assert.equal((h.page.data.list||h.page.data.groups||h.page.data.topics).length,0);
  });
}

for(const kind of ['income','withdraw','invite','profit']) {
  test(kind+' waits for existing bootstrap session without starting a second login or anonymous query',async()=>{
    const h=mount(kind);h.owner('');h.app.isAppReady=false;let ready;let waits=0;
    h.app.waitForAppReady=()=>{waits++;return new Promise(resolve=>ready=resolve);};
    h.app.firstLogin=()=>{throw new Error('unexpected duplicate login');};h.load();h.load();
    assert.equal(h.requests.length,0);assert.equal(waits,1);h.owner('9');ready();await flush();
    assert.equal(h.requests.length,kind==='invite'?2:1);assert.equal(h.page.data.authRequired,false);
  });
  test(kind+' detached bootstrap completion cannot issue a private query',async()=>{
    const h=mount(kind);h.owner('');h.app.isAppReady=false;let ready;
    h.app.waitForAppReady=()=>new Promise(resolve=>ready=resolve);h.load();h.page._detached=true;h.owner('9');ready();await flush();assert.equal(h.requests.length,0);
  });
}
