import { go, sleep } from './go.mjs';
for (const [t, n] of [['进入项目', 'g27-op-enter-project'], ['联系合作方', 'g28-op-contact'], ['申报供给', 'g29-op-declare-supply'], ['评价', 'g30-op-review'], ['取消合作', 'g31-op-cancel']])
  await go(n, { path: 'pages/coop/invite-detail/index', identity: 'e2e-openid-9002' }, async (w, shot) => {
    await w.relaunch('/pages/coop/invite-detail/index?inviteId=990026'); await sleep(2500); await w.scroll(900); await sleep(400);
    await w.tap(t, { exact: true }); await sleep(2500); await shot(n); console.log('ROUTE', JSON.stringify(await w.route()));
  });
