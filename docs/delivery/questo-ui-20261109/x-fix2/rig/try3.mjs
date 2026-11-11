const { launch } = await import(process.env.HOME + '/rig/docs/delivery/cloud-walk-20261101/rig/mpweb/walk.mjs');
const [pathname, name, js] = process.argv.slice(2);
const w = await launch({ path: pathname, identity: 'e2e-openid-9004', settle: 2500, agree: true, lat: 31.23, lng: 121.47 });
try { console.log(JSON.stringify(await w.eval(js))); await new Promise((r) => setTimeout(r, 1500)); await w.shot(name); } finally { await w.browser.close(); }
