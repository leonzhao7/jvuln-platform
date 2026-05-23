<script setup lang="ts">
</script>

<template>
  <el-container style="min-height: 100vh">
    <el-header style="background:#1a1a2e; display:flex; align-items:center; gap:16px">
      <span style="color:#e94560; font-weight:700; font-size:20px">JVuln</span>
      <span style="color:#aaa; font-size:14px">Java Vulnerability Analysis Platform</span>
      <div style="flex:1"/>
      <el-button type="primary" size="small" @click="$router.push('/analysis/new')">
        New Analysis
      </el-button>
      <el-button size="small" @click="$router.push('/')">Dashboard</el-button>
      <el-button size="small" @click="$router.push('/settings')">⚙ Settings</el-button>
    </el-header>
    <el-main style="background:#0f0f23; padding:24px">
      <RouterView />
    </el-main>
  </el-container>
</template>

<style>
* { box-sizing: border-box; margin: 0; padding: 0; }
body { font-family: 'Segoe UI', system-ui, sans-serif; }
.el-header { padding: 0 24px; }
</style>
