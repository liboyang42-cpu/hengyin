// F12 受控响应的原生组件验证；不代表真实支付回跳或后端权限验收。
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { openMiniProgram } = require('../tests/automator/harness');

async function main() {
  const session = await openMiniProgram({ projectPath: path.resolve(__dirname, '..') });
  const mp = session.mp;
  const output = process.env.EVIDENCE_DIR || '/tmp/chengyin-f12-ui';
  fs.mkdirSync(output, { recursive: true });
  try {
    await mp.evaluate(() => {
      const app = getApp();
      app._f12OriginalRequest = app.sendRequest;
      app._f12Requests = [];
      app.sendRequest = function (options) {
        app._f12Requests.push({ url: options.url, data: options.data });
        const id = String((options.data || {}).id || '');
        const result = options.url === '/api/registration/info' && id === '76'
          ? { code: 200, data: { id: 76, registrationNo: 'F12-UI-76', registrationStatus: 2,
            paymentStatus: 2, verificationStatus: 0, ownerType: 2,
            cmsActivity: { name: '深链回归样板', startTime: '2099-01-01 10:00:00' } } }
          : { code: 403, msg: '无权查看该订单' };
        setTimeout(() => {
          if (options.success) options.success(result);
          if (options.complete) options.complete(result);
        }, 40);
      };
    });
    const page = await mp.reLaunch('/subpackageMember/orderinfo/orderinfo');
    await page.waitFor(500);
    const component = await page.$('#orderDetail');
    assert.ok(component, '订单组件必须挂载');
    assert.equal(await component.data('loadState'), 'failed_business');
    await page.setData({ id: '76' });
    await page.waitFor(500);
    assert.equal(await component.data('loadState'), 'ready');
    assert.equal(String(await component.data('id')), '76');
    assert.equal(await component.data('info.id'), 76);
    await mp.screenshot({ path: path.join(output, 'late-id-ready.png') });
    await page.setData({ id: '77' });
    await page.waitFor(300);
    assert.equal(await component.data('loadState'), 'failed_business');
    assert.deepEqual(await component.data('info'), {});
    await mp.screenshot({ path: path.join(output, 'changed-id-denied.png') });
    const requests = await mp.evaluate(() => getApp()._f12Requests);
    fs.writeFileSync(path.join(output, 'requests.json'), JSON.stringify(requests, null, 2));
    console.log('PASS F12: late property ready; changed order denied without old content. Controlled responses only.');
  } finally {
    await mp.evaluate(() => {
      const app = getApp();
      if (app._f12OriginalRequest) app.sendRequest = app._f12OriginalRequest;
      delete app._f12OriginalRequest;
      delete app._f12Requests;
    }).catch(() => {});
    if (!session.borrowed) await mp.close();
    else await mp.disconnect();
  }
}
main().catch(error => { console.error(error); process.exitCode = 1; });
