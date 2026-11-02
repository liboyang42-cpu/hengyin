// usage: node shots.mjs <phase> <outdir> [scene...]
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
import fs from 'fs'; import path from 'path';
const [phase, out, ...only] = process.argv.slice(2);
fs.mkdirSync(out, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function open(opts) {
  const w = await launch(Object.assign({ agree: true }, opts));
  const st = { delay: 0, fail: [] };
  await w.page.setRequestInterception(true);
  w.page.on('request', (r) => {
    const u = r.url();
    if (u.includes('/__api/')) {
      if (st.fail.some((f) => u.includes(f))) return r.respond({ status: 500, contentType: 'application/json', body: '{"code":500,"msg":"rig injected"}' });
      if (st.delay) return setTimeout(() => r.continue().catch(() => {}), st.delay);
    }
    r.continue().catch(() => {});
  });
  w.st = st;
  w.call = (js) => w.page.evaluate(`(function(){ const p = getCurrentPages().slice(-1)[0]; ${js} })()`);
  w.shot = async (name) => { const f = path.join(out, `${name}-${phase}.png`); await w.page.screenshot({ path: f }); console.log('shot', f); };
  w.text = () => w.page.evaluate(() => document.body.innerText.slice(0, 600));
  return w;
}
const scenes = {
  async roampeek() { // R-01
    const w = await open({ path: 'pages/index/index', identity: 'e2e-openid-9004', settle: 2500 });
    w.st.delay = 3000;
    await w.page.evaluate(() => wx.switchTab({ url: '/pages/roam/index' }));
    await sleep(1200); await w.shot('r01-roam-peek-loading');
    w.st.delay = 0; await sleep(9000); await w.shot('r01-roam-peek-settled');
    await w.close();
  },
  async ppfirst() { // R-02 first load
    const w = await open({ path: 'pages/index/index', identity: 'e2e-openid-9004', settle: 2500 });
    w.st.delay = 3000;
    await w.page.evaluate(() => wx.switchTab({ url: '/pages/roam/index' })); await sleep(300);
    await w.call(`p.switchIntroTab({ detail: { key: 'passport' } })`);
    await sleep(900); await w.shot('r02-passport-first-load');
    await w.close();
  },
  async passport() { // R-02
    const w = await open({ path: 'pages/roam/index', identity: 'e2e-openid-9004', settle: 4000 });
    w.st.delay = 2500;
    await w.call(`p.switchIntroTab({ detail: { key: 'passport' } })`);
    await sleep(800);
    w.st.delay = 0; await sleep(3500); await w.shot('r02-passport-loaded');
    w.st.delay = 2500;
    await w.page.evaluate(() => wx.switchTab({ url: '/pages/index/index' })); await sleep(1500);
    await w.page.evaluate(() => wx.switchTab({ url: '/pages/roam/index' })); await sleep(800);
    await w.shot('r02-passport-return');
    await w.close();
  },
  async clubs() { // C-01
    const w = await open({ path: 'pages/talent/list/index', identity: 'e2e-openid-9003', settle: 3000 });
    await w.call(`p.onTopTabChange({ detail: { key: 'club' } })`); await sleep(3000);
    await w.shot('c01-clubs-loaded');
    w.st.delay = 2500;
    await w.page.evaluate(() => wx.switchTab({ url: '/pages/index/index' })); await sleep(1500);
    await w.page.evaluate(() => wx.switchTab({ url: '/pages/talent/list/index' })); await sleep(700);
    await w.shot('c01-clubs-return');
    await w.close();
  },
  async nearby() { // N-01
    const w = await open({ path: 'pages/roam/index', identity: 'e2e-openid-9004', settle: 2500 });
    w.st.delay = 4000;
    await w.page.evaluate(() => wx.navigateTo({ url: '/subpackageRoam/nearby/index' }));
    await sleep(1500); await w.shot('n01-nearby-map-loading');
    await w.call(`p.setData({ viewMode: 'list' })`); await sleep(400); await w.shot('n01-nearby-list-loading');
    await w.close();
  },
  async myteams() { // MT-01 / MT-02
    const w = await open({ path: 'pages/roam/index', identity: 'e2e-openid-9004', settle: 2500 });
    w.st.delay = 3000;
    await w.page.evaluate(() => wx.navigateTo({ url: '/subpackageRoam/my-teams/index' }));
    await sleep(1200); await w.shot('mt01-my-teams-loading');
    w.st.delay = 0; w.st.fail = ['/api/team/my-applications'];
    await sleep(4000); await w.call(`p.reload()`); await sleep(2000); await w.shot('mt02-my-teams-partial-failure');
    await w.close();
  },
  async home() { // H-03
    const w = await open({ path: 'pages/index/index', identity: 'e2e-openid-9004', settle: 4000 });
    try { await w.tap('我已阅读并同意'); await w.tap('同意并继续'); } catch (e) { console.log('gate', e.message); }
    await sleep(800); try { await w.tap('暂不定位'); } catch (e) { console.log('city', e.message); }
    await sleep(3000);
    console.log('feed0', await w.call(`return [p.data.bottomTopicList.length, p.data.bottomActivityList.length, p.data.activeTab]`));
    try { await w.tap('我已阅读并同意'); await w.tap('同意并继续'); await sleep(800); } catch (e) { console.log('gate2', e.message); }
    try { await w.tap('暂不定位'); await sleep(600); } catch (e) { console.log('city2', e.message); }
    await w.scroll(1400); await sleep(1500);
    await w.shot('h03-home-before-refresh');
    w.st.delay = 2500;
    await w.call(`p.onRefresh()`); await sleep(500); try { await w.tap('暂不定位'); } catch (e) { console.log('city3', e.message); } await sleep(500); await w.shot('h03-home-during-refresh');
    console.log('feed', await w.call(`return [p.data.bottomTopicList.length, p.data.bottomActivityList.length, p.data.activeTab]`));
    await w.close();
  },
};
for (const [k, fn] of Object.entries(scenes)) if (!only.length || only.includes(k)) { try { await fn(); } catch (e) { console.log('scene', k, 'ERR', e.message); } }
