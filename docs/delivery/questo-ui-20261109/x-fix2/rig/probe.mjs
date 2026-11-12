const { launch } = await import(process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const [pathname, js] = process.argv.slice(2);
const w = await launch({ path: pathname, identity: 'e2e-openid-9004', settle: 3000, agree: true });
try { console.log(JSON.stringify(await w.eval(js), null, 1)); } finally { await w.browser.close(); }
