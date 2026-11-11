// x-fix2 rig 桩后端(HARNESS,仿 l-icons/rig/fakeapi.mjs):本容器拉不到 MySQL 镜像(Docker Hub 429),
// 只回截图要读的几条事实;其余一律 404。每条在 RESULT.md 的 rig 一节注明。
import http from 'node:http';
import fs from 'node:fs';
const FLAG = (name) => fs.existsSync('/tmp/x-fix2-rig-' + name);   // 截图时用标记文件切状态
const J = (res, body, status = 200) => { res.writeHead(status, { 'content-type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(body)); };
const ok = (data) => ({ code: 200, msg: '操作成功', data });
const IMG = '/pages/play/images/d_topic1.png';
const BADGES_UNUSED = [
  { code: 'explorer', name: '城市探索者', iconUrl: IMG, unlocked: true, unlockedAt: '2026-11-01 10:00:00', rarity: 0 },
  { code: 'broken', name: '签不出图的一枚', iconUrl: 'https://rig.invalid/broken.png', unlocked: true, unlockedAt: '2026-11-02 10:00:00', rarity: 1 },
  { code: 'locked', name: '夜行者', iconUrl: '', unlocked: false, rarity: 2, condition: '夜间完成一条路线' },
];
http.createServer((req, res) => {
  let body = ''; req.on('data', (c) => { body += c; }); req.on('end', () => {
    const u = new URL(req.url, 'http://x'); const p = u.pathname;
    if (p === '/api/login/code') { const m = /code=rig-([^&]+)/.exec(body); const id = m ? decodeURIComponent(m[1]) : 'guest'; return J(res, { code: 200, token: 'rig-token-' + id, data: { id: 9004, userType: 1, role: 'player', nickname: '走查玩家', avatar: '' } }); }
    if (p === '/api/role/info') return J(res, ok({ role: 'player', permission: {}, usage: {} }));
    if (p === '/api/user/info') return J(res, ok({ point: 120, id: 9004, nickName: '走查玩家' }));
    if (p === '/api/badge/wall-v2' && FLAG('wallfail')) return J(res, { code: 500, msg: '对象存储配置缺失: CHENGYIN_OSS_BUCKET_NAME' });
    if (p === '/api/badge/wall-v2') return J(res, ok({ identity: [{ badgeCode: 'explorer', badgeName: '城市探索者', category: 'EXPLORE', unlocked: true, unlockTime: '2026-11-01 10:00:00' }, { badgeCode: 'night', badgeName: '夜行者', category: 'EXPLORE', unlocked: false, unlockHint: '夜间完成一条路线' }] }));
    if (p === '/api/medal/wall') return J(res, ok({ medals: [{ medalName: '老城纪念章', medalImg: '/images/cy_logo_mark.png', getTime: '2026-11-03 10:00:00' }, { medalName: '签不出图的一枚', medalImg: 'https://rig.invalid/broken.png', getTime: '2026-11-04 10:00:00' }] }));
    if (p === '/api/roam/stamp/list') return J(res, ok({ list: [{ id: 1, picUrl: IMG }, { id: 2, picUrl: '/pages/play/images/d_topic2.png' }, { id: 3, picUrl: '/pages/play/images/d_topic4.png' }], total: 3 }));
    if (p === '/api/object-card/list') return J(res, ok({ list: [{ id: 1, title: '旧邮筒', category: '街道', place: '外滩', imageUrl: IMG }, { id: 2, title: '铜门环', category: '建筑', place: '豫园', imageUrl: '/pages/play/images/d_topic2.png' }], total: 2 }));
    J(res, { code: 404, msg: 'rig stub: ' + p }, 404);
  });
}).listen(8099, '127.0.0.1', () => console.log('x-fix2 fake api on 8099'));
