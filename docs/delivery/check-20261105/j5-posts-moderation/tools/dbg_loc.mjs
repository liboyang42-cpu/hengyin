import { start, sleep } from './lib.mjs';
const w = await start({ path: 'pages/publish/activity/index?scope=MERCHANT', identity: 'e2e-openid-9002' });
await sleep(2000);
await w.page.evaluate(() => { window.__locCalls = 0; window.__poi = 0; const pg = getCurrentPages().slice(-1)[0]; const f = pg.choosePoiForNode; pg.choosePoiForNode = function () { window.__poi++; return f.apply(this, arguments); }; window.__wxSame = (MPWEB.wx === wx); wx.chooseLocation = function (a) { window.__locCalls++; setTimeout(() => { const r = { name: 'X 咖啡角', address: '上海市', latitude: 31.2304, longitude: 121.4737, errMsg: 'chooseLocation:ok' }; a.success && a.success(r); a.complete && a.complete(r); }, 20); }; });
{ const b = await (await w.page.$('pierce/[aria-label="选择活动地点"]')).boundingBox(); await w.page.mouse.click(b.x + 4, b.y + 4); } await sleep(1200); await w.shot('dbg-loc'); console.log('box', JSON.stringify(await (await w.page.$('pierce/[aria-label="选择活动地点"]')).boundingBox()));
console.log('calls', await w.page.evaluate(() => [window.__locCalls, window.__poi, window.__wxSame]), JSON.stringify(await w.data('formData')).slice(0, 300));
console.log(w.log ? (await w.log()).slice(-10) : '');
await w.finish();
