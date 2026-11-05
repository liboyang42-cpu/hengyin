import { go, sleep } from './go.mjs';
await go('k1-quota', { path: 'pages/publish/activity/index', identity: 'e2e-openid-9008' }, async (w, shot) => { await sleep(3500); await shot('k1-quota-club-lead-publish'); });
