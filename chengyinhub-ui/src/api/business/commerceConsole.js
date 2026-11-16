import request from '@/utils/request'

// T5 MON-16 商品与会费:只读列表 + 停售 / 停用
export const commerceProducts = params => request({ url: '/business/commerceConsole/products', method: 'get', params })
export const commerceProductOrders = params => request({ url: '/business/commerceConsole/productOrders', method: 'get', params })
export const commerceBundleOrders = params => request({ url: '/business/commerceConsole/bundleOrders', method: 'get', params })
export const commerceDuesPlans = params => request({ url: '/business/commerceConsole/duesPlans', method: 'get', params })
export const commerceDuesInvoices = params => request({ url: '/business/commerceConsole/duesInvoices', method: 'get', params })
export const stopProductSale = (id, data) => request({ url: '/business/commerceConsole/products/' + id + '/stopSale', method: 'post', data })
export const deactivateDuesPlan = (id, data) => request({ url: '/business/commerceConsole/duesPlans/' + id + '/deactivate', method: 'post', data })
// W7 MERCHANT-12 会费退款:列表 + 通过(原路退款,资金开关 refunds 打开时)/ 驳回(不动钱),都要填写说明
export const commerceDuesRefunds = params => request({ url: '/business/commerceConsole/dues-refunds', method: 'get', params })
export const approveDuesRefund = (reviewId, data) => request({ url: '/business/commerceConsole/dues-refunds/' + reviewId + '/approve', method: 'post', data })
export const rejectDuesRefund = (reviewId, data) => request({ url: '/business/commerceConsole/dues-refunds/' + reviewId + '/reject', method: 'post', data })
