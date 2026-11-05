import { go, sleep } from './go.mjs';
const T = process.argv[2] || 'd13';
await go(`${T}-tixian`, { path: 'subpackageMember/tixian/tixian', identity: 'e2e-openid-9002' }, async (w, shot) => { await sleep(3000); await shot(`d14-tixian-${T}`); });
