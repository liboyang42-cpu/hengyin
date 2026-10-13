<template>
  <div />
</template>

<script>
import auth from '@/plugins/auth'

function firstQueryValue(value) {
  return Array.isArray(value) ? value[0] : value
}

function validOrderId(value) {
  return /^\d+$/.test(String(firstQueryValue(value) || ''))
}

export default {
  name: 'LegacyOrderDetailRedirect',
  created() {
    this.redirectToCanonical(this.$route.query)
  },
  methods: {
    redirectToCanonical(query = {}) {
      const targetQuery = Object.assign({}, query)
      const id = firstQueryValue(targetQuery.id)
      delete targetQuery.id
      if (!validOrderId(id)) {
        if (!auth.hasPermi('business:order:list')) return this.$router.replace('/404')
        return this.$router.replace({ name: 'financeOrder', query: targetQuery })
      }
      return this.$router.replace({
        name: 'financeOrderDetail',
        params: { id: String(id) },
        query: targetQuery
      })
    }
  }
}
</script>
