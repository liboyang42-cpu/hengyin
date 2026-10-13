'use strict'

const TAP_EVENTS = new Set(['bindtap', 'catchtap', 'bind:tap', 'catch:tap'])

function normalizeTapSelector(control) {
  const item = control || {}
  if (!TAP_EVENTS.has(item.event)) {
    throw new Error(`暂不支持以 tap 执行事件 ${item.event || '<empty>'}`)
  }
  const hint = String(item.selectorHint || '').trim()
  if (!hint) throw new Error('控件缺少 selectorHint')
  const eventSelector = /^([A-Za-z][\w-]*)\[(?:bind|catch):?tap\]$/.exec(hint)
  return eventSelector ? eventSelector[1] : hint
}

function normalizeControlSelector(control) {
  if (TAP_EVENTS.has(control && control.event)) return normalizeTapSelector(control)
  const hint = String(control && control.selectorHint || '').trim()
  if (!hint) throw new Error('控件缺少 selectorHint')
  const eventSelector = /^([A-Za-z][\w-]*)\[[^\]]+\]$/.exec(hint)
  return eventSelector ? eventSelector[1] : hint
}

function selectUniqueTapTarget(elements, action) {
  const matches = Array.isArray(elements) ? elements : []
  const id = action && action.id ? action.id : '<unknown>'
  if (matches.length === 0) throw new Error(`${id}: 未找到可点击控件`)
  if (matches.length !== 1) throw new Error(`${id}: selector 命中 ${matches.length} 个控件，拒绝猜测点击目标`)
  return matches[0]
}

function sameStack(left, right) {
  return JSON.stringify(left || []) === JSON.stringify(right || [])
}

function assertBaselineStable(first, second) {
  if (first.dataDigest !== second.dataDigest || first.domDigest !== second.domDigest
    || !sameStack(first.routeStack, second.routeStack)) {
    throw new Error('页面空跑基线仍在变化，无法把后续变化归因于本次点击')
  }
}

function assertInteractable(metrics) {
  const size = metrics && metrics.size ? metrics.size : {}
  if (!(Number(size.width) >= 44) || !(Number(size.height) >= 44)) throw new Error('控件可点击尺寸必须至少 44×44 CSS px')
  if (metrics.disabled === true || metrics.disabled === 'true' || metrics.disabled === '') throw new Error('控件处于 disabled 状态')
  if (metrics.hidden === true || metrics.hidden === 'true' || metrics.hidden === ''
      || metrics.display === 'none' || metrics.visibility === 'hidden'
      || (String(metrics.opacity || '').trim() && Number(metrics.opacity) === 0)
      || metrics.pointerEvents === 'none') throw new Error('控件当前不可见或不可交互')
  const offset = metrics.offset || {}
  const viewport = metrics.viewport || {}
  if (Number.isFinite(Number(offset.left)) && Number.isFinite(Number(offset.top))
      && Number.isFinite(Number(viewport.width)) && Number.isFinite(Number(viewport.height))) {
    const centerX = Number(offset.left) + Number(size.width) / 2
    const centerY = Number(offset.top) + Number(size.height) / 2
    const safe = viewport.safeArea || { left: 0, top: 0, right: viewport.width, bottom: viewport.height }
    if (centerX < Number(safe.left || 0) || centerX > Number(safe.right || viewport.width)
        || centerY < Number(safe.top || 0) || centerY > Number(safe.bottom || viewport.height)) {
      throw new Error('控件中心点不在当前视口安全区内')
    }
    const capsule = viewport.menuButton
    if (capsule && Number(offset.left) < Number(capsule.right) && Number(offset.left) + Number(size.width) > Number(capsule.left)
        && Number(offset.top) < Number(capsule.bottom) && Number(offset.top) + Number(size.height) > Number(capsule.top)) {
      throw new Error('控件触达区与微信胶囊按钮重叠')
    }
  }
}

function assertAccessible(action, metrics, expectation) {
  const control = (action && action.control) || {}
  const expected = (expectation && expectation.accessibility) || {}
  const tag = String(control.tag || '').toLowerCase()
  const physicalTag = String(metrics && metrics.tagName || tag).toLowerCase()
  const role = String(metrics && metrics.ariaRole || '').trim()
  const label = String(metrics && metrics.ariaLabel || '').trim()
  const accessibleName = label || String(metrics && metrics.text || '').trim()
  if (control.ariaLabel && label !== control.ariaLabel) throw new Error(`${action.id}: aria-label 与台账真源不一致`)
  if (control.ariaLabelExpression && (!String(expected.label || '').trim() || label !== String(expected.label))) {
    throw new Error(`${action.id}: 动态 aria-label 必须用运行时期望精确回读`)
  }
  if (control.ariaRoleExpression && (!String(expected.role || '').trim() || role !== String(expected.role))) {
    throw new Error(`${action.id}: 动态 aria-role 必须用运行时期望精确回读`)
  }
  if (['view', 'image', 'text'].includes(physicalTag) || /^cy-/.test(tag)) {
    if (!accessibleName) throw new Error(`${action.id}: 自定义可点击控件缺少可访问名称（aria-label 或可见文本）`)
    if (!['button', 'link', 'checkbox', 'switch', 'radio', 'tab'].includes(role)) {
      throw new Error(`${action.id}: 自定义可点击控件缺少合法 aria-role`)
    }
  }
  if ((metrics.disabled === true || metrics.disabled === 'true' || metrics.disabled === '')
      && metrics.ariaDisabled !== 'true') throw new Error(`${action.id}: disabled 未同步 aria-disabled`)
  if (['checkbox', 'switch', 'radio'].includes(role) && !['true', 'false', 'mixed'].includes(String(metrics.ariaChecked))) {
    throw new Error(`${action.id}: ${role} 缺少 aria-checked 状态`)
  }
  ;['ariaDisabled', 'ariaExpanded', 'ariaChecked'].forEach((key) => {
    const declared = control[key]
    if (declared != null && String(metrics[key]) !== String(declared)) throw new Error(`${action.id}: ${key} 与台账真源不一致`)
    const expression = control[`${key}Expression`]
    const expectationKey = key.replace(/^aria/, '').replace(/^./, (char) => char.toLowerCase())
    if (expression && (expected[expectationKey] == null || String(metrics[key]) !== String(expected[expectationKey]))) {
      throw new Error(`${action.id}: 动态 ${key} 必须用运行时期望精确回读`)
    }
  })
}

function assertNoCoveringOverlay(action, overlays) {
  if (Array.isArray(overlays) && overlays.length) {
    throw new Error(`${action.id}: 点击中心被更高层 fixed overlay 遮挡: ${overlays.map((item) => item.tag || item.id || 'unknown').join(',')}`)
  }
}

function readPath(source, dottedPath) {
  return String(dottedPath).split('.').reduce((value, key) => (value == null ? undefined : value[key]), source)
}

function lastRoute(stack) {
  const value = (stack || []).at(-1)
  return typeof value === 'string' ? { path: value, query: {} } : (value || { path: '', query: {} })
}

function equalValue(left, right) {
  return JSON.stringify(left) === JSON.stringify(right)
}

function judgeObservedEffect(action, before, after, expectation) {
  const kinds = action.effectClasses && action.effectClasses.length
    ? action.effectClasses : [action.baseActionClass || action.actionClass]
  const expected = expectation || {}
  const changed = {
    data: before.dataDigest !== after.dataDigest,
    dom: before.domDigest !== after.domDigest,
    routeStack: !sameStack(before.routeStack, after.routeStack),
  }
  const assertions = {}
  if (kinds.includes('navigation') && !changed.routeStack) {
    throw new Error(`${action.id}: 点击后页面栈没有变化，未进入下一步`)
  }
  if (kinds.includes('navigation')) {
    const declaredRoutes = action.targets && Array.isArray(action.targets.routes) ? action.targets.routes : []
    const expectedRoute = String(expected.route || (declaredRoutes.length === 1 ? declaredRoutes[0] : '')).replace(/^\//, '')
    if (!expectedRoute) throw new Error(`${action.id}: navigation 缺少唯一目标 route 断言`)
    const actual = lastRoute(after.routeStack)
    if (String(actual.path || '').replace(/^\//, '') !== expectedRoute) {
      throw new Error(`${action.id}: 实际目标 route ${actual.path || '<empty>'} 不符合预期 ${expectedRoute}`)
    }
    Object.entries(expected.query || {}).forEach(([key, value]) => {
      if (!equalValue((actual.query || {})[key], value)) throw new Error(`${action.id}: route option ${key} 不符合预期`)
    })
    assertions.navigation = { expectedRoute, expectedQuery: expected.query || {}, actual }
  }
  if (kinds.includes('state')) {
    const stateExpectation = expected.data
    if (!stateExpectation || typeof stateExpectation !== 'object' || Object.keys(stateExpectation).length === 0) {
      throw new Error(`${action.id}: state 动作必须提供精确状态断言`)
    }
    const entries = Object.entries(stateExpectation)
    if (!entries.some(([key, value]) => !equalValue(readPath(before.data, key), value))) {
      throw new Error(`${action.id}: 点击前已是预期值，无法证明本次 tap 生效`)
    }
    entries.forEach(([key, value]) => {
      if (!equalValue(readPath(after.data, key), value)) throw new Error(`${action.id}: ${key} 不符合预期`)
    })
    assertions.state = {
      expected: stateExpectation,
      observed: Object.fromEntries(Object.keys(stateExpectation).map((key) => [key, readPath(after.data, key)])),
    }
  }
  const authorityKind = kinds.includes('write') ? 'write' : (kinds.includes('read') ? 'read' : null)
  if (authorityKind && !after.authoritativeReadback) {
    throw new Error(`${action.id}: ${authorityKind} 动作缺少服务端权威回读`)
  }
  if (kinds.includes('read')) {
    const stateExpectation = expected.data
    if (!stateExpectation || typeof stateExpectation !== 'object' || Object.keys(stateExpectation).length === 0) {
      throw new Error(`${action.id}: read 动作必须声明响应映射后的 page.data 精确断言`)
    }
    Object.entries(stateExpectation).forEach(([key, value]) => {
      if (!equalValue(readPath(after.data, key), value)) throw new Error(`${action.id}: read 映射 ${key} 不符合预期`)
    })
    assertions.read = { expected: stateExpectation }
  }
  if (kinds.includes('other')) {
    if (expected.data && typeof expected.data === 'object' && Object.keys(expected.data).length) {
      const entries = Object.entries(expected.data)
      if (!entries.some(([key, value]) => !equalValue(readPath(before.data, key), value))) {
        throw new Error(`${action.id}: other 点击前已是预期值`)
      }
      entries.forEach(([key, value]) => {
        if (!equalValue(readPath(after.data, key), value)) throw new Error(`${action.id}: other ${key} 不符合预期`)
      })
      assertions.other = { type: 'data', expected: expected.data }
    } else if (expected.route) {
      const actual = lastRoute(after.routeStack)
      if (!changed.routeStack || String(actual.path || '').replace(/^\//, '') !== String(expected.route).replace(/^\//, '')) {
        throw new Error(`${action.id}: other 未进入声明 route`)
      }
      assertions.other = { type: 'route', expectedRoute: expected.route, actual }
    } else {
      throw new Error(`${action.id}: other 动作必须声明精确 data 或 route 断言`)
    }
  }
  return {
    changed,
    assertions,
    routeStack: after.routeStack || [],
    authoritativeReadback: after.authoritativeReadback || null,
  }
}

function judgeFailureEffect(action, before, after, expectation, branch) {
  const expected = expectation && expectation.branches && expectation.branches[branch]
  if (!expected || !expected.data || typeof expected.data !== 'object' || !Object.keys(expected.data).length
      || !expected.recovery || typeof expected.recovery !== 'object') {
    throw new Error(`${action.id}: ${branch} 必须声明 branches.${branch}.data 与 recovery 精确终态`)
  }
  let enteredFailureState = false
  Object.entries(expected.data).forEach(([key, value]) => {
    if (!equalValue(readPath(after.data, key), value)) throw new Error(`${action.id}: ${branch} 终态 ${key} 不符合预期`)
    if (!equalValue(readPath(before.data, key), value)) enteredFailureState = true
  })
  if (!enteredFailureState) throw new Error(`${action.id}: 点击前已是声明的失败终态，不能证明本次交互产生了异常反馈`)
  const routeChanged = !sameStack(before.routeStack, after.routeStack)
  if (expected.route) {
    const actual = lastRoute(after.routeStack)
    if (String(actual.path || '').replace(/^\//, '') !== String(expected.route).replace(/^\//, '')) {
      throw new Error(`${action.id}: ${branch} 未进入声明恢复 route`)
    }
  } else if (routeChanged) {
    throw new Error(`${action.id}: ${branch} 发生未声明的页面跳转`)
  }
  if (!expected.recovery.selector && expected.recovery.dismissed !== true) {
    throw new Error(`${action.id}: ${branch} recovery 必须声明 selector 或 dismissed=true`)
  }
  return {
    changed: {
      data: before.dataDigest !== after.dataDigest,
      dom: before.domDigest !== after.domDigest,
      routeStack: routeChanged,
    },
    assertions: { failure: { branch, expected } },
    routeStack: after.routeStack || [],
    authoritativeReadback: after.authoritativeReadback || null,
  }
}

module.exports = {
  assertAccessible,
  assertNoCoveringOverlay,
  assertBaselineStable,
  assertInteractable,
  judgeObservedEffect,
  judgeFailureEffect,
  normalizeTapSelector,
  normalizeControlSelector,
  selectUniqueTapTarget,
}
