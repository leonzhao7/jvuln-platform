<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'

interface Slice { label: string; value: number; color: string }

const props = withDefaults(defineProps<{
  slices: Slice[]
  size?: number
  thickness?: number
  centerValue?: string | number
  centerLabel?: string
}>(), { size: 168, thickness: 13 })

const mounted = ref(false)
onMounted(() => requestAnimationFrame(() => { mounted.value = true }))

const total = computed(() => props.slices.reduce((s, x) => s + x.value, 0))
const radius = computed(() => (props.size - props.thickness) / 2)
const circ = computed(() => 2 * Math.PI * radius.value)

const arcs = computed(() => {
  let acc = 0
  return props.slices.map(s => {
    const frac = total.value ? s.value / total.value : 0
    const arc = { ...s, dash: frac * circ.value, offset: -acc * circ.value }
    acc += frac
    return arc
  })
})
</script>

<template>
  <div class="donut" :style="{ width: size + 'px', height: size + 'px' }">
    <svg :width="size" :height="size">
      <g :transform="`rotate(-90 ${size / 2} ${size / 2})`">
        <circle
          :cx="size / 2" :cy="size / 2" :r="radius"
          fill="none" stroke="rgba(255,255,255,.055)" :stroke-width="thickness"
        />
        <circle
          v-for="(a, i) in arcs" :key="a.label"
          :cx="size / 2" :cy="size / 2" :r="radius"
          fill="none" :stroke="a.color" :stroke-width="thickness"
          :stroke-dasharray="`${mounted ? a.dash : 0} ${circ}`"
          :stroke-dashoffset="a.offset"
          :style="{ transition: `stroke-dasharray .9s cubic-bezier(.16,1,.3,1) ${i * 0.11}s`,
                    filter: `drop-shadow(0 0 6px ${a.color})` }"
        />
      </g>
    </svg>
    <div class="donut-core">
      <div class="donut-value jv-numeral">{{ centerValue ?? total }}</div>
      <div v-if="centerLabel" class="donut-label">{{ centerLabel }}</div>
    </div>
  </div>
</template>

<style scoped>
.donut { position: relative; flex: none; }
.donut-core {
  position: absolute; inset: 0;
  display: flex; flex-direction: column;
  align-items: center; justify-content: center;
  gap: 3px; pointer-events: none;
}
.donut-value { font-size: 34px; }
.donut-label {
  font-family: var(--font-mono);
  font-size: 9px; font-weight: 600;
  letter-spacing: .18em; text-transform: uppercase;
  color: var(--ink-3);
}
</style>
