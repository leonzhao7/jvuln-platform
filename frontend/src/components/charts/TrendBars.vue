<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'

interface Bar { label: string; value: number }

const props = withDefaults(defineProps<{
  bars: Bar[]
  height?: number
  color?: string
}>(), { height: 92, color: '#7c5cff' })

const mounted = ref(false)
onMounted(() => requestAnimationFrame(() => { mounted.value = true }))

const peak = computed(() => Math.max(1, ...props.bars.map(b => b.value)))
</script>

<template>
  <div class="trend">
    <div class="trend-plot" :style="{ height: height + 'px' }">
      <div
        v-for="(b, i) in bars" :key="b.label + i"
        class="trend-col"
        :title="`${b.label} · ${b.value}`"
      >
        <div class="trend-cap jv-mono">{{ b.value || '' }}</div>
        <div
          class="trend-bar"
          :style="{
            height: mounted ? Math.max(b.value ? 6 : 2, (b.value / peak) * 100) + '%' : '0%',
            transitionDelay: i * 55 + 'ms',
            background: b.value
              ? `linear-gradient(180deg, ${color}, ${color}22)`
              : 'rgba(255,255,255,.05)',
            boxShadow: b.value ? `0 0 12px -2px ${color}` : 'none',
          }"
        />
      </div>
    </div>
    <div class="trend-axis">
      <span v-for="(b, i) in bars" :key="'x' + i" class="trend-tick">{{ b.label }}</span>
    </div>
  </div>
</template>

<style scoped>
.trend { width: 100%; }
.trend-plot {
  display: flex; align-items: flex-end; gap: 6px;
  padding-bottom: 8px;
  border-bottom: 1px solid var(--hairline-lo);
}
.trend-col {
  flex: 1; min-width: 0; height: 100%;
  display: flex; flex-direction: column;
  align-items: center; justify-content: flex-end; gap: 5px;
}
.trend-cap {
  font-size: 10px; font-weight: 600;
  color: var(--ink-3);
  height: 12px;
}
.trend-bar {
  width: 100%;
  border-radius: var(--r-xs) var(--r-xs) 2px 2px;
  transition: height .8s var(--ease-spring);
}
.trend-col:hover .trend-bar { filter: brightness(1.25); }
.trend-axis { display: flex; gap: 6px; margin-top: 7px; }
.trend-tick {
  flex: 1; min-width: 0;
  text-align: center;
  font-family: var(--font-mono);
  font-size: 9px; letter-spacing: .04em;
  color: var(--ink-4);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
</style>
