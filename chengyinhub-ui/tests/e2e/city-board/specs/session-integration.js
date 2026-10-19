// Explicitly tagged integration: simulate external session ABA through real Vuex mutations.
async function sessionABA(page) {
  await page.evaluate(({ a, b, permissions }) => {
    const store = document.querySelector('#app').__vue__.$store
    for (const [token, id, roles] of [[b, 7002, ['synthetic_other']], [a, 7001, ['synthetic_auditor']]]) {
      document.cookie = `Admin-Token=${token}; path=/`
      store.commit('SET_TOKEN', token); store.commit('SET_ID', id)
      store.commit('SET_ROLES', roles); store.commit('SET_PERMISSIONS', [...permissions])
    }
  }, { a: 'synthetic-city-board-session-A-not-a-real-credential', b: 'synthetic-city-board-session-B-not-a-real-credential', permissions: ['business:cityBoardDraft:edit'] })
}
module.exports = { sessionABA }
