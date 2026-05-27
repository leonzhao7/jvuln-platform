# Frontend

Vue 3 + TypeScript + Vite frontend for JVuln Platform.

中文说明：[`README.zh-CN.md`](README.zh-CN.md)

## Stack

- Vue 3 Composition API
- TypeScript
- Vite
- Element Plus
- Axios
- Vue Router
- diff2html for patch rendering

## Development

```bash
npm install
npm run dev
```

The development server listens on `http://localhost:5173` by default and proxies API requests to the backend.

## Build

```bash
npm run build
```

The build runs `vue-tsc -b` before the Vite production build.

## UI Theme

The UI uses an IBM Carbon-inspired dark theme defined in `src/style.css`:

- dark base/surface/elevated backgrounds
- square, border-first components
- blue primary actions
- semantic status colors for success, running, failed, pending, critical, high, medium, and low
- IBM Plex Sans and IBM Plex Mono fonts

Patch diffs are rendered by `diff2html` in dark mode and then restyled with Carbon-compatible CSS variables in `src/views/PatchDiff.vue`.

## Localization

The interface supports Chinese and English without `vue-i18n`; it uses a small local helper:

- `src/locales/zh-CN.ts` — Chinese resources
- `src/locales/en-US.ts` — English resources
- `src/i18n/index.ts` — `useI18n()`, `t()`, `array()`, and `setLocale()`

The language switcher is in `src/App.vue`. The selected locale is stored in `localStorage` as `jvuln-locale`; Chinese is the default.

When adding UI text, put the string in both locale files and reference it through `t('path.to.key')` or `array('path.to.key')`.
