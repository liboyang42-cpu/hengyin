import request from '@/utils/request'

// T5 MON-16 商品与会费:只读列表 + 停售 / 停用
export const commerceProducts = params => request({ url: '/business/commerceConsole/products', method: 'get', params })
export const commerceProductOrders = params => request({ url: '/business/commerceConsole/productOrders', method: 'get', params })
export const commerceBundleOrders = params => request({ url: '/business/commerceConsole/bundleOrders', method: 'get', params })
export const commerceDuesPlans = params => request({ url: '/business/commerceConsole/duesPlans', method: 'get', params })
export const commerceDuesInvoices = params => request({ url: '/business/commerceConsole/duesInvoices', method: 'get', params })
export const stopProductSale = (id, data) => request({ url: '/business/commerceConsole/products/' + id + '/stopSale', method: 'post', data })
export const deactivateDuesPlan = (id, data) => request({ url: '/business/commerceConsole/duesPlans/' + id + '/deactivate', method: 'post', data })
