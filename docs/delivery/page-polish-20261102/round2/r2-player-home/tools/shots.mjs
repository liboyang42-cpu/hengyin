// usage: node shots.mjs <phase> <outdir> [scene...]
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import fs from 'fs'; import path from 'path';
const [phase, out, ...only] = process.argv.slice(2);
fs.mkdirSync(out, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const EMPTY = '{"code":200,"msg":"ok","data":{"rows":[],"total":0}}';
async function open(opts) {
  const w = await launch(Object.assign({ agree: true }, opts));
  const st = { delay: 0, fail: [], empty: [] };
  await w.page.setRequestInterception(true);
  w.page.on('request', (r) => {
    const u = r.url();
    if (u.includes('/__api/')) {
      if (st.fail.some((f) => u.includes(f))) return r.respond({ status: 500, contentType: 'application/json', body: '{"code":500,"msg":"rig injected"}' });
      if (st.empty.some((f) => u.includes(f))) return setTimeout(() => r.respond({ status: 200, contentType: 'application/json', body: EMPTY }).catch(() => {}), st.delay);
      if (st.delay) return setTimeout(() => r.continue().catch(() => {}), st.delay);
    }
    r.continue().catch(() => {});
  });
  w.st = st;
  w.prep = async () => {
    try { await w.tap('暂不定位'); await sleep(800); } catch (e) {}
    for (let k = 0; k < 2; k++) { try { if (await w.eval(`(()=>{let f=false;(function walk(r){for(const e of r.querySelectorAll('*')){if(e.shadowRoot)walk(e.shadowRoot);for(const n of e.childNodes)if(n.nodeType===3&&n.data.includes('同意并继续')&&e.getBoundingClientRect().width>0)f=true;}})(document);return f})()`)) { await w.tap('我已阅读并同意'); await w.tap('同意并继续'); await sleep(1500); } } catch (e) {} }
    try { await w.tap('暂不定位'); await sleep(500); } catch (e) {}
  };
  w.call = (js) => w.page.evaluate(`(function(){ const p = getCurrentPages().slice(-1)[0]; ${js} })()`);
  w.shot = async (name) => { await w.eval(`(()=>{function inj(r){const t=document.createElement('style');t.textContent='wx-scroll-view{position:relative}';(r.head||r).appendChild(t);for(const e of r.querySelectorAll('*'))if(e.shadowRoot)inj(e.shadowRoot);}inj(document);})()`).catch(()=>{}); const f = path.join(out, `${name}-${phase}.png`); await w.page.screenshot({ path: f }); console.log('shot', f); };
  // main scroller of the current page
  w.sc = (js) => w.page.evaluate(`(function(){ const P=getCurrentPages().slice(-1)[0]; let sc=null,a=0; (function walk(root){for(const el of root.querySelectorAll('*')){if(el.shadowRoot)walk(el.shadowRoot); if(el.scrollHeight>el.clientHeight+4&&/auto|scroll/.test(getComputedStyle(el).overflowY)){const r=el.getBoundingClientRect(); if(r.width*r.height>a&&r.width>300){a=r.width*r.height;sc=el;}}}})(P.__root||document); if(!sc) return null; ${js} })()`);
  return w;
}
async function dismiss(w) { await w.prep(); }
const scenes = {
  async h03b() { // home refresh: page shape
    const w = await open({ path: 'pages/index/index', identity: 'e2e-openid-9009', settle: 6000 });
    await dismiss(w); await sleep(1500);
    await w.sc(`sc.scrollTop = 0; return 1;`);
    const h0 = await w.sc(`return sc.scrollHeight`);
    await w.shot('h03b-home-settled');
    w.st.delay = 2500;
    await w.call(`p.onRefresh()`);
    const seq = []; const t0 = Date.now(); let shot = false;
    while (Date.now() - t0 < 5000) { seq.push(await w.sc(`return sc.scrollHeight`)); if (!shot && Date.now() - t0 > 600) { shot = true; await w.shot('h03b-home-refreshing'); } await sleep(50); }
    console.log('H03B', phase, JSON.stringify({ before: h0, distinct: [...new Set(seq)] }));
    await w.close();
  },
  async h04() { // feed first load + feed empty
    const w = await open({ path: 'pages/index/index', identity: 'e2e-openid-9009', settle: 6000 });
    await dismiss(w);
    w.st.delay = 4000;
    await w.page.evaluate(() => wx.reLaunch({ url: '/pages/index/index' })); await sleep(1500);
    await dismiss(w);
    await w.sc(`const f=(function find(root){for(const el of root.querySelectorAll('*')){if(el.id==='sec-feed')return el; if(el.shadowRoot){const x=find(el.shadowRoot); if(x) return x;}} return null;})(document); if(f) f.scrollIntoView(); return 1;`);
    await sleep(300); await w.shot('h04-feed-first-load');
    w.st.delay = 300; w.st.empty = ['/api/topic/list'];
    await sleep(6000);
    await w.page.evaluate(() => wx.reLaunch({ url: '/pages/index/index' })); await sleep(4000); await dismiss(w); await sleep(1000);
    await w.sc(`const f=(function find(root){for(const el of root.querySelectorAll('*')){if(el.id==='sec-feed')return el; if(el.shadowRoot){const x=find(el.shadowRoot); if(x) return x;}} return null;})(document); if(f) f.scrollIntoView(); return 1;`);
    await sleep(500); await w.shot('h04-feed-empty');
    await w.close();
  },
  async homefull() { // home with real content in every section (page data from the rig's own records)
    const w = await open({ path: 'pages/index/index', identity: 'e2e-openid-9009', settle: 6000 });
    await dismiss(w); await sleep(1500);
    const n = await w.call(`const t=p.data.bottomTopicList, a=p.data.bottomActivityList; const reco=t.slice(0,3).map((x,i)=>Object.assign({},x,{id:x.id+'-'+i})); const act=(a.length?a:t).slice(0,1); const near=[0,1,2].map(i=>Object.assign({},act[0],{id:'n'+i,distance:'1.2'+i+'km'})); const up=[0,1,2].map(i=>Object.assign({},act[0],{id:'u'+i})); p.setData({recommendedTopicList:reco,nearbyActivityList:near,upcomingActivityList:up,'listLoading.recommendedTopicList':false,'listLoading.nearbyActivityList':false,'listLoading.upcomingActivityList':false,'listErr.recommendedTopicList':false,'listErr.nearbyActivityList':false,'listErr.upcomingActivityList':false}, ()=>{p._buildRecoHero(); p._buildHeroBanners && p._buildHeroBanners();}); return [t.length,a.length];`);
    console.log('feed lens', n); await sleep(1500);
    for (const [i, y] of [[0, 0], [1, 700], [2, 1400], [3, 2100]]) { await w.sc(`sc.scrollTop=${y}; return 1;`); await sleep(500); await w.shot(`home-full-${i}`); }
    // sync row during refresh with content on screen
    await w.sc(`sc.scrollTop=420; return 1;`); await sleep(300);
    await w.call(`p.setData({'listLoading.nearbyActivityList':true,'listLoading.recommendedTopicList':true})`); await sleep(500);
    await w.shot('home-full-syncing');
    await w.close();
  },
  async c03() { // club empty state
    const w = await open({ path: 'pages/talent/list/index', identity: 'e2e-openid-9004', settle: 4000 });
    await w.call(`p.onTopTabChange({ detail: { key: 'club' } })`); await sleep(3000);
    await w.call(`p.setData({ myClubs: [], joinedClubs: [], nearbyClubs: [], clubsLoaded: true, clubsError: false, clubsState: 'ready' })`); await sleep(800);
    await w.shot('c03-clubs-empty');
    await w.close();
  },
  async feedrefresh() { // 帖文 refreshing row
    const w = await open({ path: 'pages/talent/list/index', identity: 'e2e-openid-9004', settle: 4000 });
    await w.call(`p.onTopTabChange({ detail: { key: 'feed' } })`); await sleep(4000);
    const len = await w.call(`return p.data.feed.length + ' ' + p.data.feedState`); console.log('feed', len);
    await w.call(`p.setData({ feedState: 'refreshing' })`); await sleep(600);
    await w.shot('feed-refreshing');
    await w.close();
  },
  async myteams() { // MT-04 loading, MT-06 error
    const w = await open({ path: 'pages/roam/index', identity: 'e2e-openid-9004', settle: 2500 });
    w.st.delay = 3000;
    await w.page.evaluate(() => wx.navigateTo({ url: '/subpackageRoam/my-teams/index' }));
    await sleep(300); console.log('mt', JSON.stringify(await w.call(`return [p.route, p.data.loading, (p.data.groups||[]).length]`))); await w.shot('mt04-my-teams-loading');
    w.st.delay = 0; w.st.fail = ['/api/team/my?', '/api/team/my"', '/api/team/my'];
    await sleep(4000); await w.call(`p.reload()`); await sleep(2500); await w.shot('mt06-my-teams-error');
    await w.close();
  },
  async nearbymine() { // N-09
    const w = await open({ path: 'pages/roam/index', identity: 'e2e-openid-9004', settle: 2500 });
    await sleep(1000);
    await w.page.evaluate(() => wx.navigateTo({ url: '/subpackageRoam/nearby/index' })); await sleep(4000);
    w.st.delay = 4000; await w.call(`p.openMyTeams()`);
    for (let i=0;i<8;i++){ await sleep(500); const d = await w.call(`return [p.route, p.data.sheet, p.data.myLoading, p.data.sheetIn]`); console.log('n09', JSON.stringify(d)); if (d[1]==='mine' && d[2]) break; } await sleep(400); await w.shot('n09-nearby-mine-loading');
    await w.close();
  },
};
for (const k of (only.length ? only : Object.keys(scenes))) { try { await scenes[k](); } catch (e) { console.log('SCENE FAIL', k, e.message); } }
process.exit(0);
