// E-B1:wx.request 的 fail 回调有两种事实 ——
//   · 请求根本没发出去(断网 / 域名解析失败 / 连接被拒 / 不在合法域名):服务端不可能执行过,可以安全重试;
//   · 已经发出去但没拿到回包(超时 / 连接中途断开 / 被中止):服务端可能已经执行,只能按「结果待确认」处理。
// 只有明确落在第一类的才标 intentNotExecuted;认不出的一律不标,保持待确认。
const NOT_SENT = /ERR_INTERNET_DISCONNECTED|ERR_NAME_NOT_RESOLVED|ERR_CONNECTION_REFUSED|ERR_ADDRESS_UNREACHABLE|ERR_PROXY_CONNECTION_FAILED|ERR_TUNNEL_CONNECTION_FAILED|url not in domain list|domain list|not in domain|offline/i;
const SENT_OR_UNKNOWN = /timeout|time out|ERR_CONNECTION_RESET|ERR_CONNECTION_CLOSED|ERR_CONNECTION_ABORTED|ERR_EMPTY_RESPONSE|ERR_TIMED_OUT|abort|interrupt|ERR_NETWORK_CHANGED/i;

/** fail 回调的入参(无 HTTP 状态码)能否证明请求没有送达 */
function requestNotSent(failData, status) {
  if (status != null && status !== '') return false;   // 有状态码 = 服务端回过话
  const msg = String((failData && failData.errMsg) || '');
  if (!msg || SENT_OR_UNKNOWN.test(msg)) return false;
  return NOT_SENT.test(msg);
}

/** 连接层失败的统一回包:证明没送达才带 intentNotExecuted,调用方才走 netFail 的「重试」分支 */
function connectionFailure(failData, status) {
  const response = { code: 'fail', netFail: true, msg: '网络异常，请重试' };
  if (requestNotSent(failData, status)) response.intentNotExecuted = true;
  return response;
}

module.exports = { requestNotSent, connectionFailure };
