'use strict';
// 节点核验方式码表的唯一真源(审核清单 §7-7:四处副本只到 5,码 6「偏好题组」渲染成空串)。
// 与后端 validation_method 一致;新增一档只改这里。
// 码 1 的名字:2026-09-06 用户裁决「文字暗号」「文字问答」都不对,统一叫「文字作答」——
// 玩家端实际提示是「输入你在现场观察或确认到的内容」:既不要求保密(不是暗号),
// 也不是开放问答(是把现场看到的写下来),而且和兄弟项「选项问答」读起来不重复。
const VALIDATION_METHOD_LABELS = {
  0: '无需验证', 1: '文字作答', 2: '拍照打卡', 3: '选项问答', 4: '到店扫码', 5: 'GPS 到达', 6: '偏好题组',
  7: '传感器挑战',   // App 专属(ValidationMethod.APP_SENSOR),小程序只展示不可玩
};
// 码值常量:与后端 com.chengyinhub.common.constant.ValidationMethod 同值(B:P1-067 魔法值整理),
// 由 tests/unit/validation-method-codes-contract.test.js 对着 Java 源码钉住。
const VM = Object.freeze({
  NONE: 0, TEXT: 1, PHOTO: 2, CHOICE: 3, QR: 4, GPS: 5, PREFERENCE_GROUP: 6, APP_SENSOR: 7,
  ESTIMATE: 8, PRICE_PAIR: 9, HIDDEN_OBJECT: 10,
});
function validationMethodLabel(code, fallback) {
  // 没给码(null/空串)不是「无需验证」,是「还没配」——走 fallback,别被 Number('') === 0 骗过去
  const label = (code === null || code === undefined || code === '') ? undefined : VALIDATION_METHOD_LABELS[Number(code)];
  return label === undefined ? (fallback === undefined ? '' : fallback) : label;
}
module.exports = { VALIDATION_METHOD_LABELS, VM, validationMethodLabel };
