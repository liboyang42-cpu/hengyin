// node shots.mjs <before|after> <set>   (sets: pre, checkin, roster, running, member-running, ended, member-ended, enroll, exec)
import fs from 'node:fs';
import path from 'node:path';
const HOME = process.env.HOME;
const phase = process.argv[2], set = process.argv[3];
process.env.RIG_SHOTS = path.join(HOME, 'l6-shots', phase);
const { launch } = await import(HOME + '/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const S = JSON.parse(fs.readFileSync(path.join(HOME, 'l6-shots/state.json'), 'utf8'));
const sl = (ms) => new Promise((r) => setTimeout(r, ms));
const LEAD = 'e2e-openid-9008', CLUB = 7002;
const td = `pages/club/topic-detail/index?topicId=${S.topic}&clubId=${CLUB}&activityId=${S.act}`;
const name = (id, suffix = '') => `${id}-${phase}${suffix}`;
async function go(identity, p, fn) {
  const w = await launch({ path: p, identity, agree: true, lat: 31.24272, lng: 121.48839 });
  try { await sl(4500); await fn(w); } catch (e) { console.log('STEP ERROR', e.message); }
  console.log('route', JSON.stringify(await w.route()));
  console.log('errors', JSON.stringify(w.errors()).slice(0, 800));
  await w.close();
}
async function txt(w, l) { console.log('TEXT[' + l + ']', (await w.text()).slice(0, 1500)); }
async function scrollTo(w, text) {
  for (let i = 0; i < 12; i++) { const t = await w.text(); if (t.includes(text)) return true; await w.scroll(500); }
  return false;
}
async function toastNow(w, ms = 250) { await sl(ms); }
const sets = {
  async create() { // C-14: duplicate club name, caught on the last step
    const ty = async (w, ph, text) => { const h = await w.page.$(`pierce/input[placeholder="${ph}"]`) || await w.page.$(`pierce/textarea[placeholder="${ph}"]`); if (!h) throw new Error('no input ' + ph); await h.click({ clickCount: 3 }); await h.type(String(text), { delay: 10 }); await sl(300); };
    await go(LEAD, 'pages/club/create/index', async (w) => {
      await txt(w, 'c0');
      try { await w.tap('开始创建'); await sl(1200); } catch (e) {}
      await w.tap('旅行组织'); await w.tap('下一步', { exact: true }); await sl(900);
      await w.tap('城市定向'); await w.tap('下一步', { exact: true }); await sl(900);
      await ty(w, '给你的俱乐部起个名字', '城瘾跑团');
      await ty(w, '用一句话说清你的俱乐部是做什么的', '每月一次，周末下午走一条上海老街，边走边解谜。');
      await w.tap('下一步', { exact: true }); await sl(1500);
      try { await ty(w, '如 上海', '上海'); await ty(w, '逗号分隔，如 徒步，摄影', '城市漫步，解谜'); await ty(w, '如 沉浸剧情', '轻松慢走'); } catch (e) { console.log('step4', e.message); }
      await w.tap('创建俱乐部', { exact: true }); await sl(2500); await w.shot(name('C-14')); await txt(w, 'dup');
    });
  },
  async pre() {
    await go(LEAD, td, async (w) => {
      await txt(w, 'td'); await scrollTo(w, '节点状态'); await w.shot(name('C-26', '-empty')); await txt(w, 'pre');
    });
  },
  async preready() { // after PREPARE (by API) with no teams: tap 开始活动 → toast/caption
    await go(LEAD, td, async (w) => {
      await scrollTo(w, '开始活动');
      try { await w.tap('开始活动', { exact: true }); } catch (e) { console.log('tap', e.message); }
      await sl(200); await w.shot(name('C-09')); await txt(w, 'c09');
      await sl(2500); await w.scroll(600); await w.shot(name('C-09', '-card')); await txt(w, 'c09card');
    });
  },
  async checkin() {
    await go('walk-player-1', `pages/play/index?activityId=${S.act}&topicId=${S.topic}`, async (w) => {
      await txt(w, 'play'); await w.shot(name('C-03', '-gather'));
      try { await w.tap('我到了'); await sl(800); } catch (e) { console.log('tap', e.message); }
      if (!(await w.text()).includes('定位并签到')) { console.log('INJECT leadArrive (the handler 我到了 binds)'); await w.eval(() => getCurrentPages().slice(-1)[0].leadArrive()); await sl(800); }
      await txt(w, 'modal'); await w.shot(name('C-03', '-modal'));
      try { await w.tap('定位并签到', { exact: true }); } catch (e) { console.log('confirm', e.message); }
      await sl(400); await w.shot(name('C-03')); await txt(w, 'after');
    });
  },
  async roster() {
    await go(LEAD, `pages/club/event-ops/index?clubId=${CLUB}&activityId=${S.act}`, async (w) => {
      await scrollTo(w, '本场名册'); await w.scroll(200); await w.shot(name('C-05')); await txt(w, 'roster');
      try { await w.tap('标记到场', { exact: true }); } catch (e) { console.log('tap', e.message); }
      await sl(600); await w.shot(name('C-06')); await txt(w, 'mark');
    });
  },
  async running() {
    await go(LEAD, td, async (w) => {
      await txt(w, 'r0');
      await scrollTo(w, '排行榜'); await w.scroll(1500); await w.shot(name('C-04')); await txt(w, 'r1');
      const d = await w.data(); console.log('DATA', JSON.stringify({ canUnlockChapter: d.canUnlockChapter, chapterOptions: d.chapterOptions, unlockButtonText: d.unlockButtonText, teams: (d.directorTeamRows || []).map(t => t.value) }));
      try { await w.tap('放行'); await sl(800); await w.shot(name('C-04', '-sheet')); await txt(w, 'sheet'); } catch (e) { console.log('no release control:', e.message); }
      // the 队伍进度 chip sits under the fixed footer in the rig viewport; call the handler it binds (walker did the same)
      console.log('INJECT openTeamSheet'); await w.eval(() => getCurrentPages().slice(-1)[0].openTeamSheet());
      try { await sl(900); await w.shot(name('C-26', '-teams')); await txt(w, 'teams'); } catch (e) { console.log('no team sheet:', e.message); }
    });
  },
  async released() {
    await go(LEAD, td, async (w) => {
      await scrollTo(w, '排行榜'); await w.scroll(1500); await w.shot(name('C-04', '-released')); await txt(w, 'rel');
    });
  },
  async memberrunning() {
    await go('walk-player-1', `pages/play/index?activityId=${S.act}&topicId=${S.topic}`, async (w) => {
      try { await w.tap('开始游戏', { exact: true }); } catch (e) { console.log('tap', e.message); }
      await sl(2500); await w.shot(name('C-10', '-member')); await txt(w, 'm');
    });
    await go(LEAD, `pages/club/event-ops/index?clubId=${CLUB}&activityId=${S.act}`, async (w) => {
      await scrollTo(w, '本场名册'); await w.scroll(200); await w.shot(name('C-10', '-roster')); await txt(w, 'roster');
    });
  },
  async ended() {
    await go(LEAD, td, async (w) => {
      await scrollTo(w, '活动复盘'); await w.shot(name('C-11')); await txt(w, 'e1');
      await w.scroll(400); await w.shot(name('C-11', '-2')); await txt(w, 'e2');
    });
  },
  async memberended() {
    await go('walk-player-1', `pages/play/index?activityId=${S.act}&topicId=${S.topic}`, async (w) => {
      await w.shot(name('C-12', '-cover')); try { await w.tap('开始游戏', { exact: true }); } catch (e) { console.log('tap', e.message); }
      await sl(1500); try { await w.tap('轻点跳过'); } catch (e) {} await sl(1500); try { await w.tap('轻点跳过'); } catch (e) {} await sl(1200);
      try { await w.tap('继续探索', { exact: true }); } catch (e) { console.log('tap', e.message); } await sl(2500);
      try { await w.tap('在地图中查看路线'); } catch (e) { console.log('tap', e.message); } await sl(2500);
      console.log('LEAD', JSON.stringify(await w.data('lead')), 'screen', JSON.stringify(await w.data('screen')));
      await w.shot(name('C-12')); await txt(w, 'm');
    });
  },
  async enroll() {
    await go(LEAD, `pages/club/enroll/index?clubId=${CLUB}&topicId=${S.enrollTopic || S.topic}`, async (w) => {
      await sl(1500); await w.shot(name('C-13')); await txt(w, 'enroll'); await w.scroll(400); await w.shot(name('C-13', '-2'));
    });
  },
};
await sets[set]();
