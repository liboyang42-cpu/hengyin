const test = require('node:test');
const assert = require('node:assert/strict');
const invoice = require('../../subpackageMember/utils/user-invoice.js');

test('invoice IDs retain the complete positive Java Long domain and reject rounded numeric IDs', () => {
  for (const value of ['1', '9007199254740993', '9223372036854775807']) assert.equal(invoice.exactId(value), value);
  assert.equal(invoice.exactId(42), '42');
  for (const value of [9007199254740992, 0, -1, NaN, '0', '01', '1e3', '1.0', '9223372036854775808', ' 7', {}, null]) {
    assert.equal(invoice.exactId(value), '');
  }
});

test('title projection accepts masked tax only, drops raw tax and requires reentry on company edits', () => {
  const row = invoice.title({ id: '9', titleName: '公司', titleType: 'COMPANY', taxNoMasked: '91******1234', taxNo: '913100001234567890' });
  assert.equal(Object.hasOwn(row, 'taxNo'), false);
  assert.throws(() => invoice.title({ ...row, taxNoMasked: '913100001234567890' }));
  assert.throws(() => invoice.titlePayload({ ...row, taxNo: row.taxNoMasked }));
  assert.throws(() => invoice.titlePayload({ ...row, taxNo: '' }));
  assert.equal(invoice.titlePayload({ ...row, taxNo: '91310000abcde12345' }).taxNo, '91310000ABCDE12345');
  assert.equal(Object.hasOwn(invoice.titlePayload({ titleType: 'PERSONAL', titleName: '我', taxNo: '旧税号' }), 'taxNo'), false);
});

test('write outcome never turns transport failure or unbound HTTP 200 into a completed application', () => {
  const decode = value => { if (!value || value.requestId !== 'original') throw Error('mismatch'); return value; };
  assert.equal(invoice.classify({ code: 200, data: {} }, 200, decode).status, 'unknown');
  assert.equal(invoice.classify({ code: 200, data: { requestId: 'original' } }, 503, decode).status, 'unknown');
  assert.equal(invoice.classify({ code: 500, errorCode: 'INVOICE_REQUEST_IN_PROGRESS' }, 200, decode).status, 'unknown');
  assert.equal(invoice.classify({ code: 500, errorCode: 'INVOICE_REQUEST_REUSED' }, 200, decode).status, 'unknown');
  assert.equal(invoice.classify({ code: 500, errorCode: 'INVOICE_SOMETHING_NEW' }, 200, decode).status, 'unknown');
  assert.equal(invoice.classify({ code: 500, errorCode: 'INVOICE_ORDER_NOT_ELIGIBLE' }, 200, decode).status, 'failed');
  assert.equal(invoice.classify({ code: 500, errorCode: 'INVOICE_FEATURE_OFF' }, 503, decode).status, 'failed');
  assert.equal(invoice.classify({ code: 200, data: { requestId: 'original' } }, 200, decode).status, 'success');
  assert.equal(invoice.classify({ code: 200, data: { requestId: 'original' } }, 200, decode, true).status, 'unknown');
});

test('application projection rejects unknown statuses and incomplete or duplicate order receipts', () => {
  const row = { id: '9', requestId: 'original', applicationNo: 'INV9', status: 'SUBMITTED', totalAmount: '2.00',
    orderCount: 1, orders: [{ orderId: '8', amount: '2.00' }] };
  assert.equal(invoice.application(row).orders[0].orderId, '8');
  for (const changed of [{ status: 'toString' }, { orderCount: 0 }, { orderCount: '1' },
    { orderCount: 2 }, { orderCount: 2, orders: [row.orders[0], row.orders[0]] }]) {
    assert.throws(() => invoice.application({ ...row, ...changed }));
  }
});


test('list summaries accept the backend empty order array but never weaken detail receipts', () => {
  const row = { id: '9', requestId: 'original', applicationNo: 'INV9', status: 'SUBMITTED', totalAmount: '2.00',
    orderCount: 2, orders: [] };
  assert.equal(invoice.applicationSummary(row).orderCount, 2);
  assert.throws(() => invoice.application(row));
  for (const changed of [{ orderCount: 0 }, { orderCount: 51 }, { orders: null },
    { orders: [{ orderId: '8', amount: '1.00' }] }, { requestId: '' }]) {
    assert.throws(() => invoice.applicationSummary({ ...row, ...changed }));
  }
});
