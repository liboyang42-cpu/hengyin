import Vue from 'vue'
import Router from 'vue-router'

Vue.use(Router)

/* Layout */
import Layout from '@/layout'

/**
 * Note: 路由配置项
 *
 * hidden: true                     // 当设置 true 的时候该路由不会再侧边栏出现 如401，login等页面，或者如一些编辑页面/edit/1
 * alwaysShow: true                 // 当你一个路由下面的 children 声明的路由大于1个时，自动会变成嵌套的模式--如组件页面
 *                                  // 只有一个时，会将那个子路由当做根路由显示在侧边栏--如引导页面
 *                                  // 若你想不管路由下面的 children 声明的个数都显示你的根路由
 *                                  // 你可以设置 alwaysShow: true，这样它就会忽略之前定义的规则，一直显示根路由
 * redirect: noRedirect             // 当设置 noRedirect 的时候该路由在面包屑导航中不可被点击
 * name:'router-name'               // 设定路由的名字，一定要填写不然使用<keep-alive>时会出现各种问题
 * query: '{"id": 1, "name": "ry"}' // 访问路由的默认传递参数
 * roles: ['admin', 'common']       // 访问路由的角色权限
 * permissions: ['a:a:a', 'b:b:b']  // 访问路由的菜单权限
 * meta : {
    noCache: true                   // 如果设置为true，则不会被 <keep-alive> 缓存(默认 false)
    title: 'title'                  // 设置该路由在侧边栏和面包屑中展示的名字
    icon: 'svg-name'                // 设置该路由的图标，对应路径src/assets/icons/svg
    breadcrumb: false               // 如果设置为false，则不会在breadcrumb面包屑中显示
    activeMenu: '/system/user'      // 当路由设置了该属性，则会高亮相对应的侧边栏。
  }
 */

// 公共路由
export const constantRoutes = [
  {
    path: '/redirect',
    component: Layout,
    hidden: true,
    children: [
      {
        path: '/redirect/:path(.*)',
        component: () => import('@/views/redirect')
      }
    ]
  },
  {
    path: '/login',
    component: () => import('@/views/login'),
    hidden: true
  },
  {
    path: '/register',
    component: () => import('@/views/register'),
    hidden: true
  },
  {
    path: '/404',
    component: () => import('@/views/error/404'),
    hidden: true
  },
  {
    path: '/401',
    component: () => import('@/views/error/401'),
    hidden: true
  },
  {
    path: '',
    component: Layout,
    redirect: 'index',
    children: [
      {
        path: 'index',
        component: () => import('@/views/index'),
        name: 'Index',
        meta: { title: '首页', icon: 'dashboard', affix: true }
      }
    ]
  },
  {
    path: '/user',
    component: Layout,
    hidden: true,
    redirect: 'noredirect',
    children: [
      {
        path: 'profile',
        component: () => import('@/views/system/user/profile/index'),
        name: 'Profile',
        meta: { title: '个人中心', icon: 'user' }
      }
    ]
  }
]

// 动态路由，基于用户权限动态去加载
export const dynamicRoutes = [
  ...(process.env.VUE_APP_CITY_BOARD_DRAFTS === 'true' ? [{
    path: '/platform/city-board-drafts',
    component: Layout,
    hidden: true,
    permissions: ['business:cityBoardDraft:edit'],
    children: [{ path: '', component: () => import('@/views/business/city-board-drafts'), name: 'CityBoardDraftWorkbench', meta: { title: '城市棋盘草稿', noCache: true } }]
  }] : []),
  {
    path: '/system/user-auth',
    component: Layout,
    hidden: true,
    permissions: ['system:user:edit'],
    children: [
      {
        path: 'role/:userId(\\d+)',
        component: () => import('@/views/system/user/authRole'),
        name: 'AuthRole',
        meta: { title: '分配角色', activeMenu: '/system-ops/system/user' }
      }
    ]
  },
  {
    path: '/system/role-auth',
    component: Layout,
    hidden: true,
    permissions: ['system:role:edit'],
    children: [
      {
        path: 'user/:roleId(\\d+)',
        component: () => import('@/views/system/role/authUser'),
        name: 'AuthUser',
        meta: { title: '分配用户', activeMenu: '/system-ops/system/role' }
      }
    ]
  },
  {
    path: '/system/dict-data',
    component: Layout,
    hidden: true,
    permissions: ['system:dict:list'],
    children: [
      {
        path: 'index/:dictId(\\d+)',
        component: () => import('@/views/system/dict/data'),
        name: 'Data',
        meta: { title: '字典数据', activeMenu: '/system-ops/system/dict' }
      }
    ]
  },
  {
    path: '/monitor/job-log',
    component: Layout,
    hidden: true,
    permissions: ['monitor:job:list'],
    children: [
      {
        path: 'index/:jobId(\\d+)',
        component: () => import('@/views/monitor/job/log'),
        name: 'JobLog',
        meta: { title: '调度日志', activeMenu: '/system-ops/monitor/job' }
      }
    ]
  },
  {
    path: '/tool/gen-edit',
    component: Layout,
    hidden: true,
    permissions: ['tool:gen:edit'],
    children: [
      {
        path: 'index/:tableId(\\d+)',
        component: () => import('@/views/tool/gen/editTable'),
        name: 'GenEdit',
        meta: { title: '修改生成配置', activeMenu: '/system-ops/tool/gen' }
      }
    ]
  },
  {
    path: '/config/points-product',
    component: Layout,
    hidden: true,
    permissions: ['business:product:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/config/points-product'),
        name: 'pointsProductCatalog',
        meta: { title: '积分商城商品', activeMenu: '/funds-aftercare/product' }
      },
      {
        path: 'create',
        component: () => import('@/views/business/product/AddProduct'),
        name: 'pointsProductCreate',
        meta: { title: '新增积分商城商品', activeMenu: '/funds-aftercare/product' }
      },
      {
        path: ':id/edit',
        component: () => import('@/views/business/product/AddProduct'),
        name: 'pointsProductEdit',
        meta: { title: '编辑积分商城商品', activeMenu: '/funds-aftercare/product' }
      }
    ]
  },
  {
    path: '/product',
    component: Layout,
    hidden: true,
    permissions: ['business:product:list'],
    children: [
      {
        path: 'edit',
        component: () => import('@/views/business/product/legacy-edit'),
        name: 'pointsProductLegacyEdit',
        meta: { title: '积分商城商品旧入口' }
      }
    ]
  },
  {
    path: '/finance/order',
    component: Layout,
    hidden: true,
    permissions: ['business:order:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/finance/order'),
        name: 'financeOrder',
        meta: { title: '订单查询', activeMenu: '/funds-aftercare/order' }
      }
    ]
  },
  {
    path: '/finance/order/:id(\\d+)',
    component: Layout,
    hidden: true,
    permissions: ['business:order:query'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/finance/order/detail'),
        name: 'financeOrderDetail',
        meta: { title: '订单详情', activeMenu: '/funds-aftercare/order' }
      }
    ]
  },
  {
    path: '/order',
    component: Layout,
    hidden: true,
    permissions: ['business:order:query'],
    children: [
      {
        path: 'detail',
        component: () => import('@/views/business/order/detail'),
        name: 'orderDetail',
        meta: { title: '订单详情' }
      }
    ]
  },
  {
    path: '/coupon',
    component: Layout,
    hidden: true,
    permissions: ['business:couponhistory:list'],
    children: [
      {
        path: 'ledger/:couponId(\\d+)',
        component: () => import('@/views/business/coupon/ledger'),
        name: 'couponLedger',
        meta: { title: '优惠券流水', activeMenu: '/supply/coupon' }
      }
    ]
  }
  ,
  {
    path: '/finance',
    component: Layout,
    hidden: true,
    permissions: ['business:memberbalancedetail:list'],
    children: [
      {
        path: 'ledger',
        component: () => import('@/views/business/finance/ledger'),
        name: 'financeLedger',
        meta: { title: '资金流水', activeMenu: '/funds-aftercare/memberbalancedetail' }
      }
    ]
  }
  ,
  {
    path: '/finance/payable-batch',
    component: Layout,
    hidden: true,
    permissions: ['business:payablebatch:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/finance/payable-batch/index'),
        name: 'financePayableBatch',
        meta: { title: '商家应付月结', activeMenu: '/funds-aftercare/payablebatch' }
      }
    ]
  }
  ,
  {
    path: '/club/profile',
    component: Layout,
    hidden: true,
    permissions: ['business:club:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/club/index'),
        name: 'clubProfile',
        meta: { title: '俱乐部运营工作台', activeMenu: '/clubs-roam/club' }
      }
    ]
  }
  ,
  {
    path: '/club/lead-run',
    component: Layout,
    hidden: true,
    permissions: ['business:clubRun:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/clubrun/index'),
        name: 'clubLeadRun',
        meta: { title: '带队场次', activeMenu: '/clubs-roam/clubrun' }
      }
    ]
  }
  ,
  {
    path: '/finance/withdraw/wechat',
    component: Layout,
    hidden: true,
    permissions: ['business:cashwithdraw:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/cashwithdraw/index'),
        name: 'financeWechatWithdraw',
        meta: { title: '微信提现出金', activeMenu: '/funds-aftercare/cashwithdraw' }
      }
    ]
  }
  ,
  {
    path: '/merchant/chapter-offer',
    component: Layout,
    hidden: true,
    permissions: ['business:chapterOffer:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/chapteroffer/index'),
        name: 'merchantChapterOffer',
        meta: { title: '商家章节承接档', activeMenu: '/merchants-coop/chapteroffer' }
      }
    ]
  }
  ,
  {
    path: '/finance/withdraw/bank',
    component: Layout,
    hidden: true,
    permissions: ['business:memberwithdrawal:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/memberwithdrawal/index'),
        name: 'financeBankWithdraw',
        meta: { title: '银行卡提现', activeMenu: '/funds-aftercare/memberwithdrawal' }
      }
    ]
  }
  ,
  {
    path: '/finance/registration',
    component: Layout,
    hidden: true,
    permissions: ['business:registration:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/registration/index'),
        name: 'financeRegistration',
        meta: { title: '报名台账', activeMenu: '/funds-aftercare/registration' }
      }
    ]
  }
  ,
  {
    path: '/business/clubrun',
    hidden: true,
    permissions: ['business:clubRun:list'],
    redirect: to => ({ name: 'clubLeadRun', query: Object.assign({}, to.query) })
  }
  ,
  {
    path: '/business/chapteroffer',
    hidden: true,
    permissions: ['business:chapterOffer:list'],
    redirect: to => ({ name: 'merchantChapterOffer', query: Object.assign({}, to.query) })
  }
  ,
  {
    path: '/business/club',
    hidden: true,
    permissions: ['business:club:list'],
    redirect: to => ({ name: 'clubProfile', query: Object.assign({}, to.query) })
  }
  ,
  {
    path: '/activity',
    component: Layout,
    hidden: true,
    permissions: ['business:activity:query'],
    children: [
      {
        path: 'detail',
        component: () => import('@/views/business/activity/detail'),
        name: 'activityDetail',
        meta: { title: '活动详情' }
      }
    ]
  },
  {
    path: '/topic',
    component: Layout,
    hidden: true,
    permissions: ['business:topic:query'],
    children: [
      {
        path: 'detail',
        component: () => import('@/views/business/topic/detail'),
        name: 'topicDetail',
        meta: { title: '主题详情' }
      },
      {
        path: 'workspace',
        component: () => import('@/views/business/topic/workspace'),
        name: 'topicWorkspace',
        meta: { title: '主题运营工作台' }
      }
    ]
  },
  {
    path: '/refund',
    component: Layout,
    hidden: true,
    permissions: ['business:refund:review'],
    children: [
      {
        path: 'manual',
        component: () => import('@/views/business/refund/manual'),
        name: 'refundManual',
        meta: { title: '退款人工打款队列' }
      }
    ]
  },
  {
    path: '/finance/refund',
    component: Layout,
    hidden: true,
    permissions: ['business:refund:list', 'business:refund:review'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/finance/refund'),
        name: 'financeRefund',
        meta: { title: '退款处理台', activeMenu: '/funds-aftercare/refund' }
  }
  ]
  }
  ,
  {
    path: '/customer/member',
    component: Layout,
    hidden: true,
    permissions: ['business:member:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/member/index'),
        name: 'customerMember',
        meta: { title: '用户调证台', activeMenu: '/users-support/member' }
      },
      {
        path: ':memberId/growth',
        component: () => import('@/views/business/member/growth-detail'),
        name: 'memberGrowthDetail',
        meta: { title: '会员成长档案', activeMenu: '/users-support/member' }
      }
    ]
  },
  {
    path: '/business/member',
    hidden: true,
    permissions: ['business:member:list'],
    redirect: to => ({ name: 'customerMember', query: Object.assign({}, to.query) })
  },
  {
    path: '/templatelibrary',
    component: Layout,
    hidden: true,
    permissions: ['business:templatelibrary:query'],
    children: [
      {
        path: 'detail',
        component: () => import('@/views/business/templatelibrary/detail'),
        name: 'templateLibraryDetail',
        meta: { title: '模板详情' }
      }
    ]
  },
  {
    path: '/membertemplate',
    component: Layout,
    hidden: true,
    permissions: ['business:membertemplate:query'],
    children: [
      {
        path: 'detail',
        component: () => import('@/views/business/membertemplate/detail'),
        name: 'membertemplateDetail',
        meta: { title: '模板详情' }
      }
    ]
  }
  ,
  {
    path: '/config',
    component: Layout,
    hidden: true,
    permissions: ['business:pointsetting:list'],
    children: [
      {
        path: 'points',
        component: () => import('@/views/business/points/index'),
        name: 'pointsConfig',
        meta: { title: '积分配置', activeMenu: '/rules/pointsetting' }
      }
    ]
  }
  ,
  {
    path: '/risk/creator-admission',
    component: Layout,
    hidden: true,
    permissions: ['business:creator:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/risk/creator-admission'),
        name: 'creatorAdmission',
        meta: { title: '创作者准入', activeMenu: '/audit-center/creator' }
      }
    ]
  }
  ,
  {
    path: '/risk/ai-log',
    component: Layout,
    hidden: true,
    permissions: ['business:ailog:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/ailog/index'),
        name: 'riskAiLog',
        meta: { title: 'AI 调用审计', activeMenu: '/audit-center/ailog' }
      }
    ]
  }
  ,
  {
    path: '/config/role-quota',
    component: Layout,
    hidden: true,
    permissions: ['business:userpermission:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/config/role-quota/index'),
        name: 'roleQuotaConfig',
        meta: { title: '角色配额', activeMenu: '/rules/userpermission' }
      }
    ]
  }
  ,
  {
    path: '/betagame',
    component: Layout,
    hidden: true,
    permissions: ['business:betagame:list'],
    children: [
      {
        path: 'edit',
        component: () => import('@/views/business/betagame/edit'),
        name: 'addBetagame',
        meta: { title: '新增/编辑游戏' }
      }
    ]
  },
  {
    path: '/supply/coupon',
    component: Layout,
    hidden: true,
    permissions: ['business:coupon:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/coupon/index'),
        name: 'supplyCoupon',
        meta: { title: '优惠券目录', activeMenu: '/supply/coupon' }
  }
  ]
  }
  ,
  // [P2-4 H7 · Q14=B 用户裁决] /supply/official-event 与菜单 canonical /supply/officialevent
  // 只差一个连字符,是拼写分叉不是两个页面(菜单 2192 path='officialevent',父 2209 path='supply')。
  // 改为同权限 redirect:保留 business:officialevent:list gate —— 无权账号不能借 redirect 绕过;
  // 不删除,旧书签/外链继续可用(方案 §6.3「无访问证据前不评估删除」)。
  // ⚠️ 本条推翻了 P2-3 留下的「/supply/official-event 本身是 canonical 入口」结论,
  //    对应契约测试 tests/unit/official-event-migration-contract.test.js 已同批改写为
  //    断言真实跳转行为(vue-router 解析结果),并做过负控。
  {
    path: '/supply/official-event',
    hidden: true,
    permissions: ['business:officialevent:list'],
    redirect: to => ({ name: 'Officialevent', query: Object.assign({}, to.query) })
  }
  ,
  {
    path: '/customer/im',
    component: Layout,
    hidden: true,
    permissions: ['business:im:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/im/index'),
        name: 'customerIm',
        meta: { title: '客服会话', activeMenu: '/users-support/im' }
      }
    ]
  },
  {
    path: '/business/coupon',
    hidden: true,
    permissions: ['business:coupon:list'],
    redirect: to => ({ name: 'supplyCoupon', query: Object.assign({}, to.query) })
  }
  ,
  {
    path: '/config/content/guide',
    component: Layout,
    hidden: true,
    permissions: ['business:infomation:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/content/guide/index'),
        name: 'contentGuide',
        meta: { title: '玩法说明', activeMenu: '/supply/infomation' }
      }
    ]
  }
  ,
  {
    path: '/business/im',
    hidden: true,
    permissions: ['business:im:list'],
    redirect: to => ({ name: 'customerIm', query: Object.assign({}, to.query) })
  }
  ,
  {
    path: '/config/gift',
    component: Layout,
    hidden: true,
    permissions: ['business:gift:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/gift-catalog/index'),
        name: 'giftCatalog',
        meta: { title: '礼物目录与定价', activeMenu: '/funds-aftercare/gift' }
      }
    ]
  },
  {
    path: '/risk/verification-evidence',
    component: Layout,
    hidden: true,
    permissions: ['business:verifyEvidence:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/verifyevidence/index'),
        name: 'riskVerificationEvidence',
        meta: { title: '核销证据', activeMenu: '/audit-center/verifyevidence' }
      }
    ]
  },
  {
    path: '/business/verifyevidence',
    hidden: true,
    permissions: ['business:verifyEvidence:list'],
    redirect: to => ({ name: 'riskVerificationEvidence', query: Object.assign({}, to.query) })
  }
  ,
  {
    path: '/club/roam-poi',
    component: Layout,
    hidden: true,
    permissions: ['business:roamPoi:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/roampoi/index'),
        name: 'clubRoamPoi',
        meta: { title: '漫游 POI 治理', activeMenu: '/roam/roampoi' }
      }
    ]
  }
  ,
  {
    path: '/business/roampoi',
    hidden: true,
    permissions: ['business:roamPoi:list'],
    redirect: to => ({ name: 'clubRoamPoi', query: Object.assign({}, to.query) })
  }
  ,
  {
    path: '/roam/ai-content',
    component: Layout,
    hidden: true,
    permissions: ['business:roamAiContent:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/roamaicontent/index'),
        name: 'roamAiContent',
        meta: { title: '漫游陪伴内容审核', activeMenu: '/roam/ai-content' }
      }
    ]
  }
  ,
  {
    path: '/customer/case',
    component: Layout,
    hidden: true,
    permissions: ['business:coopcomplaint:list'],
    children: [
      {
        path: '',
        component: () => import('@/views/business/coopcomplaint/index'),
        name: 'customerCase',
        meta: { title: '合作客诉案件', activeMenu: '/merchants-coop/coopcomplaint' }
      }
    ]
  }
  ,
  {
    path: '/business/coopcomplaint',
    hidden: true,
    permissions: ['business:coopcomplaint:list'],
    redirect: to => ({ name: 'customerCase', query: Object.assign({}, to.query) })
  }
  ,
  {
    path: '/business/registration',
    hidden: true,
    permissions: ['business:registration:list'],
    redirect: to => ({ name: 'financeRegistration', query: Object.assign({}, to.query) })
  }
  ,
  // =========================================================================
  // [P2-4 Q10] 迁移后旧 fullpath 兜底 redirect(用户裁决:全加,不只已有的 10 条)
  // 这些路径今天由 sys_menu 动态路由提供;本轮 re-parent 后 fullpath 变化,
  // 没有兜底的旧书签会静默 404。全部保留原 permissions gate,按 route name 跳转
  // (RuoYi getRouteName = capitalize(route_name || path),与 parent 无关,迁移后仍稳定;
  //  已校验 71 个 C 的 route name 互不冲突、也不与 50 条 static route name 冲突)。
  // =========================================================================
  {
    path: '/business/product/edit',
    hidden: true,
    permissions: ['business:product:list'],
    redirect: to => ({ name: 'Edit', query: Object.assign({}, to.query) })
  },   // [2043] 编辑商品 → /funds-aftercare/product/edit
  {
    path: '/system/club/userpermission',
    hidden: true,
    permissions: ['business:userpermission:list'],
    redirect: to => ({ name: 'Userpermission', query: Object.assign({}, to.query) })
  },   // [2153] 角色配额 → /rules/userpermission
  {
    path: '/system/log/logininfor',
    hidden: true,
    permissions: ['monitor:logininfor:list'],
    redirect: to => ({ name: 'Logininfor', query: Object.assign({}, to.query) })
  },   // [501] 登录日志 → /system-ops/system/log/logininfor
  {
    path: '/system/log/operlog',
    hidden: true,
    permissions: ['monitor:operlog:list'],
    redirect: to => ({ name: 'Operlog', query: Object.assign({}, to.query) })
  },   // [500] 操作日志 → /system-ops/system/log/operlog
  {
    path: '/business/activity',
    hidden: true,
    permissions: ['business:activity:list'],
    redirect: to => ({ name: 'Activity', query: Object.assign({}, to.query) })
  },   // [2062] 活动管理 → /supply/activity
  {
    path: '/business/ailog',
    hidden: true,
    permissions: ['business:ailog:list'],
    redirect: to => ({ name: 'Ailog', query: Object.assign({}, to.query) })
  },   // [2205] AI生成日志 → /audit-center/ailog
  {
    path: '/business/banner',
    hidden: true,
    permissions: ['system:banner:list'],
    redirect: to => ({ name: 'Banner', query: Object.assign({}, to.query) })
  },   // [2001] 轮播图 → /supply/banner
  {
    path: '/business/betagame',
    hidden: true,
    permissions: ['business:betagame:list'],
    redirect: to => ({ name: 'Betagame', query: Object.assign({}, to.query) })
  },   // [2098] 游戏管理 → /rules/betagame
  {
    path: '/business/cashwithdraw',
    hidden: true,
    permissions: ['business:cashwithdraw:list'],
    redirect: to => ({ name: 'Cashwithdraw', query: Object.assign({}, to.query) })
  },   // [2219] 提现出金管理 → /funds-aftercare/cashwithdraw
  {
    path: '/business/category',
    hidden: true,
    permissions: ['system:category:list'],
    redirect: to => ({ name: 'Category', query: Object.assign({}, to.query) })
  },   // [2056] 分类管理 → /supply/category
  {
    path: '/business/gift',
    hidden: true,
    permissions: ['business:gift:list'],
    redirect: to => ({ name: 'Gift', query: Object.assign({}, to.query) })
  },   // [2037] 众筹礼物 → /funds-aftercare/gift
  {
    path: '/business/infomation',
    hidden: true,
    permissions: ['business:infomation:list'],
    redirect: to => ({ name: 'Infomation', query: Object.assign({}, to.query) })
  },   // [2129] 玩法说明 → /supply/infomation
  {
    path: '/business/memberbalancedetail',
    hidden: true,
    permissions: ['business:memberbalancedetail:list'],
    redirect: to => ({ name: 'Memberbalancedetail', query: Object.assign({}, to.query) })
  },   // [2141] 余额明细 → /funds-aftercare/memberbalancedetail
  {
    path: '/business/membertemplate',
    hidden: true,
    permissions: ['business:membertemplate:list'],
    redirect: to => ({ name: 'Membertemplate', query: Object.assign({}, to.query) })
  },   // [2086] 用户模板库 → /supply/membertemplate
  {
    path: '/business/memberwithdrawal',
    hidden: true,
    permissions: ['business:memberwithdrawal:list'],
    redirect: to => ({ name: 'Memberwithdrawal', query: Object.assign({}, to.query) })
  },   // [2111] 提现管理 → /funds-aftercare/memberwithdrawal
  {
    path: '/business/order',
    hidden: true,
    permissions: ['business:order:list'],
    redirect: to => ({ name: 'Order', query: Object.assign({}, to.query) })
  },   // [2025] 订单管理 → /funds-aftercare/order
  {
    path: '/business/pointsetting',
    hidden: true,
    permissions: ['business:pointsetting:list'],
    redirect: to => ({ name: 'Pointsetting', query: Object.assign({}, to.query) })
  },   // [2135] 积分规则设置 → /rules/pointsetting
  {
    path: '/business/product',
    hidden: true,
    permissions: ['business:product:list'],
    redirect: to => ({ name: 'Product', query: Object.assign({}, to.query) })
  },   // [2019] 积分商城商品 → /funds-aftercare/product
  {
    path: '/business/setting',
    hidden: true,
    permissions: ['business:setting:list'],
    redirect: to => ({ name: 'Setting', query: Object.assign({}, to.query) })
  },   // [2117] 参数设置 → /funds-aftercare/setting
  {
    path: '/business/ticket',
    hidden: true,
    permissions: ['business:ticket:list'],
    redirect: to => ({ name: 'Ticket', query: Object.assign({}, to.query) })
  },   // [2031] 主题/活动 → /supply/ticket
  {
    path: '/clubs-roam/roampoi',
    hidden: true,
    permissions: ['business:roamPoi:list'],
    redirect: to => ({ name: 'Roampoi', query: Object.assign({}, to.query) })
  },   // [2224] POI治理 → /roam/roampoi
  {
    path: '/monitor/cache',
    hidden: true,
    permissions: ['monitor:cache:list'],
    redirect: to => ({ name: 'Cache', query: Object.assign({}, to.query) })
  },   // [113] 缓存监控 → /system-ops/monitor/cache
  {
    path: '/monitor/cacheList',
    hidden: true,
    permissions: ['monitor:cache:list'],
    redirect: to => ({ name: 'CacheList', query: Object.assign({}, to.query) })
  },   // [114] 缓存列表 → /system-ops/monitor/cacheList
  {
    path: '/monitor/druid',
    hidden: true,
    permissions: ['monitor:druid:list'],
    redirect: to => ({ name: 'Druid', query: Object.assign({}, to.query) })
  },   // [111] 数据监控 → /system-ops/monitor/druid
  {
    path: '/monitor/job',
    hidden: true,
    permissions: ['monitor:job:list'],
    redirect: to => ({ name: 'Job', query: Object.assign({}, to.query) })
  },   // [110] 定时任务 → /system-ops/monitor/job
  {
    path: '/monitor/online',
    hidden: true,
    permissions: ['monitor:online:list'],
    redirect: to => ({ name: 'Online', query: Object.assign({}, to.query) })
  },   // [109] 在线用户 → /system-ops/monitor/online
  {
    path: '/monitor/server',
    hidden: true,
    permissions: ['monitor:server:list'],
    redirect: to => ({ name: 'Server', query: Object.assign({}, to.query) })
  },   // [112] 服务监控 → /system-ops/monitor/server
  {
    path: '/p3-platform/analytics',
    hidden: true,
    permissions: ['business:analytics:list'],
    redirect: to => ({ name: 'Analytics', query: Object.assign({}, to.query) })
  },   // [2156] 增长漏斗 → /workbench/analytics
  {
    path: '/p3-platform/audit',
    hidden: true,
    permissions: ['business:audit:list'],
    redirect: to => ({ name: 'Audit', query: Object.assign({}, to.query) })
  },   // [2157] 审核工作台 → /audit-center/audit
  {
    path: '/p3-platform/creator',
    hidden: true,
    permissions: ['business:creator:list'],
    redirect: to => ({ name: 'Creator', query: Object.assign({}, to.query) })
  },   // [2180] 创作者中心 → /audit-center/creator
  {
    path: '/p3-platform/crm',
    hidden: true,
    permissions: ['business:crm:list'],
    redirect: to => ({ name: 'Crm', query: Object.assign({}, to.query) })
  },   // [2182] 商家CRM → /merchants-coop/crm
  {
    path: '/p3-platform/growth',
    hidden: true,
    permissions: ['business:growth:list'],
    redirect: to => ({ name: 'Growth', query: Object.assign({}, to.query) })
  },   // [2176] 玩家成长 → /rules/growth
  {
    path: '/p3-platform/payablebatch',
    hidden: true,
    permissions: ['business:payablebatch:list'],
    redirect: to => ({ name: 'Payablebatch', query: Object.assign({}, to.query) })
  },   // [2217] ③应付批次 → /funds-aftercare/payablebatch
  {
    path: '/p3-platform/recommendation',
    hidden: true,
    permissions: ['business:recommendation:list'],
    redirect: to => ({ name: 'Recommendation', query: Object.assign({}, to.query) })
  },   // [2159] 推荐配置 → /supply/recommendation
  {
    path: '/p3-platform/refund',
    hidden: true,
    permissions: ['business:refund:list'],
    redirect: to => ({ name: 'Refund', query: Object.assign({}, to.query) })
  },   // [2190] 退款审核 → /funds-aftercare/refund
  {
    path: '/p3-platform/settlement',
    hidden: true,
    permissions: ['business:settlement:list'],
    redirect: to => ({ name: 'Settlement', query: Object.assign({}, to.query) })
  },   // [2184] 结算管理 → /funds-aftercare/settlement
  {
    path: '/p3-platform/settlementrule',
    hidden: true,
    permissions: ['business:settlementrule:list'],
    redirect: to => ({ name: 'Settlementrule', query: Object.assign({}, to.query) })
  },   // [2188] 结算规则 → /funds-aftercare/settlementrule
  {
    path: '/p3-platform/world',
    hidden: true,
    permissions: ['business:world:list'],
    redirect: to => ({ name: 'World', query: Object.assign({}, to.query) })
  },   // [2178] IP世界观 → /supply/world
  {
    path: '/preset/route',
    hidden: true,
    permissions: ['business:presetRoute:list'],
    redirect: to => ({ name: 'Route', query: Object.assign({}, to.query) })
  },   // [2168] 城市路线 → /supply/route
  {
    path: '/preset/style',
    hidden: true,
    permissions: ['business:presetStyle:list'],
    redirect: to => ({ name: 'Style', query: Object.assign({}, to.query) })
  },   // [2163] 主题风格 → /supply/style
  {
    path: '/system/club',
    hidden: true,
    permissions: ['business:club:list'],
    redirect: to => ({ name: 'Club', query: Object.assign({}, to.query) })
  },   // [2147] 俱乐部管理 → /clubs-roam/club
  {
    path: '/system/config',
    hidden: true,
    permissions: ['system:config:list'],
    redirect: to => ({ name: 'Config', query: Object.assign({}, to.query) })
  },   // [106] 参数设置 → /system-ops/system/config
  {
    path: '/system/dept',
    hidden: true,
    permissions: ['system:dept:list'],
    redirect: to => ({ name: 'Dept', query: Object.assign({}, to.query) })
  },   // [103] 部门管理 → /system-ops/system/dept
  {
    path: '/system/dict',
    hidden: true,
    permissions: ['system:dict:list'],
    redirect: to => ({ name: 'Dict', query: Object.assign({}, to.query) })
  },   // [105] 字典管理 → /system-ops/system/dict
  {
    path: '/system/menu',
    hidden: true,
    permissions: ['system:menu:list'],
    redirect: to => ({ name: 'Menu', query: Object.assign({}, to.query) })
  },   // [102] 菜单管理 → /system-ops/system/menu
  {
    path: '/system/notice',
    hidden: true,
    permissions: ['system:notice:list'],
    redirect: to => ({ name: 'Notice', query: Object.assign({}, to.query) })
  },   // [107] 通知公告 → /system-ops/system/notice
  {
    path: '/system/post',
    hidden: true,
    permissions: ['system:post:list'],
    redirect: to => ({ name: 'Post', query: Object.assign({}, to.query) })
  },   // [104] 岗位管理 → /system-ops/system/post
  {
    path: '/system/role',
    hidden: true,
    permissions: ['system:role:list'],
    redirect: to => ({ name: 'Role', query: Object.assign({}, to.query) })
  },   // [101] 角色管理 → /system-ops/system/role
  {
    path: '/system/user',
    hidden: true,
    permissions: ['system:user:list'],
    redirect: to => ({ name: 'User', query: Object.assign({}, to.query) })
  },   // [100] 系统用户管理 → /system-ops/system/user
  {
    path: '/tool/build',
    hidden: true,
    permissions: ['tool:build:list'],
    redirect: to => ({ name: 'Build', query: Object.assign({}, to.query) })
  },   // [115] 表单构建 → /system-ops/tool/build
  {
    path: '/tool/gen',
    hidden: true,
    permissions: ['tool:gen:list'],
    redirect: to => ({ name: 'Gen', query: Object.assign({}, to.query) })
  },   // [116] 代码生成 → /system-ops/tool/gen
  {
    path: '/tool/swagger',
    hidden: true,
    permissions: ['tool:swagger:list'],
    redirect: to => ({ name: 'Swagger', query: Object.assign({}, to.query) })
  },   // [117] 系统接口 → /system-ops/tool/swagger
  {
    path: '/users-support/comment',
    hidden: true,
    permissions: ['business:comment:list'],
    redirect: to => ({ name: 'Comment', query: Object.assign({}, to.query) })
  },   // [2092] 评论管理 → /audit-center/comment
  {
    path: '/users-support/square',
    hidden: true,
    permissions: ['business:square:list'],
    redirect: to => ({ name: 'Square', query: Object.assign({}, to.query) })
  },   // [2044] 创意广场 → /audit-center/square
  {
    path: '/coopcomplaint',
    hidden: true,
    permissions: ['business:coopcomplaint:list'],
    redirect: to => ({ name: 'Coopcomplaint', query: Object.assign({}, to.query) })
  },   // [2193] 客诉工单 → /merchants-coop/coopcomplaint
  {
    path: '/im',
    hidden: true,
    permissions: ['business:im:list'],
    redirect: to => ({ name: 'Im', query: Object.assign({}, to.query) })
  },   // [2173] 客服消息 → /users-support/im
  {
    path: '/merchantlead',
    hidden: true,
    permissions: ['business:merchantlead:list'],
    redirect: to => ({ name: 'Merchantlead', query: Object.assign({}, to.query) })
  },   // [2196] 商家目录 → /merchants-coop/merchantlead
  {
    // [P2-4 H1/R4] 2080『商家报名管理』C 行已删,旧 path 同权限 redirect 到 2161。
    // 保留 business:registrationmerchant:list gate —— 无权账号不能借 redirect 绕过。
    // route name = capitalize('registrationmerchant-dispatch') = 'Registrationmerchant-dispatch'
    path: '/merchants-coop/registrationmerchant',
    hidden: true,
    permissions: ['business:registrationmerchant:list'],
    redirect: to => ({ name: 'Registrationmerchant-dispatch', query: Object.assign({}, to.query) })
  }
]

// 防止连续点击多次路由报错
let routerPush = Router.prototype.push
let routerReplace = Router.prototype.replace
// push
Router.prototype.push = function push(location) {
  return routerPush.call(this, location).catch(err => err)
}
// replace
Router.prototype.replace = function push(location) {
  return routerReplace.call(this, location).catch(err => err)
}

export default new Router({
  mode: 'history', // 去掉url中的#
  scrollBehavior: () => ({ y: 0 }),
  routes: constantRoutes
})
