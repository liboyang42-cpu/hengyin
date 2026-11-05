// j4-1105: drag on the roam map (seam: onRegionChange begin/end + center move, since the stand-in has no gestures)
import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const w = await launch({ path: 'pages/roam/index', identity: process.argv[2] || 'e2e-openid-9004', agree: true, lat: 31.2304, lng: 121.4737, settle: 3500 });
const pre = process.argv[3] || '';
await w.tap('开始漫游', { nth: 1 }); await sleep(2000); await w.tap('同意并开启'); await sleep(6000);
await w.eval('MPWEB.mapTrueScale=true');
await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0];(p._pois||[]).forEach(x=>{if(x.state==='fog')x.state='seen'});return 1})()`);
const region = (lat, lng, s) => `(()=>{const p=getCurrentPages().slice(-1)[0];const s=${s},lat=${lat},lng=${lng};const mpp=156543.03*Math.cos(lat*Math.PI/180)/Math.pow(2,s);const dLat=844*mpp/111000/2,dLng=390*mpp/(111000*Math.cos(lat*Math.PI/180))/2;return {southwest:{latitude:lat-dLat,longitude:lng-dLng},northeast:{latitude:lat+dLat,longitude:lng+dLng}}})()`;
const end = (lat, lng, s, cause) => `(()=>{const p=getCurrentPages().slice(-1)[0];p.setData({mapScale:${s},center:{lat:${lat},lng:${lng}}});p.onRegionChange({detail:{type:'end',causedBy:'${cause}',scale:${s},region:${region(lat, lng, s)}}});return 'end'})()`;
await w.eval(end(31.2304, 121.4737, 17, 'scale')); await sleep(2500);
await w.shot(pre + '40-drag-before');
const mk = async () => JSON.parse(await w.eval(`JSON.stringify((getCurrentPages().slice(-1)[0].data.markers||[]).map(m=>({id:m.id,w:m.width,lat:+(+m.latitude).toFixed(5),lng:+(+m.longitude).toFixed(5),lbl:(m.callout&&m.callout.content)||(m.label&&m.label.content)||''})))`));
const before = await mk();
await w.eval(`(()=>{getCurrentPages().slice(-1)[0].onRegionChange({detail:{type:'begin',causedBy:'gesture'}});return 'begin'})()`);
await sleep(300);
await w.shot(pre + '41-drag-begin-clouds');
console.log('cloudOn', await w.data('cloudOn'));
// drag the map so the camera moves ~350 m east (content moves west)
await w.eval(end(31.2304, 121.4774, 17, 'gesture')); await sleep(400);
await w.shot(pre + '42-drag-release');
await sleep(1200);
await w.shot(pre + '43-drag-settled');
console.log('cloudStyle', await w.data('cloudStyle'), 'cloudOn', await w.data('cloudOn'));
const after = await mk();
console.log('markers before', before.length, JSON.stringify(before.slice(0, 40)));
console.log('markers after', after.length, JSON.stringify(after.slice(0, 40)));
// release: drag back
await w.eval(`(()=>{getCurrentPages().slice(-1)[0].onRegionChange({detail:{type:'begin',causedBy:'gesture'}});return 'begin'})()`);
await w.eval(end(31.2304, 121.4737, 17, 'gesture')); await sleep(1600);
await w.shot(pre + '44-drag-back');
const back = await mk();
console.log('markers back', back.length, JSON.stringify(back.slice(0, 40)));
console.log('errors', JSON.stringify(await w.errors()));
await w.close();
