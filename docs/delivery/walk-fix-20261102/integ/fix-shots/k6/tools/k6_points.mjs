import { go, sleep } from './go.mjs';
const ID = process.argv[2] || 'e2e-openid-k6new1';
await go('pts-home', { path: 'pages/index/index', identity: ID }, async (w, shot) => { await sleep(3000); await shot('pts-01-new-player-home'); });
await go('pts-hist', { path: 'subpackageMember/points-history/index', identity: ID }, async (w, shot) => { await sleep(3000); await shot('pts-02-points-history'); });
await go('pts-growth', { path: 'subpackageP3/pages/growthcenter/index/index', identity: ID }, async (w, shot) => { await sleep(3000); await shot('pts-03-growth-balance'); });
