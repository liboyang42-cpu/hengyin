// 真实小程序(mpweb 走查 rig 运行时)逐帧:node rig-frames.mjs <outDir> <m> <look> <t1,t2,...>
// 与 proto-frames.mjs 同一时间轴:m4 = 点支付(0)→1100ms 到账;m5 = 点发送(0);m6 = 点卡片(0)。
// 用 setData 注入状态(沿用 x-design1 的做法),动画本体走真实页面代码。
import fs from 'node:fs';
const [outDir, m, look, ts] = process.argv.slice(2);
process.env.RIG_WEB = process.env.RIG_WEB || 'http://127.0.0.1:8097';
const { launch } = await import('/tmp/claude-0/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const times = ts.split(',').map(Number);
fs.mkdirSync(outDir, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const COVER = '/pages/topic/images/icon_line.jpg';
const LOOK = { stardust: '星尘', paper: '纸本', survival: '末世', cyber: '夜城' };
const styleName = { stardust: 'STARDUST', paper: 'PAPER', survival: 'SURVIVAL', cyber: 'CYBER' }[look];
const skew = {};
const shot = async (w, t, t00) => { const wait = t - (Date.now() - t00); if (wait > 0) await sleep(wait); const a = Date.now() - t00; await w.page.screenshot({ path: `${outDir}/r-m${m}-${look}-${String(t).padStart(5, '0')}.png` }); skew[t] = a - t; };
let w;
try {
  if (m === '4') {
    w = await launch({ path: 'pages/topic/index/index?id=990014', settle: 3000, agree: true, identity: 'member-1' });
    await w.page.evaluate((cover, style) => { const p = getCurrentPages().slice(-1)[0];
      p.setData({ loadError: false, topicLoaded: true, coverImages: [cover], 'info.name': '星尘便笺', 'info.imgUrl': cover, 'info.look': JSON.stringify({ style }), 'info.selfPlay': 1, 'info.selfPlayPrice': '0.01',
        heroCta: { kind: 'selfplay', text: '¥0.01 随时开玩' }, currentScreen: 1, selfPlayCheckoutShow: true, selfPlayConsentChecked: true,
        checkout: { cover, placeText: '人民广场', view: { pills: ['城市定向'], single: { title: '主题门票', price: '¥0.01', line: '可玩至 2 月 9 日' }, passes: [], bill: [], trust: [], totalText: '¥0.01', payLabel: '微信支付' }, profileName: '本人', profilePhone: '138****0000' } }); }, COVER, styleName);
    await sleep(800);
    await w.page.evaluate(() => { const p = getCurrentPages().slice(-1)[0]; p.setData({ selfPlayCheckoutShow: false }); p.scrollToSecond(); }); await sleep(1800);
    await w.page.evaluate(() => { const p = getCurrentPages().slice(-1)[0]; p.setData({ selfPlayCheckoutShow: true }); }); await sleep(900);
    await w.page.screenshot({ type: 'jpeg', quality: 40 }); const t00 = Date.now();
    await w.page.evaluate(() => { const p = getCurrentPages().slice(-1)[0]; p.setData({ selfPlayPhase: 'busy' }); });
    let paid = false;
    for (const t of times) {
      if (!paid && t >= 1100) { await sleep(Math.max(0, 1100 - (Date.now() - t00))); await w.page.evaluate(() => { const p = getCurrentPages().slice(-1)[0]; p._playPaidMotion(990014, 5); }); paid = true; }
      await shot(w, t, t00);
    }
  }
  if (m === '5') {
    w = await launch({ path: 'pages/club/topic-detail/index?topicId=1&clubId=1&activityId=1', settle: 3000, agree: true, identity: 'member-1' });
    const aud = [['阿眠', '阿'], ['小周', '周'], ['Kiki', 'K']].map((x, i) => ({ id: 'u' + i, name: x[0], initial: x[1], sub: '已报名', picked: true }));
    await w.page.evaluate((aud) => { const p = getCurrentPages().slice(-1)[0];
      p.setData({ loaded: true, loadError: false, directorActive: true, loadState: 'ready', broadcastSheetVisible: true, broadcastAudience: aud, broadcastCountText: '3 位同行人', broadcastSendText: '发送 3 条提醒', broadcastPreviewReady: true, broadcastDraft: { targetType: 'ALL', targetIndex: 0, content: '集合提醒' }, broadcastScopeOptions: [], broadcastTargetRows: [], customerState: 'ready', customerRows: [], customerStats: [], customerChips: [], canBroadcast: true }); p._broadcastFromCustomers = true; }, aud);
    await sleep(1500);
    await w.page.screenshot({ type: 'jpeg', quality: 40 }); const t00 = Date.now();
    await w.page.evaluate(() => { const p = getCurrentPages().slice(-1)[0]; p.setData({ broadcastSendPhase: 'sending', broadcastSentCount: 3 }); });
    await sleep(60);
    await w.page.evaluate(() => { const p = getCurrentPages().slice(-1)[0]; p.setData({ broadcastSendPhase: 'sent' }); });
    for (const t of times) await shot(w, t, t00);
  }
  if (m === '6') {
    w = await launch({ path: 'pages/index/index', settle: 3000, agree: true, identity: 'member-1' });
    const CV = '/images/home-banner-friends.jpg';
    await w.page.evaluate((cover) => { const p = getCurrentPages().slice(-1)[0]; p.setData({ bottomTopicList: [{ id: 990014, name: '星尘便笺 · 人民广场观星线', cover, feedTags: ['城市定向'], feedTypeLabel: '主题' }, { id: 990015, name: '纸本旧书 · 人民广场寻字', cover, feedTags: ['城市定向'], feedTypeLabel: '主题' }] }); }, CV);
    await sleep(1200);
    const top = await w.page.evaluate(() => { const els = document.querySelectorAll('*'); return 0; });
    try { await w.scroll(Number(process.env.M6_SCROLL || 900)); } catch (e) {}
    await sleep(800);
    await w.page.screenshot({ path: `${outDir}/r-m6-${look}-list.png` });
    await w.page.screenshot({ type: 'jpeg', quality: 40 }); const t00 = Date.now();
    await w.page.evaluate((cover) => { const p = getCurrentPages().slice(-1)[0]; p.goTopic({ currentTarget: { dataset: { id: 990014, cover } } }); }, CV);
    for (const t of times) await shot(w, t, t00);
  }
  if (m === '7') { // 6 的返回:先进详情落定,再点返回(帧 m7 = 6b)
    w = await launch({ path: 'pages/index/index', settle: 3000, agree: true, identity: 'member-1' });
    const CV = '/images/home-banner-friends.jpg';
    await w.page.evaluate((cover) => { const p = getCurrentPages().slice(-1)[0]; p.setData({ bottomTopicList: [{ id: 990014, name: '星尘便笺 · 人民广场观星线', cover, feedTags: ['城市定向'], feedTypeLabel: '主题' }] }); }, CV);
    await sleep(1200); try { await w.scroll(Number(process.env.M6_SCROLL || 780)); } catch (e) {} await sleep(800);
    await w.page.evaluate((cover) => { getCurrentPages().slice(-1)[0].goTopic({ currentTarget: { dataset: { id: 990014, cover } } }); }, CV);
    await sleep(2500);
    await w.page.screenshot({ type: 'jpeg', quality: 40 }); const t00 = Date.now();
    await w.page.evaluate(() => { const p = getCurrentPages().slice(-1)[0]; p.goBack(); });
    for (const t of times) await shot(w, t, t00);
  }
  fs.writeFileSync(`${outDir}/skew-m${m}-${look}.json`, JSON.stringify(skew));
  console.log('ok', m, look, 'skew(ms)', JSON.stringify(skew), JSON.stringify(w.errors().slice(0, 2)).slice(0, 400));
} catch (e) { console.log('FAIL', String(e).slice(0, 400)); }
if (w) await w.browser.close();
