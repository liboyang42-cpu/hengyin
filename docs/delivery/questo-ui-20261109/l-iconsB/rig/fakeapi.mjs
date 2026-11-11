// l-iconsB rig 桩后端(HARNESS,沿用 l-icons/rig/fakeapi.mjs):没有真后端时,只回这几屏要读的事实;其余一律 404。
// 身份名里带 held = 已持证(有效期至 2027-11-10);带 merchant = 商家视角。截图说明里逐条注明。
import http from 'node:http';
const J = (res, body, status = 200) => { res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(body)); };
const ok = (data) => ({ code: 200, msg: '操作成功', data });
const LIB = [
  { topicId: 101, name: '老城三封信', imgUrl: '/pages/play/images/d_topic1.png', productType: 1, totalTime: 3600, addressName: '外滩', covered: true },
  { topicId: 102, name: '钟楼背后', imgUrl: '/pages/play/images/d_topic2.png', productType: 1, totalTime: 2700, addressName: '北外滩', covered: true },
  { topicId: 103, name: '灯笼铺的夜', imgUrl: '/pages/play/images/d_topic4.png', productType: 2, totalTime: 1800, addressName: '豫园', covered: true },
  { topicId: 104, name: '邮政大楼', imgUrl: '/pages/play/images/d_topic5.png', productType: 1, totalTime: 4200, addressName: '苏州河', covered: true },
];
http.createServer((req, res) => {
  let body = ''; req.on('data', (c) => { body += c; }); req.on('end', () => {
    const u = new URL(req.url, 'http://x'); const p = u.pathname; const tok = String(req.headers.authorization || '');
    if (p === '/api/login/code') { const m = /code=rig-([^&]+)/.exec(body); const id = m ? decodeURIComponent(m[1]) : 'guest'; const isM = /merchant/.test(id); return J(res, { code: 200, token: 'rig-token-' + id, data: { id: isM ? 9102 : 9004, userType: isM ? 2 : 1, role: isM ? 'merchant' : 'player', nickname: isM ? '走查商家' : '走查玩家', avatar: '' } }); }
    if (p === '/api/role/info') return J(res, ok({ role: /merchant/.test(tok) ? 'merchant' : 'player', permission: {}, usage: {} }));
    if (p === '/api/platform-pass') return J(res, ok({ enabled: true, plans: [{ planCode: 'YEAR', priceFen: 19900, validDays: 365 }, { planCode: 'MONTH', priceFen: 2900, validDays: 30 }], channels: { jsapi: { available: true }, xpay: { available: true } }, holding: /held/.test(tok) ? { active: true, validTo: '2027-11-10', coverageEnd: '2027-11-10' } : { active: false, validTo: null, coverageEnd: null }, libraryCount: LIB.length }));
    if (p === '/api/platform-pass/library') return J(res, ok({ rows: LIB, total: LIB.length }));
    if (p === '/api/user/info') return J(res, ok({ point: 120, id: 9004, nickName: '走查玩家' }));
    if (p.startsWith('/api/marketing/product/')) return J(res, ok([]));
    J(res, { code: 404, msg: 'rig stub: ' + p }, 404);
  });
}).listen(8099, '127.0.0.1', () => console.log('fake api on 8099'));
