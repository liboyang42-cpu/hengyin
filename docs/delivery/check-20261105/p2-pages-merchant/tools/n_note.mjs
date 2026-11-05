import { go, sleep } from './go.mjs';
await go('n05-cust-note-save', { path: 'pages/merchant/customer/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2500); await w.tap('玩家二号'); await sleep(2200); await w.tap('编辑', { exact: true }); await sleep(1200);
  await w.type('textarea', '喜欢浅烘，周末常来'); await sleep(300); await w.tap('保存', { exact: true }); await sleep(2500); await shot('n05-customer-note-saved');
  console.log('AF', (await w.text()).slice(0, 500));
});
await go('n06-export-later', { path: 'pages/merchant/customer/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
  await sleep(2500); await w.tap('管理', { exact: true }); await sleep(900); await w.tap('导出', { exact: true }); await sleep(12000); await shot('n06-customer-export-12s'); console.log('EX', (await w.text()).slice(0, 300));
});
