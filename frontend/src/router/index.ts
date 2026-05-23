import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: () => import('../views/Dashboard.vue') },
    { path: '/analysis/new', component: () => import('../views/NewAnalysis.vue') },
    { path: '/analysis/:cveId', component: () => import('../views/AnalysisDetail.vue') },
    { path: '/analysis/:cveId/diff', component: () => import('../views/PatchDiff.vue') },
    { path: '/settings', component: () => import('../views/Settings.vue') },
  ]
})

export default router
