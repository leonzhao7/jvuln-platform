<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'

const props = withDefaults(defineProps<{
  value: number
  max?: number
  size?: number
  label?: string
  suffix?: string
  color?: string
}>(), { max: 100, size: 132, suffix: '%', color: '#22d3ee' })

const mounted = ref(false)
onMounted(() => requestAnimationFrame(() => { mounted.value = true }))

const pct = computed(() => (props.max ? Math.min(1, Math.max(0, props.value / props.max)) : 0))
const radius = computed(() => (props.size - 12) / 2)
/* 270deg sweep leaves a gap at the bottom for a gauge silhouette */
const sweep = computed(() => 2 * Math.PI * radius.value * 0.75)
const circ = computed(() => 2 * Math.PI * radius.value)
</script>

<template>
  <div class="meter" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size">
      <g :transform="`rotate(135 ${size / 2} ${size / 2})`">
        <circle
          :cx="size / 2" :cy="size / 2" :r="radius" fill="none"
          stroke="rgba(255,255,255,.06)" stroke-width="8" stroke-linecap="round"
          :stroke-dasharray="`${sweep} ${circ}`"
        />
        <circle
          :cx="size / 2" :cy="size / 2" :r="radius" fill="none"
          :stroke="color" stroke-width="8" stroke-linecap="round"
          :stroke-dasharray="`${mounted ? sweep * pct : 0} ${circ}`"
          :style="{ transition: 'stroke-dasharray 1.1s cubic-bezier(.16,1,.3,1) .15s',
                    filter: `drop-shadow(0 0 7px ${color})` }"
        />
      </g>
    </svg>
    <div class="meter-core">
      <div class="meter-value jv-numeral">
        {{ Math.round(pct * 100) }}<span class="meter-suffix">{{ suffix }}</span>
      </div>
      <div v-if="label" class="meter-label">{{ label }}</div>
    </div>
  </div>
</template>

<style scoped>
.meter { position: relative; flex: none; }
.meter-core {
  position: absolute; inset: 0;
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  gap: 2px; pointer-events: none;
}
.meter-value { font-size: 27px; }
.meter-suffix { font-size: 13px; margin-left: 1px; }
.meter-label {
  font-family: var(--font-mono);
  font-size: 9px; font-weight: 600;
  letter-spacing: .16em; text-transform: uppercase;
  color: var(--ink-3);
}
</style>
