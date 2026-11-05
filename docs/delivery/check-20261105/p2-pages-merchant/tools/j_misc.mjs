import { go, sleep } from './go.mjs';
await go('j01-coopcenter-detail', { path: 'pages/merchant/coop-center/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2500); await w.tap('看详情'); await sleep(800); await shot('j01-coopcenter-detail-800ms'); await sleep(2000); await shot('j02-coopcenter-detail-settled');
  await w.scroll(500); await shot('j03-coopcenter-detail-scrolled');
});
await go('j04-workbench-qr', { path: 'pages/merchant/index/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2500); await w.tap('E2E 探店日一期'); await sleep(2500); await w.tap('二维码', { exact: true }); await sleep(2500); await shot('j04-workbench-qr-sheet');
  await w.back(); await sleep(500);
});
await go('j05-workbench-npc', { path: 'pages/merchant/index/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2500); await w.tap('E2E 探店日一期'); await sleep(2500); await w.tap('节点NPC', { exact: true }); await sleep(2500); await shot('j05-workbench-npc-sheet');
  await w.relaunch('/pages/merchant/index/index'); await sleep(2500); await w.tap('E2E 探店日一期'); await sleep(2500); await w.tap('客户', { exact: true }); await sleep(2500); await shot('j06-workbench-customers');
});
