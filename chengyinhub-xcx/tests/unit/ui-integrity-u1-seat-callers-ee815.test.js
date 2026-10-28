'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { parseJavaScript } = require('../../scripts/lib/xcx-scan');
const baseline = require('../lint/ui-integrity-baseline');
const { resolveVerificationScan } = require('../../utils/verification-scan');
const ROOT = path.resolve(__dirname, '../..');
const read = relative => fs.readFileSync(path.join(ROOT, relative), 'utf8');
const PROFILE = 'subpackageMember/components/cy/profile/index.js';
const DETAIL = 'subpackageMember/components/scene-member-participation-detail/index.js';
const routes = [
  '/api/coupon/verification', '/api/registration/scan_dynamic_code',
  '/api/registration/scan_qr_code', '/api/verify/groupcode/redeem',
];
function nodes(source, predicate) {
  const found = [];
  function visit(value) {
    if (!value || typeof value !== 'object') return;
    if (value.type && predicate(value)) found.push(value);
    for (const child of Object.values(value)) {
      if (Array.isArray(child)) child.forEach(visit);
      else if (child && typeof child === 'object') visit(child);
    }
  }
  visit(parseJavaScript(source));
  return found;
}
const named = (node, name) => node && !node.computed && node.type === 'MemberExpression'
  && node.property.name === name;
function props(node) {
  assert.equal(node && node.type, 'ObjectExpression');
  assert.ok(node.properties.every(p => p.type === 'Property' && !p.computed));
  return new Map(node.properties.map(p => [p.key.name || p.key.value, p.value]));
}
function assertFeedbackOnly(source, input) {
  const sends = nodes(source, n => n.type === 'CallExpression' && named(n.callee, 'sendRequest'));
  const clones = sends.filter(n => {
    const arg = n.arguments[0];
    return arg && arg.type === 'CallExpression' && named(arg.callee, 'assign')
      && arg.callee.object.name === 'Object' && arg.arguments[1] && arg.arguments[1].name === input;
  });
  assert.equal(clones.length, 1);
  const args = clones[0].arguments[0].arguments;
  assert.equal(args.length, 3);
  assert.equal(props(args[0]).size, 0);
  const feedback = props(args[2]);
  for (const protectedKey of ['url', 'method', 'data']) assert.equal(feedback.has(protectedKey), false);
  for (const key of ['success', 'fail', 'successStatusAbnormal']) assert.ok(feedback.has(key));
}
function assertLead(source) {
  const owners = nodes(source, n => n.type === 'Property' && n.key.name === 'leadVerifyMemberTicket');
  assert.equal(owners.length, 1);
  const body = source.slice(owners[0].value.body.start, owners[0].value.body.end);
  const requests = nodes('function check() ' + body, n => n.type === 'VariableDeclarator' && n.id.name === 'request');
  assert.equal(requests.length, 1);
  const request = props(requests[0].init);
  assert.equal(request.get('url').value, '/api/registration/scan_group_member_ticket');
  assert.equal(request.get('method').value, 'POST');
  assert.match(body, /data:\s*\{\s*code:\s*ticket\.code,\s*activityId:\s*context\.activityId\s*\}/);
  const sends = nodes('function check() ' + body, n => n.type === 'CallExpression' && named(n.callee, 'sendRequest'));
  assert.equal(sends.length, 1);
  const sent = props(sends[0].arguments[0]);
  assert.equal(sends[0].arguments[0].properties.length, sent.size, '重复键不能覆盖原路径或回调');
  assert.equal(sent.get('url').value, '/api/registration/scan_group_member_ticket');
  assert.equal(sent.get('method').value, 'POST');
  assert.equal(body.includes('data: request.data,'), true);
  for (const key of ['success', 'fail', 'successStatusAbnormal']) assert.ok(sent.has(key));
  assert.equal(sent.get('autoErrorToast').value, false);
  assert.equal(sent.has('silentError'), false);
  assert.match(body, /loginCurrent\(app,\s*wx,\s*seatLogin\)/);
  assert.match(body, /seatRecovery\(app,\s*target,\s*seatCurrent\)/);
  assert.match(body, /_leadVerificationWorkflow\.run\(key/);
}
function assertCallers(source, expected, scanKind) {
  assert.match(source, /require\(['"][^'"]*utils\/verification-scan(?:\.js)?['"]\)/);
  const scans = nodes(source, n => n.type === 'VariableDeclaration' && n.kind === scanKind)
    .flatMap(n => n.declarations).filter(n => n.id.name === 'scan' && n.init
      && n.init.type === 'CallExpression' && n.init.callee.name === 'resolveVerificationScan');
  assert.equal(scans.length, 1);
  assert.equal(scans[0].init.arguments.length, 1);
  const input = scans[0].init.arguments[0];
  assert.ok(named(input, 'result') && input.object.name === 'res');
  assert.match(source, /if \(scan\.kind === 'invalid'\) \{\s*cyToast\(scan\.message\);\s*return;\s*\}/);
  const calls = nodes(source, n => n.type === 'CallExpression' && named(n.callee, '_submitVerification'));
  assert.equal(calls.length, expected.length);
  const actual = calls.map(call => {
    assert.ok(call.callee.object.type === 'ThisExpression' || call.callee.object.name === 'that');
    const options = props(call.arguments[2]);
    assert.equal(options.get('method').value, 'POST');
    const url = options.get('url');
    if (url.type === 'Literal') return url.value;
    if (url.type === 'Identifier' && url.name === 'url') {
      const declarations = nodes(source, n => n.type === 'VariableDeclarator' && n.id.name === 'url'
        && n.init && n.init.type === 'ConditionalExpression');
      assert.equal(declarations.length, 1);
      const values = [];
      let choice = declarations[0].init;
      while (choice.type === 'ConditionalExpression') {
        assert.equal(choice.test.type, 'BinaryExpression'); assert.equal(choice.test.operator, '===');
        assert.equal(source.slice(choice.test.left.start, choice.test.left.end), 'scan.url');
        assert.equal(choice.test.right.type, 'Literal'); assert.equal(choice.consequent.type, 'Literal');
        assert.equal(choice.test.right.value, choice.consequent.value);
        values.push(choice.consequent.value); choice = choice.alternate;
      }
      assert.equal(choice.type, 'Literal'); values.push(choice.value);
      assert.deepEqual(values.sort(), routes);
      assert.match(source, /if \(!\[[\s\S]*?\]\.includes\(scan\.url\)\) \{\s*cyToast\('二维码格式错误'\);\s*return;\s*\}/);
      assert.equal(source.slice(options.get('data').start, options.get('data').end), 'scan.data');
      return 'finite-scan';
    }
    assert.equal(source.slice(url.start, url.end), 'scan.url');
    assert.equal(source.slice(options.get('data').start, options.get('data').end), 'scan.data');
    return 'scan.url';
  });
  assert.deepEqual(actual.sort(), expected.slice().sort());
  assertFeedbackOnly(source, 'options');
  assert.match(source, /loginCurrent\(app,\s*wx,\s*login\)/);
  assert.match(source, /target \? seatRecovery\(app,\s*target,\s*seatCurrent\) : undefined/);
}
test('U1只保留原profile请求登记，带队与详情有限路径不保留旧豁免', () => {
  for (const [file, line, statement] of [
    [PROFILE, 676, 'app.sendRequest(Object.assign({}, options'],
  ]) {
    const key = 'chengyinhub-xcx/' + file + ':' + line;
    assert.ok(baseline.u1Variable[key], key);
    assert.ok(read(file).split('\n')[line - 1].includes(statement), key);
  }
  for (const file of ['pages/play/index.js', DETAIL]) {
    assert.equal(Object.keys(baseline.u1Variable).some(key => key.startsWith('chengyinhub-xcx/' + file + ':')), false);
  }
  assert.equal(baseline.u1Variable['chengyinhub-xcx/' + PROFILE + ':663'], undefined);
  assert.equal(baseline.u1Variable['chengyinhub-xcx/' + DETAIL + ':190'], undefined);
});
test('带队复制的原命令固定席位路径且feedback不能改身份；改路径或覆盖url必须红', () => {
  const source = read('pages/play/index.js');
  assertLead(source);
  assert.throws(() => assertLead(source.replace("const request = { url: '/api/registration/scan_group_member_ticket'",
    "const request = { url: '/api/registration/scan_qr_code'")));
  assert.throws(() => assertLead(source.replace('hideLoading: true, autoErrorToast: false,\n            success: r =>',
    "url: '/api/registration/scan_qr_code', hideLoading: true, autoErrorToast: false,\n            success: r =>")));
});
test('共享扫码仅四个固定端点，外部URL和未知类型不成为请求；新增端点必须红', () => {
  function assertRoutes(source) {
    const actual = [...new Set([...source.matchAll(/['"](\/api\/[^'"]+)['"]/g)].map(m => m[1]))].sort();
    assert.deepEqual(actual, routes);
  }
  const source = read('utils/verification-scan.js');
  assertRoutes(source);
  assert.throws(() => assertRoutes(source.replace("'/api/verify/groupcode/redeem'", "'/api/unregistered/write'")));
  assert.deepEqual([
    resolveVerificationScan('v1.1.group_1.1.n.s').url,
    resolveVerificationScan('v1.1.activity_seat_2.1.n.s').url,
    resolveVerificationScan('cq1.1.1.sig').url,
    resolveVerificationScan(JSON.stringify({ type: 'activity', code: 'original' })).url,
  ].sort(), routes);
  for (const raw of ['https://example.invalid/api/arbitrary', 'v1.1.unknown.1.n.s',
    JSON.stringify({ type: 'unknown', code: 'x', url: '/api/arbitrary' })]) {
    assert.equal(resolveVerificationScan(raw).kind, 'invalid');
    assert.equal(resolveVerificationScan(raw).url, undefined);
  }
});
test('个人页三caller及参与详情唯一caller闭包固定，破坏guard/输入来源/请求覆盖必须红', () => {
  const profile = read(PROFILE), detail = read(DETAIL);
  const profileExpected = ['scan.url', '/api/registration/scan_qr_code_chapter', '/api/registration/scan_qr_code_station'];
  assertCallers(profile, profileExpected, 'var');
  assertCallers(detail, ['finite-scan'], 'const');
  assert.throws(() => assertCallers(profile.replace('url: scan.url,', 'url: supplied.url,'), profileExpected, 'var'));
  assert.throws(() => assertCallers(detail.replace("if (scan.kind === 'invalid')", "if (false)"), ['finite-scan'], 'const'));
  assert.throws(() => assertCallers(detail.replace("const url = scan.url", "const url = supplied.url"), ['finite-scan'], 'const'));
  assert.throws(() => assertCallers(profile.replace('autoErrorToast: false,\n          hideLoading:',
    "url: '/api/arbitrary', autoErrorToast: false,\n          hideLoading:"), profileExpected, 'var'));
});
