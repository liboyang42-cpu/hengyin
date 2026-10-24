'use strict';

/**
 * 服务端文本过滤器(chengyinhub-common HTMLFilter,经 XssFilter 作用于 /api/* 的 POST/PUT/PATCH 请求体)
 * 会把紧跟字母的文本 '<' 存成 &lt;(如 x<y → x&lt;y),个别情形还会出 &gt; / &amp; / &quot;。
 * 小程序用普通 WXML 文本绑定展示用户文字,不解码就会把 &lt; 原样显示出来。
 *
 * 只用于**纯文本**展示(text/view 的 {{}} 绑定、input/textarea 的回填值):
 * 不得把解码结果喂给 rich-text 节点或任何按 HTML 解析的接口。
 * 只认这五个字符的命名实体与对应的十进制/十六进制数字实体,单遍解码:
 * &amp;lt; → &lt;(不会再变成 '<');其它实体与非字符串原样返回。
 */
const NAMED = { lt: '<', gt: '>', amp: '&', quot: '"', '#39': "'" };
const NUMERIC = { 60: '<', 62: '>', 38: '&', 34: '"', 39: "'" };
const ENTITY = /&(?:(lt|gt|amp|quot|#39)|#0*(60|62|38|34|39)|#[xX]0*(3[cCeE]|26|22|27));/g;

function decodeDisplayText(value) {
  if (typeof value !== 'string' || value.indexOf('&') < 0) return value;
  return value.replace(ENTITY, function (match, name, dec, hex) {
    if (name) return NAMED[name];
    if (dec) return NUMERIC[dec];
    return NUMERIC[parseInt(hex, 16)];
  });
}

/** 返回浅拷贝,只解码列出的字符串字段;其余字段与原对象一致。 */
function decodeDisplayFields(row, fields) {
  if (!row || typeof row !== 'object') return row;
  const out = Object.assign({}, row);
  (fields || []).forEach(function (key) {
    if (typeof out[key] === 'string') out[key] = decodeDisplayText(out[key]);
  });
  return out;
}

module.exports = { decodeDisplayText, decodeDisplayFields };
