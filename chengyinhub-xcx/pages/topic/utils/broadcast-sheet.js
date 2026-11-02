// 定向广播弹层的纯整形(2026-11-01 业主定稿,审阅页 65gFrW…):顶部横滑头像选人 + 范围 + 内容 + 发送。
// 不碰 setData / 网络,页面只管把结果写回去。
const OFF_TEXT = { NO_CONSENT: '不接收', TODAY_SENT: '今天已收到' };

function emptySheet() {
  return { show: false, state: '', rows: [], count: 0, scope: '', content: '', remaining: 0, sending: false, sent: false, skipped: 0, canSend: false, sendText: '发送', error: '' };
}

// 收件人 → 头像行。能不能收到只听服务端的 reach;没给 reach 的按不能发算(fail closed)。
function rowsFrom(recipients, pickedIds) {
  return (Array.isArray(recipients) ? recipients : []).map(function (r) {
    const off = r.reach !== 'OK';
    const id = String(r.memberId);
    return {
      id: id,
      name: r.name || '未留姓名',
      initial: String(r.name || '?').slice(0, 1),
      avatar: r.avatar || '',
      statusKey: r.arrived ? 'arrived' : 'pending',
      statusText: off ? (OFF_TEXT[r.reach] || '不接收') : (r.arrived ? '已核销' : '未到店'),
      off: off,
      picked: !off && (pickedIds ? pickedIds.indexOf(id) >= 0 : true),
    };
  });
}

function pickScope(rows, scope) {
  return rows.map(function (r) {
    const inScope = scope === 'all' || (scope === 'arrived' ? r.statusKey === 'arrived' : r.statusKey === 'pending');
    return Object.assign({}, r, { picked: !r.off && inScope });
  });
}

function toggleRow(rows, id) {
  return rows.map(function (r) { return r.id === id && !r.off ? Object.assign({}, r, { picked: !r.picked }) : r; });
}

function pickedIds(rows) {
  return rows.filter(function (r) { return r.picked; }).map(function (r) { return r.id; });
}

// 派生量一处算清:人数、能不能发、按钮写什么。发送按钮的话术全在这里。
function derive(sheet) {
  const rows = sheet.rows || [];
  const count = pickedIds(rows).length;
  const hasContent = !!String(sheet.content || '').trim();
  let sendText = count ? '发送给 ' + count + ' 人' : '先选收件人';
  if (sheet.state === 'loading' || sheet.state === 'error') sendText = '发送';
  else if (sheet.state === 'none') sendText = '没有可发送的人';
  else if (!sheet.remaining) sendText = '今天已发满 3 条';
  else if (sheet.sending) sendText = '发送中…';
  return Object.assign({}, sheet, {
    count: count,
    skipped: rows.filter(function (r) { return r.off; }).length,
    sendText: sendText,
    canSend: sheet.state === 'ready' && !sheet.sending && !!sheet.remaining && count > 0 && hasContent,
  });
}

module.exports = { emptySheet, rowsFrom, pickScope, toggleRow, pickedIds, derive };
