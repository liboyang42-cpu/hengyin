const { launch } = await import(process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const [pathname, name, waitMs, evalJs] = process.argv.slice(2);
const w = await launch({ path: pathname, identity: 'e2e-openid-9004', settle: 2500, agree: true });
try {
  if (evalJs) { console.log('eval', JSON.stringify(await w.eval(evalJs))); }
  await new Promise((r) => setTimeout(r, Number(waitMs || 1500)));
  await w.shot(name);
  console.log('ERR', JSON.stringify(w.errors().slice(0, 3)), JSON.stringify(w.console().filter((l) => l.startsWith('[error]')).slice(0, 4)));
} finally { await w.browser.close(); }
