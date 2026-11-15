import request from '@/utils/request'

// R10 漫游与游玩运营（只读）
export const roamOpsSessions = params => request({ url: '/business/roamOps/sessions', method: 'get', params })
export const roamOpsFog = params => request({ url: '/business/roamOps/fog', method: 'get', params })
export const roamOpsTeams = params => request({ url: '/business/roamOps/teams', method: 'get', params })
export const roamOpsRuns = params => request({ url: '/business/roamOps/runs', method: 'get', params })
