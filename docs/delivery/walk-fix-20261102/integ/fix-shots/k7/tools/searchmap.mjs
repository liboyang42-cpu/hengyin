// k7-map rig walk: merchant 9002 opens the search map (j4 N6). argv[2] = prefix.
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const pre = process.argv[2] || '';
// a merchant reaches the search map from inside the app (merchant home first, so the app has the merchant role)
const w = await launch({ path: 'pages/merchant/index/index', identity: 'e2e-openid-9002', agree: true, lat: 31.2304, lng: 121.4737, settle: 4000 });
await w.open('pages/searchmap/index'); await sleep(3000);
await w.shot(pre + 'n6-merchant-searchmap');
console.log('layer', await w.data('mapLayerStyle'), 'merchantView', await w.data('merchantView'));
console.log('errors', JSON.stringify(await w.errors()));
await w.close();
const p = await launch({ path: 'pages/searchmap/index', identity: 'e2e-openid-9004', agree: true, lat: 31.2304, lng: 121.4737, settle: 4000 });
await sleep(1500);
await p.shot(pre + 'n6-player-searchmap');
await p.close();
