<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'

interface Row { label: string; value: number; hint?: string }

const props = withDefaults(defineProps<{
  rows: Row[]
  color?: string
}>(), { color: '#22d3ee' })

const mounted = ref(false)
onMounted(() => requestAnimationFrame(() => { mounted.value = true }))

const peak = computed(() => Math.max(1, ...props.rows.map(r => r.value)))
</script>

<template>
  <div class="barlist">
    <div v-for="(r, i) in rows" :key="r.label" class="bl-row">
      <div class="bl-head">
        <span class="bl-label" :title="r.hint || r.label">{{ r.label }}</span>
        <span class="bl-value jv-mono">{{ r.value }}</span>
      </div>
      <div class="bl-track">
        <div
          class="bl-fill"
          :style="{
            width: mounted ? Math.max(3, (r.value / peak) * 100) + '%' : '0%',
            transitionDelay: i * 65 + 'ms',
            background: `linear-gradient(90deg, ${color}, ${color}33)`,
            boxShadow: `0 0 10px -2px ${color}`,
          }"
        />
      </div>
    </div>
  </div>
</template>

<style scoped>
.barlist { display: flex; flex-direction: column; gap: 13px; }
.bl-head {
  display: flex; align-items: baseline; justify-content: space-between;
  gap: 10px; margin-bottom: 6px;
}
.bl-label {
  font-family: var(--font-mono);
  font-size: 11px; letter-spacing: .04em;
  color: var(--ink-2);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.bl-value { font-size: 11px; font-weight: 600; color: var(--ink-1); flex: none; }
.bl-track {
  height: 5px;
  border-radius: var(--r-pill);
  background: rgba(255, 255, 255, .05);
  overflow: hidden;
}
.bl-fill {
  height: 100%;
  border-radius: var(--r-pill);
  transition: width .85s var(--ease-out);
}
.bl-row:hover .bl-fill { filter: brightness(1.25); }
</style>
