<script setup lang="ts">
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useI18n, type Locale } from './i18n'
import { LayoutDashboard, Settings2, Plus, ShieldHalf, ChevronRight, Activity } from 'lucide-vue-next'

const router = useRouter()
const route = useRoute()
const { locale, setLocale, t } = useI18n()

const crumbs = computed<string[]>(() => {
  const p = route.path
  if (p === '/settings') return [t('app.settings')]
  if (p === '/analysis/new') return [t('app.dashboard'), t('app.newAnalysis')]
  if (route.params.cveId) {
    const tail = p.endsWith('/diff') ? ['DIFF'] : []
    return [t('app.dashboard'), String(route.params.cveId), ...tail]
  }
  return [t('app.dashboard')]
})
</script>

<template>
  <div class="jv-shell">
    <aside class="jv-rail">
      <div class="jv-rail-mark" @click="router.push('/')">
        <ShieldHalf :size="21" :stroke-width="2.1" />
      </div>
      <div class="jv-rail-sep" />

      <div class="jv-rail-item" :class="{ 'is-active': route.path === '/' }" @click="router.push('/')">
        <LayoutDashboard :size="19" :stroke-width="1.8" />
        <span class="jv-rail-tip">{{ t('app.dashboard') }}</span>
      </div>
      <div class="jv-rail-item" :class="{ 'is-active': route.path === '/analysis/new' }" @click="router.push('/analysis/new')">
        <Plus :size="20" :stroke-width="1.9" />
        <span class="jv-rail-tip">{{ t('app.newAnalysis') }}</span>
      </div>
      <div class="jv-rail-item" :class="{ 'is-active': route.path === '/settings' }" @click="router.push('/settings')">
        <Settings2 :size="19" :stroke-width="1.8" />
        <span class="jv-rail-tip">{{ t('app.settings') }}</span>
      </div>

      <div class="jv-rail-spacer" />
      <span class="jv-rail-glyph">JVULN</span>
    </aside>

    <div class="jv-main">
      <header class="jv-bar">
        <div class="jv-bar-brand" @click="router.push('/')">
          <span class="jv-wordmark">JVULN<span class="jv-wordmark-dot">.</span></span>
          <span class="jv-tagline">{{ t('app.tagline') }}</span>
        </div>

        <div class="jv-bar-crumbs">
          <template v-for="(c, i) in crumbs" :key="i">
            <ChevronRight v-if="i > 0" :size="12" :stroke-width="2" />
            <span :class="{ 'crumb-cur': i === crumbs.length - 1 }">{{ c }}</span>
          </template>
        </div>

        <div class="jv-bar-spacer" />

        <div class="jv-bar-status">
          <span class="dot" />
          <Activity :size="12" :stroke-width="2" />
          <span>LIVE</span>
        </div>

        <div class="jv-locale">
          <span class="jv-locale-opt" :class="{ 'is-active': locale === 'zh-CN' }" @click="setLocale('zh-CN' as Locale)">中文</span>
          <span class="jv-locale-opt" :class="{ 'is-active': locale === 'en-US' }" @click="setLocale('en-US' as Locale)">EN</span>
        </div>

        <button class="jv-bar-cta" @click="router.push('/analysis/new')">
          <Plus :size="15" :stroke-width="2.4" />
          {{ t('app.newAnalysis') }}
        </button>
      </header>

      <main class="jv-canvas">
        <RouterView v-slot="{ Component }">
          <Transition name="route" mode="out-in">
            <component :is="Component" />
          </Transition>
        </RouterView>
      </main>
    </div>
  </div>
</template>
