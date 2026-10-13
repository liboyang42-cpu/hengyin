const automator = require('miniprogram-automator');
const PAGES = [
  '/pages/index/index',
  '/pages/merchant/index/index',
  '/pages/club/enroll/index',
  '/pages/coop/list/index',
  '/subpackageMember/order/order',
];
(async () => {
  const mp = await automator.launch({ projectPath: '/Users/arlan/Downloads/chengyin/chengyinhub-xcx', cliPath: '/Applications/wechatwebdevtools.app/Contents/MacOS/cli' });
  let pass = 0;
  for (const p of PAGES) {
    try {
      const page = await mp.reLaunch(p);
      await page.waitFor(900);
      const cur = await page.path;
      const ok = ('/' + cur) === p;
      console.log(`${ok ? 'PASS' : 'FAIL'} | 加载 ${p} | 实际 /${cur}`);
      if (ok) pass++;
    } catch (e) { console.log(`FAIL | 加载 ${p} | ${e.message}`); }
  }
  await mp.close();
  console.log(`\n==== ${pass}/${PAGES.length} 页加载无崩溃 ====`);
  process.exit(pass === PAGES.length ? 0 : 2);
})().catch(e => { console.error('FATAL:', e && e.message ? e.message : e); process.exit(1); });
