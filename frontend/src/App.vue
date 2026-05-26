<script setup lang="ts">
import { useRouter } from 'vue-router'
const router = useRouter()
</script>

<template>
  <el-container style="min-height:100vh; background:var(--bg-base)">
    <el-header class="jv-header">
      <div class="jv-header-brand" @click="router.push('/')">
        <span class="jv-logo">JVULN</span>
        <span class="jv-tagline">Java Vulnerability Platform</span>
      </div>
      <div style="flex:1"/>
      <nav class="jv-nav">
        <span class="jv-nav-link" @click="router.push('/')">Dashboard</span>
        <span class="jv-nav-divider">|</span>
        <span class="jv-nav-link" @click="router.push('/settings')">Settings</span>
        <el-button type="primary" size="small" @click="router.push('/analysis/new')">
          + New Analysis
        </el-button>
      </nav>
    </el-header>
    <el-main style="background:var(--bg-base); padding:28px 32px; min-height:calc(100vh - 56px)">
      <RouterView />
    </el-main>
  </el-container>
</template>

<style scoped>
.jv-header {
  background: #0d0d0d;
  border-bottom: 1px solid var(--border-subtle);
  display: flex;
  align-items: center;
  gap: 20px;
  padding: 0 32px !important;
  height: 56px;
}
.jv-header-brand {
  display: flex;
  align-items: baseline;
  gap: 12px;
  cursor: pointer;
  user-select: none;
}
.jv-logo {
  color: var(--text-primary);
  font-family: var(--font-mono);
  font-weight: 600;
  font-size: 15px;
  letter-spacing: 3px;
}
.jv-tagline {
  color: var(--text-disabled);
  font-size: 12px;
}
.jv-nav {
  display: flex;
  align-items: center;
  gap: 16px;
}
.jv-nav-link {
  color: var(--text-muted);
  font-size: 13px;
  cursor: pointer;
  transition: color .15s;
}
.jv-nav-link:hover { color: var(--text-primary); }
.jv-nav-divider { color: var(--border); font-size: 11px; }
</style>
