#!/usr/bin/env node
// Uses the existing rig driver and real authenticated app requests. No SQL, payment or fixture writes.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL, fileURLToPath } from 'node:url';

const ACTORS = [9014, 9015, 9016];
const own = (data) => ({
  settledAmountText: data.settledAmountText,
  topics: (data.topics || []).map(r => ({ id: r.id, topicId: r.topicId, amountText: r.amountText,
    originalAmountText: r.originalAmountText, executedAdjustmentText: r.executedAdjustmentText,
    netAmountText: r.netAmountText, status: r.status, arrivedText: r.arrivedText, paidText: r.paidText })).sort((a, b) => a.id - b.id),
  sessions: (data.sessions || []).map(r => ({ activityId: r.activityId, netText: r.netText }))
    .sort((a, b) => a.activityId - b.activityId),
});
const earned = snapshot => [...snapshot.topics.map(row => row.originalAmountText), ...snapshot.sessions.map(row => row.netText)]
  .some(text => typeof text === 'string' && Number(text.replace(/−/g, '-').replace(/[^0-9.-]/g, '')) > 0);
const positive = value => Number.isSafeInteger(Number(value)) && Number(value) > 0;
const ok = result => Number(result && result.code) === 200;
const sourceOf = info => [info?.sourceSha, info?.SOURCE_SHA, info?.build?.sourceSha, info?.build?.version,
  info?.git?.commit?.id?.full, typeof info?.git?.commit?.id === 'string' ? info.git.commit.id : null]
  .find(value => typeof value === 'string' && /^[a-f0-9]{40}$/.test(value));
const scopeOnly = data => {
  assert.equal(data.personal, true, 'Personal read must be explicitly server-scoped');
  for (const row of data.sessions || []) for (const key of ['grossText', 'feeText', 'merchantFeeText'])
    assert.equal(Object.hasOwn(row, key), false, 'Personal session leaked ' + key);
  return own(data);
};
const block = message => { throw new Error('PREREQUISITE: ' + message); };

async function api(w, url, data = {}) {
  assert.ok(['/api/club/settlement/mine', '/api/club/settlement/summary', '/api/club/access/me',
    '/api/club/governance/list', '/api/club/detail'].includes(url), 'Only declared read endpoints are allowed');
  return w.page.evaluate(({ url, data }) => new Promise(resolve => getApp().sendRequest({
    url, method: 'POST', data: JSON.stringify(data), header: { 'Content-Type': 'application/json' },
    hideLoading: true, silentError: true, success: resolve, successStatusAbnormal: resolve,
    fail: () => resolve({ code: -1, msg: 'transport failed' }),
  })), { url, data });
}

async function runtimeSource(w, expected) {
  const result = await w.page.evaluate(async () => {
    const response = await fetch('/__api/actuator/info', { headers: { Authorization: getApp().getAuthorization() } });
    return { status: response.status, data: response.ok ? await response.json() : null };
  });
  if (result.status !== 200 || !sourceOf(result.data)) block('Authenticated runtime source is unreadable; root must verify the source seam');
  assert.equal(sourceOf(result.data), expected, 'Backend source does not match the ready candidate');
  return expected;
}

async function transferThroughUi(w, targetId, clubId) {
  await w.open('/pages/club/detail/index?id=' + clubId);
  try {
    await w.tapSel('[aria-label="打开设置"]');
    await w.tap('成员治理', { exact: true });
    if (!(await w.data('isOwner'))) block('Normal governance UI does not identify this actor as owner');
    const members = await w.data('members');
    if (!members.some(row => Number(row.memberId) === targetId)) block('Target is not a normal eligible member returned by governance UI');
    await w.tap('转让主理人', { exact: true });
    await w.tapSel('.picker-row[data-member-id="' + targetId + '"]');
    await w.type('.mh__input', 'R14 ACCESS 正常交接验收');
  } catch (error) { block('Governance UI prerequisite missing: ' + error.message); }
  const response = w.page.waitForResponse(r => r.url().includes('/api/club/governance/owner/transfer')
    && r.request().method() === 'POST', { timeout: 20000 });
  const [received] = await Promise.all([response, w.tap('确认转让', { exact: true })]);
  const receipt = await received.json();
  assert.ok(ok(receipt), 'Normal transfer refused: ' + (receipt.msg || 'unknown'));
  assert.ok(positive(receipt.data?.id), 'Transfer must return its real governance record ID');
  assert.equal(Number(receipt.data.clubId), clubId);
  assert.equal(Number(receipt.data.toOwnerMemberId), targetId);
  return { id: receipt.data.id, clubId: receipt.data.clubId,
    fromOwnerMemberId: receipt.data.fromOwnerMemberId, toOwnerMemberId: receipt.data.toOwnerMemberId };
}

async function ownEntry(w, clubId) {
  await w.open('/subpackageA/pages/assetcenter/earnings/index');
  await w.tap('更多', { exact: true });
  await w.tap('本人俱乐部结算', { exact: true });
  await w.tapSel('[data-id="' + clubId + '"]');
  assert.equal(await w.data('summary.personal'), true);
  const topics = await w.data('summary.topics');
  if (topics.length) {
    await w.tapSel('[data-id="' + topics[0].id + '"]');
    assert.equal(await w.data('loadState'), 'ready', 'Own real returned row must open its detail');
    await w.back();
  }
}

async function main() {
  if (process.argv.includes('--self-check')) {
    assert.ok(earned({ topics: [{ originalAmountText: '¥12.00' }], sessions: [] }));
    assert.ok(!earned({ topics: [], sessions: [{ netText: '−¥2.00' }, { netText: '¥0.00' }] }));
    assert.ok(positive('7')); assert.ok(!positive(-1)); assert.ok(!positive('1.5'));
    assert.equal(sourceOf({ git: { commit: { id: { full: 'a'.repeat(40) } } } }), 'a'.repeat(40));
    assert.throws(() => scopeOnly({ personal: true, sessions: [{ grossText: '¥99.00' }] }));
    assert.deepEqual(scopeOnly({ personal: true, topics: [], sessions: [] }),
      { settledAmountText: undefined, topics: [], sessions: [] });
    console.log('SELF_CHECK PASS — no browser, login, network, governance or money action'); return;
  }
  const sha = process.env.ACCESS_READY_SHA;
  assert.equal(process.env.ACCESS_RUNTIME_READY, '1', 'Wait for the explicit root runtime-ready signal');
  assert.match(sha || '', /^[a-f0-9]{40}$/);
  assert.ok(positive(process.env.ACCESS_CLUB_ID), 'Use the club ID returned by a normal authored fixture flow');
  const clubId = Number(process.env.ACCESS_CLUB_ID);
  const web = new URL(process.env.RIG_WEB || 'http://127.0.0.1:18197');
  assert.ok(['127.0.0.1', '[::1]', 'localhost'].includes(web.hostname));
  assert.ok(path.isAbsolute(process.env.ACCESS_PROOF_DIR || ''), 'Private proof directory must be absolute');
  const out = path.resolve(process.env.ACCESS_PROOF_DIR);
  const checkout = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../../../..');
  for (const root of [checkout, process.cwd()]) assert.ok(out !== root && !out.startsWith(root + path.sep), 'Proof stays outside the public checkout');
  fs.mkdirSync(out, { recursive: true, mode: 0o700 });
  process.env.RIG_WEB = web.href.replace(/\/$/, ''); process.env.RIG_SHOTS = out;
  assert.ok(process.env.RIG_WALK_MODULE, 'Supply the existing approved rig walk.mjs');
  const { launch } = await import(pathToFileURL(path.resolve(process.env.RIG_WALK_MODULE)));
  const report = { status: 'UNVERIFIED', sha, actors: ACTORS, clubId, checks: [], transfers: [], readbacks: {},
    notCovered: ['real device', 'real moderation provider', 'customer-service case completion', 'external payments', 'independent SQL/ledger readback'] };
  const sessions = [];
  try {
    for (const id of ACTORS) {
      const w = await launch({ path: 'subpackageA/pages/assetcenter/earnings/index',
        identity: 'e2e-openid-' + id, agree: true, localOnly: true });
      sessions.push(w);
      assert.equal(Number(await w.page.evaluate(() => getApp().getUserID())), id, 'Real login returned a different allocated identity');
    }
    const [a, b, stranger] = sessions;
    await runtimeSource(a, sha);
    const access = await api(a, '/api/club/access/me', { clubId });
    if (!ok(access) || !access.data?.roleCodes?.includes('CLUB_OWNER')) block('9014 must be the normal current owner before this authored replay');
    const mine = await api(a, '/api/club/settlement/mine');
    if (!ok(mine) || !mine.data?.some(row => Number(row.clubId) === clubId))
      block('9014 has no historically frozen receivable/posted earnings for this actual club; do not create ledger rows or bypass end+14 days');
    const beforeA = await api(a, '/api/club/settlement/summary', { clubId, personal: true });
    const beforeB = await api(b, '/api/club/settlement/summary', { clubId, personal: true });
    if (!ok(beforeA) || !ok(beforeB)) block('Both allocated payees need distinct normal historical earnings/receivables in this club before comparing ownership isolation');
    const aMoney = scopeOnly(beforeA.data), bMoney = scopeOnly(beforeB.data);
    report.readbacks.before = { former: aMoney, current: bMoney };
    if (!earned(aMoney) || !earned(bMoney)) block('Both actors need genuine historical earnings; zero/negative rows cannot manufacture this prerequisite');
    if (JSON.stringify(aMoney) === JSON.stringify(bMoney)) block('Use distinct real personal income projections so another payee cannot masquerade as this actor');
    assert.ok(!aMoney.topics.some(row => bMoney.topics.some(other => other.id === row.id)), 'Payee topic rows overlap');
    for (const body of [{ clubId }, {}, { clubId: 0 }, { clubId: -1 }])
      assert.ok(!ok(await api(stranger, '/api/club/settlement/summary', body)), 'Stranger/missing/invalid scope was allowed');
    const wrongClub = Number(process.env.ACCESS_OTHER_CLUB_ID);
    if (positive(wrongClub) && wrongClub !== clubId) {
      const returned = await api(stranger, '/api/club/detail', { id: wrongClub });
      if (!ok(returned) || Number(returned.data?.id) !== wrongClub) block('Other-club ID must be independently returned by the real detail endpoint');
      assert.ok(!ok(await api(a, '/api/club/settlement/summary', { clubId: returned.data.id, personal: true })));
    } else report.notCovered.push('different real club fixture missing');
    report.transfers.push(await transferThroughUi(a, 9015, clubId));
    await a.login('e2e-openid-9014'); await b.login('e2e-openid-9015');
    const bAccess = await api(b, '/api/club/access/me', { clubId });
    assert.ok(bAccess.data?.roleCodes?.includes('CLUB_OWNER'), 'Counterpart did not read back the ownership handoff');
    const aAccess = await api(a, '/api/club/access/me', { clubId });
    assert.ok(!(aAccess.data?.permissions || []).includes('club:finance:read'), 'Former owner still has current club finance authority');
    assert.ok(!ok(await api(a, '/api/club/governance/list', { clubId })), 'Former owner can still manage governance');
    const afterA = await api(a, '/api/club/settlement/summary', { clubId, memberId: 9015, payeeId: 9015 });
    assert.ok(ok(afterA)); assert.deepEqual(scopeOnly(afterA.data), aMoney);
    report.readbacks.afterFirstTransfer = { former: scopeOnly(afterA.data), formerPermissions: aAccess.data?.permissions || [], newOwnerRoles: bAccess.data?.roleCodes || [] };
    assert.deepEqual(scopeOnly((await api(b, '/api/club/settlement/summary', { clubId, personal: true })).data), bMoney);
    await ownEntry(a, clubId); await a.shot('former-own-history-after-transfer');
    report.checks.push('9014 former own rows and UI detail allowed; 9015 rows excluded; governance denied');
    // Return through normal UI so 9016 remains an independently authenticated stranger throughout.
    report.transfers.push(await transferThroughUi(b, 9014, clubId));
    await a.login('e2e-openid-9014'); await b.login('e2e-openid-9015');
    assert.deepEqual(scopeOnly((await api(a, '/api/club/settlement/summary', { clubId, personal: true })).data), aMoney);
    assert.deepEqual(scopeOnly((await api(b, '/api/club/settlement/summary', { clubId, personal: true })).data), bMoney);
    assert.ok(!ok(await api(stranger, '/api/club/settlement/summary', { clubId })));
    report.readbacks.afterSecondTransfer = { former: scopeOnly((await api(a, '/api/club/settlement/summary', { clubId, personal: true })).data), current: scopeOnly((await api(b, '/api/club/settlement/summary', { clubId, personal: true })).data) };
    await runtimeSource(a, sha);
    report.checks.push('Two normal UI transfers A→B→A; personal amounts/row ownership unchanged; 9016 stays denied');
    report.status = report.notCovered.includes('different real club fixture missing') ? 'PARTIAL_UI_HTTP' : 'VERIFIED_UI_HTTP_ONLY';
    if (report.status === 'PARTIAL_UI_HTTP') process.exitCode = 2;
  } catch (error) {
    report.status = error.message.startsWith('PREREQUISITE:') ? 'UNVERIFIED_PREREQUISITE' : 'FAILED';
    report.reason = error.message;
    process.exitCode = report.status === 'FAILED' ? 1 : 2;
    for (let i = 0; i < sessions.length; i++) {
      try { await sessions[i].shot('stopping-point-' + ACTORS[i]); } catch { /* Keep the original failure and close all sessions. */ }
    }
  } finally {
    report.browsersClosed = (await Promise.allSettled(sessions.map(w => w.close()))).every(result => result.status === 'fulfilled');
    if (!report.browsersClosed) { report.status = 'FAILED_CLEANUP'; process.exitCode = 1; }
    fs.writeFileSync(path.join(out, 'readback.json'), JSON.stringify(report, null, 2), { mode: 0o600 });
    console.log(JSON.stringify({ status: report.status, reason: report.reason || '', proof: path.join(out, 'readback.json') }));
  }
}

await main();
