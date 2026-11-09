'use strict';
// U03-01/02(§23.1):主题发布意图号与返回的内容修订身份。
//  - 发布号在请求发出前随 _publishAttempt 落盘;结果未知时重试沿用同一个号,服务端同号同 payload 只回原修订。
//  - 修订身份只展示服务端回包里的 contentRevisionNo;旧内容回包为空就不显示,不补「1」。
const RE = /^[A-Za-z0-9_-]{16,64}$/;

function valid(value) { return typeof value === 'string' && RE.test(value); }

function create() {
  let rand = '';
  while (rand.length < 20) rand += Math.random().toString(36).slice(2);
  return ('pub' + Date.now().toString(36) + rand).slice(0, 40);
}

/** 原发布请求要带的字段:落盘的版本号 + 发布号(没有合法号的旧记录不补号)。 */
function wireFields(attempt) {
  const out = {};
  if (!attempt || typeof attempt !== 'object') return out;
  if (attempt.configVersion != null) out.configVersion = attempt.configVersion;
  if (attempt.registryVersion != null) out.registryVersion = attempt.registryVersion;
  if (valid(attempt.publishRequestId)) out.publishRequestId = attempt.publishRequestId;
  return out;
}

function revisionLabel(published) {
  const no = published && published.contentRevisionNo;
  if (no == null || !/^[1-9][0-9]{0,17}$/.test(String(no))) return '';
  return '内容修订 #' + String(no);
}

module.exports = { valid, create, wireFields, revisionLabel };
