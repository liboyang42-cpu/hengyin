import { MAX_RECENT_VIEWS, hasEntityId, getViewKey } from '@/utils/tagsView'

const toVisitedView = view => {
  const title = view.title || (view.meta && view.meta.title) || 'no-name'
  const context = hasEntityId(view) ? `#${view.query.id}` : ''
  return Object.assign({}, view, { title, context, tagKey: getViewKey(view) })
}

const isAffix = view => view.meta && view.meta.affix

const trimRecentViews = state => {
  const recentViews = state.visitedViews.filter(view => !isAffix(view))
  while (recentViews.length > MAX_RECENT_VIEWS) {
    const oldest = recentViews.shift()
    const index = state.visitedViews.findIndex(view => getViewKey(view) === getViewKey(oldest))
    if (index > -1) {
      state.visitedViews.splice(index, 1)
    }
  }
}

const state = {
  visitedViews: [],
  cachedViews: [],
  iframeViews: []
}

const mutations = {
  ADD_IFRAME_VIEW: (state, view) => {
    if (state.iframeViews.some(v => v.path === view.path)) return
    state.iframeViews.push(
      Object.assign({}, view, {
        title: view.meta.title || 'no-name'
      })
    )
  },
  ADD_VISITED_VIEW: (state, view) => {
    const index = state.visitedViews.findIndex(item => getViewKey(item) === getViewKey(view))
    const nextView = toVisitedView(view)
    if (index === -1) {
      state.visitedViews.push(nextView)
      trimRecentViews(state)
      return
    }

    const currentView = state.visitedViews[index]
    if (isAffix(currentView)) {
      Object.assign(currentView, nextView)
      return
    }
    state.visitedViews.splice(index, 1)
    state.visitedViews.push(Object.assign({}, currentView, nextView))
  },
  ADD_CACHED_VIEW: (state, view) => {
    if (hasEntityId(view) || state.cachedViews.includes(view.name)) return
    if (view.meta && !view.meta.noCache) {
      state.cachedViews.push(view.name)
    }
  },
  DEL_VISITED_VIEW: (state, view) => {
    for (const [i, v] of state.visitedViews.entries()) {
      if (getViewKey(v) === getViewKey(view)) {
        state.visitedViews.splice(i, 1)
        break
      }
    }
    state.iframeViews = state.iframeViews.filter(item => item.path !== view.path)
  },
  DEL_IFRAME_VIEW: (state, view) => {
    state.iframeViews = state.iframeViews.filter(item => item.path !== view.path)
  },
  DEL_CACHED_VIEW: (state, view) => {
    const index = state.cachedViews.indexOf(view.name)
    index > -1 && state.cachedViews.splice(index, 1)
  },

  DEL_OTHERS_VISITED_VIEWS: (state, view) => {
    state.visitedViews = state.visitedViews.filter(v => {
      return isAffix(v) || getViewKey(v) === getViewKey(view)
    })
    state.iframeViews = state.iframeViews.filter(item => item.path === view.path)
  },
  DEL_OTHERS_CACHED_VIEWS: (state, view) => {
    const index = state.cachedViews.indexOf(view.name)
    if (index > -1) {
      state.cachedViews = state.cachedViews.slice(index, index + 1)
    } else {
      state.cachedViews = []
    }
  },
  DEL_ALL_VISITED_VIEWS: state => {
    // keep affix tags
    const affixTags = state.visitedViews.filter(tag => tag.meta.affix)
    state.visitedViews = affixTags
    state.iframeViews = []
  },
  DEL_ALL_CACHED_VIEWS: state => {
    state.cachedViews = []
  },
  UPDATE_VISITED_VIEW: (state, view) => {
    for (const currentView of state.visitedViews) {
      if (getViewKey(currentView) === getViewKey(view)) {
        Object.assign(currentView, toVisitedView(view))
        break
      }
    }
  },
  DEL_RIGHT_VIEWS: (state, view) => {
    const index = state.visitedViews.findIndex(v => getViewKey(v) === getViewKey(view))
    if (index === -1) {
      return
    }
    state.visitedViews = state.visitedViews.filter((item, idx) => {
      if (idx <= index || isAffix(item)) {
        return true
      }
      const i = state.cachedViews.indexOf(item.name)
      if (i > -1) {
        state.cachedViews.splice(i, 1)
      }
      if(item.meta.link) {
        const fi = state.iframeViews.findIndex(v => v.path === item.path)
        state.iframeViews.splice(fi, 1)
      }
      return false
    })
  },
  DEL_LEFT_VIEWS: (state, view) => {
    const index = state.visitedViews.findIndex(v => getViewKey(v) === getViewKey(view))
    if (index === -1) {
      return
    }
    state.visitedViews = state.visitedViews.filter((item, idx) => {
      if (idx >= index || isAffix(item)) {
        return true
      }
      const i = state.cachedViews.indexOf(item.name)
      if (i > -1) {
        state.cachedViews.splice(i, 1)
      }
      if(item.meta.link) {
        const fi = state.iframeViews.findIndex(v => v.path === item.path)
        state.iframeViews.splice(fi, 1)
      }
      return false
    })
  }
}

const actions = {
  addView({ dispatch }, view) {
    dispatch('addVisitedView', view)
    dispatch('addCachedView', view)
  },
  addIframeView({ commit }, view) {
    commit('ADD_IFRAME_VIEW', view)
  },
  addVisitedView({ commit }, view) {
    commit('ADD_VISITED_VIEW', view)
  },
  addCachedView({ commit }, view) {
    commit('ADD_CACHED_VIEW', view)
  },
  delView({ dispatch, state }, view) {
    return new Promise(resolve => {
      dispatch('delVisitedView', view)
      dispatch('delCachedView', view)
      resolve({
        visitedViews: [...state.visitedViews],
        cachedViews: [...state.cachedViews]
      })
    })
  },
  delVisitedView({ commit, state }, view) {
    return new Promise(resolve => {
      commit('DEL_VISITED_VIEW', view)
      resolve([...state.visitedViews])
    })
  },
  delIframeView({ commit, state }, view) {
    return new Promise(resolve => {
      commit('DEL_IFRAME_VIEW', view)
      resolve([...state.iframeViews])
    })
  },
  delCachedView({ commit, state }, view) {
    return new Promise(resolve => {
      commit('DEL_CACHED_VIEW', view)
      resolve([...state.cachedViews])
    })
  },
  delOthersViews({ dispatch, state }, view) {
    return new Promise(resolve => {
      dispatch('delOthersVisitedViews', view)
      dispatch('delOthersCachedViews', view)
      resolve({
        visitedViews: [...state.visitedViews],
        cachedViews: [...state.cachedViews]
      })
    })
  },
  delOthersVisitedViews({ commit, state }, view) {
    return new Promise(resolve => {
      commit('DEL_OTHERS_VISITED_VIEWS', view)
      resolve([...state.visitedViews])
    })
  },
  delOthersCachedViews({ commit, state }, view) {
    return new Promise(resolve => {
      commit('DEL_OTHERS_CACHED_VIEWS', view)
      resolve([...state.cachedViews])
    })
  },
  delAllViews({ dispatch, state }, view) {
    return new Promise(resolve => {
      dispatch('delAllVisitedViews', view)
      dispatch('delAllCachedViews', view)
      resolve({
        visitedViews: [...state.visitedViews],
        cachedViews: [...state.cachedViews]
      })
    })
  },
  delAllVisitedViews({ commit, state }) {
    return new Promise(resolve => {
      commit('DEL_ALL_VISITED_VIEWS')
      resolve([...state.visitedViews])
    })
  },
  delAllCachedViews({ commit, state }) {
    return new Promise(resolve => {
      commit('DEL_ALL_CACHED_VIEWS')
      resolve([...state.cachedViews])
    })
  },
  updateVisitedView({ commit }, view) {
    commit('UPDATE_VISITED_VIEW', view)
  },
  delRightTags({ commit }, view) {
    return new Promise(resolve => {
      commit('DEL_RIGHT_VIEWS', view)
      resolve([...state.visitedViews])
    })
  },
  delLeftTags({ commit }, view) {
    return new Promise(resolve => {
      commit('DEL_LEFT_VIEWS', view)
      resolve([...state.visitedViews])
    })
  },
}

export default {
  namespaced: true,
  state,
  mutations,
  actions
}
