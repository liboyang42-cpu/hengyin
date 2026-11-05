// k7-map rig walk: roam entry, roam map, pins, chip, edge pins, snow, largest font. argv[2] = shot prefix ("b-" before / "a-" after).
// Seams (rig, not product): onRegionChange stands in for pinch/drag (no gestures on the stand-in); the map's markertap is fired
// through the page's own onMarkerTap (the stand-in draws pins on a canvas); /api/map/weather is answered in the page with
// {kind:'snow',intensity:0.9} (no Tencent key). Everything else is the real page against the real backend.
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const pre = process.argv[2] || '';
const font = process.argv[3] === 'font';
const pg = (w, expr) => w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];return JSON.stringify(${expr})})()`);
const region = (lat, lng, s) => `(()=>{const s=${s},lat=${lat},lng=${lng};const mpp=156543.03*Math.cos(lat*Math.PI/180)/Math.pow(2,s);const dLat=844*mpp/111000/2,dLng=390*mpp/(111000*Math.cos(lat*Math.PI/180))/2;return {southwest:{latitude:lat-dLat,longitude:lng-dLng},northeast:{latitude:lat+dLat,longitude:lng+dLng}}})()`;
const end = (lat, lng, s, cause) => `(()=>{const p=getCurrentPages().slice(-1)[0];p.setData({mapScale:${s},center:{lat:${lat},lng:${lng}}});p.onRegionChange({detail:{type:'end',causedBy:'${cause}',scale:${s},region:${region(lat, lng, s)}}});return 'end'})()`;
const bigFont = `(()=>{const p=getCurrentPages().slice(-1)[0];p.__host.style.cssText+=';'+(p.data.fontScaleStyle||'');return getComputedStyle(p.__host).getPropertyValue('--cy-type-scale')+'|'+p.data.fontScaleStyle})()`;
const w = await launch({ path: 'pages/roam/index', identity: 'e2e-openid-9004', agree: true, lat: 31.2304, lng: 121.4737, settle: 4000, ...(font ? { font: 22 } : {}) });
if (font) console.log('scale', await w.eval(bigFont));
// weather reply for the snow shot (HARNESS)
await w.eval(`(()=>{const a=getApp();const o=a.sendRequest;a.sendRequest=function(opt){if(opt&&/\\/api\\/map\\/weather/.test(opt.url||'')){setTimeout(()=>opt.success&&opt.success({code:200,data:{kind:'snow',intensity:0.9}}),30);return}return o.apply(this,arguments)};return 1})()`);
try { await w.tap('开启定位'); await sleep(3500); } catch (e) { console.log('no 开启定位'); }
if (font) await w.eval(bigFont);
await w.shot(pre + (font ? 'f14-entry-font' : 'n5-entry'));
console.log('cards', await pg(w, '(p.data.entryCards||[]).map(c=>c.title+" | "+c.meta)'), 'teamsLive', await pg(w, 'p.data.introTeamsLive'));
if (!font) { await w.scroll(400); await sleep(800); await w.shot(pre + 'n4-entry-pass'); await w.scroll(-400); }
await w.tap('开始漫游', { nth: 1 }); await sleep(2000); try { await w.tap('同意并开启'); } catch (e) {} await sleep(6000);
if (font) await w.eval(bigFont);
await w.eval('MPWEB.mapTrueScale=true');
await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];(p._pois||[]).forEach(x=>{if(x.state==='fog')x.state='seen'});return 1})()`);
await w.eval(end(31.2304, 121.4737, 16, 'scale')); await sleep(2500);
if (font) { await w.shot(pre + 'f14-map-font'); await w.close(); process.exit(0); }
await w.shot(pre + 'n3-map-snow');
console.log('goal', await pg(w, 'p.data.goal&&p.data.goal.text'));
const shops = JSON.parse(await pg(w, '(p._pois||[]).filter(x=>x.cat==="merchant").map(x=>[x.id,x.name])'));
console.log('merchant pois', JSON.stringify(shops));
// N1: tap a shop's photo pin
if (shops.length) {
  const id = shops[shops.length - 1][0];
  await w.eval(`(()=>{getCurrentPages().slice(-1)[0].onMarkerTap({detail:{markerId:${JSON.stringify(id)}}});return 1})()`); await sleep(2500);
  await w.shot(pre + 'n1-shop-pin-tap');
  console.log('after pin tap', await pg(w, '{strip:p.data.shopStrip.rows.map(r=>r.name),idx:p.data.shopStrip.idx,placeCard:p.data.placeCard&&p.data.placeCard.name}'));
  await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];if(p.data.shopStrip.rows.length)p.closeShopStrip();if(p.data.placeCard)p.closePlaceCard();return 1})()`); await sleep(1200);
}
// N1: zoom out, the 「附近 N 个地点」 chip, tap it (real DOM tap on its text)
await w.eval(end(31.2304, 121.4737, 13, 'scale')); await sleep(2500);
const chip = await pg(w, 'p.data.pinCountLabel');
console.log('chip', chip);
await w.shot(pre + 'n1-chip');
try { await w.tap(JSON.parse(chip)); await sleep(2500); } catch (e) { console.log('chip tap failed', e.message); }
await w.shot(pre + 'n1-chip-tap');
console.log('after chip tap', await pg(w, '{strip:p.data.shopStrip.rows.map(r=>r.name),idx:p.data.shopStrip.idx}'));
await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];if(p.data.shopStrip.rows.length)p.closeShopStrip();return 1})()`); await sleep(1000);
// N2: zoom in to 17 and drag ~350 m east: edge pins
await w.eval(end(31.2304, 121.4737, 17, 'scale')); await sleep(2000);
await w.eval(`(()=>{getCurrentPages().slice(-1)[0].onRegionChange({detail:{type:'begin',causedBy:'gesture'}});return 1})()`);
await w.eval(end(31.2304, 121.4774, 17, 'gesture')); await sleep(2500);
await w.shot(pre + 'n2-edge-pins');
console.log('edge', await pg(w, '(p.data.markers||[]).filter(m=>m.width===26||m.width===10).map(m=>[m.id,+(+m.latitude).toFixed(5),+(+m.longitude).toFixed(5),m.width])'));
console.log('safe', await pg(w, 'typeof p._pinSafeArea==="function"?p._pinSafeArea():null'), 'win', await pg(w, 'p._win'));
console.log('errors', JSON.stringify(await w.errors()));
await w.close();
