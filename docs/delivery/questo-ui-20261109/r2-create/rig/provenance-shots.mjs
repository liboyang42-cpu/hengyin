// r2-create 模板配置「素材来源」取证(rig only):商家 9002 新建玩法 → 上传封面(rig 生成图 + 真实上传)→ 素材来源 → 保存。
const { launch } = await import((process.env.RIG_DIR || '/home/user/rig/docs/delivery/cloud-walk-20261101/rig') + '/mpweb/walk.mjs');
const tag = process.argv[2] || 'after';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const w = await launch({ path: 'pages/publish/temp/index', identity: 'e2e-openid-9002', agree: true });
await sleep(3500);
// rig 的内容安全桩拒绝一切上传(HARNESS,同 qx-a5d),拿不到真实上传回包里的素材编号:
// 用 seed_media_asset.py 在 rig 库里放一条 9002 名下已过审的素材(ASSET_ID),页面状态等同「刚上传完封面」。
const assetId = process.env.ASSET_ID || '';
if (tag === 'after' && assetId) {
  await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; p._setFormState({ 'formData.imgUrl': '/images/home-route-free-placeholder.jpg' }); p.setData({ coverProvenance: { assetId: '${assetId}', label: '' } }); })()`);
} else {
  await w.eval(`getCurrentPages().slice(-1)[0].setData({ 'formData.imgUrl': '/images/home-route-free-placeholder.jpg' })`);
}
await sleep(1200);
// 新建玩法会先弹「选择玩法」:先退出它,回到配置页
try { await w.tap('退出', { exact: true }); await sleep(900); } catch (e) { /* 没弹 */ }
console.log('route', JSON.stringify(await w.route()));
await w.shot(`provenance-${tag}`);
if (tag === 'after') {
  try {
    await w.tap('素材来源'); await sleep(900); await w.shot('provenance-sheet-after');
    await w.tap('保存', { exact: true }); await sleep(600); await w.shot('provenance-error-after');
    await w.tap('原创'); await sleep(300);
    await w.eval(`(() => { const p = getCurrentPages().slice(-1)[0]; p.setData({ 'provenanceSheet.licenseNote': '本人拍摄', 'provenanceSheet.attribution': '商家老板' }); })()`);
    await w.tap('保存', { exact: true }); await sleep(2500);
    await w.shot('provenance-saved-after');
  } catch (e) { console.log('provenance step', e.message); }
}
const net = await w.net();
console.log('net', JSON.stringify(net.filter((n) => /upload|provenance/.test(n.url)).map((n) => [n.method, n.url, n.status, String(n.resBody || '').slice(0, 300)])));
console.log('errors', JSON.stringify(w.errors()));
await w.browser.close();
