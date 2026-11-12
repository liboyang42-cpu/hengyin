import { launch } from '/root/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs';
const name = process.argv[2];
const w = await launch({ path: 'pages/roam/index', identity: 'e2e-openid-9004', agree: true });
await new Promise(r => setTimeout(r, 4000));
console.log(await w.eval(`(()=>{const p=getCurrentPages().slice(-1)[0]; if(!p.shareMedal) return 'no shareMedal on '+p.route; p.setData({medal:{name:'外滩夜行者',sub:'点亮 12 个外滩节点'}}); p.shareMedal(); return 'ok'})()`));
await new Promise(r => setTimeout(r, 2500));
await w.shot(name);
console.log('errors', w.errors());
await w.browser.close();
