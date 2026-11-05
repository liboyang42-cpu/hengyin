import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const w = await launch({ path: 'pages/roam/index', identity: 'e2e-openid-9004', agree: true, lat: 31.2304, lng: 121.4737, settle: 3500 });
await w.tap('开始漫游', { nth: 1 }); await sleep(2000); await w.tap('同意并开启'); await sleep(6000);
await w.eval('MPWEB.mapTrueScale=true');
await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];(p._pois||[]).forEach(x=>{if(x.state==='fog')x.state='seen'});p.setData({mapScale:16});return 1})()`);
console.log('goal', JSON.stringify(await w.data('goal')));
console.log('merchant pois', await w.eval(`JSON.stringify((getCurrentPages().slice(-1)[0]._pois||[]).filter(p=>p.cat==='merchant').map(p=>[p.id,p.name,p.lat,p.lng]))`));
// SEAM: the row's own entry handler (the only UI entry is the poiEmpty goal line)
await w.eval(`(()=>{getCurrentPages().slice(-1)[0].openShopStrip();return 1})()`); await sleep(2500);
await w.shot('45-strip-open');
const st = async () => w.eval(`JSON.stringify({idx:getCurrentPages().slice(-1)[0].data.shopStrip.idx,rows:getCurrentPages().slice(-1)[0].data.shopStrip.rows.map(r=>r.name),center:getCurrentPages().slice(-1)[0].data.center,focus:getCurrentPages().slice(-1)[0]._pinFocusId,big:(getCurrentPages().slice(-1)[0].data.markers||[]).filter(m=>m.width>=48).map(m=>[m.id,m.width])})`);
console.log('open', await st());
// swipe = the swiper's change event (no touch gestures in the rig)
await w.eval(`(()=>{getCurrentPages().slice(-1)[0].onShopStripSwipe({detail:{current:1,source:'touch'}});return 1})()`); await sleep(2000);
await w.shot('46-strip-swipe-2');
console.log('swipe1', await st());
await w.eval(`(()=>{getCurrentPages().slice(-1)[0].onShopStripSwipe({detail:{current:2,source:'touch'}});return 1})()`); await sleep(2000);
await w.shot('47-strip-swipe-3');
console.log('swipe2', await st());
// tap the first card's pin on the map -> row scrolls back to card 1
const first = await w.eval(`getCurrentPages().slice(-1)[0].data.shopStrip.rows[0].poiId`);
await w.eval(`(()=>{getCurrentPages().slice(-1)[0].onMarkerTap({detail:{markerId:Number(${JSON.stringify(first)})}});return 1})()`); await sleep(2000);
await w.shot('48-strip-pin-tap');
console.log('pintap', await st());
await w.tapSel ? 0 : 0; await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];p.onShopStripGo({currentTarget:{dataset:{id:p.data.shopStrip.rows[p.data.shopStrip.idx].id}}});return 1})()`); await sleep(3000);
await w.shot('49-strip-go');
console.log('go', (await w.text()).slice(0, 300));
console.log('moves', await w.eval('JSON.stringify(MPWEB.moves||[])'));
console.log('errors', JSON.stringify(await w.errors()));
await w.close();
