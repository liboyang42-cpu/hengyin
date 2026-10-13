'use strict';

const assert = require('assert');
const path = require('path');
const test = require('node:test');
const {
  findConditionalChainErrors,
  findEscapedExpressionErrors,
  lintWxmlDirectory
} = require('../../scripts/wxml-conditional-lint');

test('WXML 表达式中的转义运算符会阻止编译，字符串和普通文案里的实体允许', () => {
  assert.equal(findEscapedExpressionErrors('<view wx:if="{{ready &amp;&amp; !done}}" />').length, 1);
  assert.equal(findEscapedExpressionErrors('<view hidden="{{count &gt; 1}}" />').length, 1);
  assert.equal(findEscapedExpressionErrors('<view wx:if="{{ready && !done}}">A &amp; B</view>').length, 0);
  assert.equal(findEscapedExpressionErrors(`<view>{{label || 'A &amp; B'}}</view>`).length, 0);
  assert.equal(findEscapedExpressionErrors(`<view>{{'A &amp; }}'}}</view>`).length, 0);
  assert.equal(findEscapedExpressionErrors(`<view>{{'}}' + (ready &amp;&amp; ok)}}</view>`).length, 1);
  assert.equal(findEscapedExpressionErrors(`<view>{{"}}" + (ready &amp;&amp; ok)}}</view>`).length, 1);
  assert.equal(findEscapedExpressionErrors('<!-- <view wx:if="{{a &amp;&amp; b}}" /> -->').length, 0);
});

test('WXML 条件链门禁拒绝孤立 wx:elif / wx:else', () => {
  const errors = findConditionalChainErrors([
    '<view wx:elif="{{ready}}"></view>',
    '<view></view>',
    '<view wx:else></view>'
  ].join('\n'));

  assert.deepStrictEqual(errors.map((error) => error.directive), ['wx:elif', 'wx:else']);
});

test('WXML 条件链允许同层 wx:if / wx:elif / wx:else，允许嵌套分支', () => {
  const errors = findConditionalChainErrors([
    '<block wx:if="{{outer}}">',
    '  <view wx:if="{{inner}}"></view>',
    '  <view wx:else></view>',
    '</block>',
    '<block wx:elif="{{fallback}}"></block>',
    '<block wx:else></block>'
  ].join('\n'));

  assert.deepStrictEqual(errors, []);
});

test('所有小程序 WXML 当前均没有孤立条件分支', () => {
  const root = path.resolve(__dirname, '../..');
  assert.deepStrictEqual(lintWxmlDirectory(root), []);
});
