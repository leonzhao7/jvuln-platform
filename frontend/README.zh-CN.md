# 前端说明

JVuln Platform 的 Vue 3 + TypeScript + Vite 前端。

English documentation: [`README.md`](README.md)

## 技术栈

- Vue 3 Composition API
- TypeScript
- Vite
- Element Plus
- Axios
- Vue Router
- diff2html 补丁渲染

## 开发启动

```bash
npm install
npm run dev
```

开发服务器默认监听 `http://localhost:5173`，API 请求会代理到后端。

## 构建

```bash
npm run build
```

构建流程会先执行 `vue-tsc -b`，再执行 Vite 生产构建。

## UI 主题

界面使用 IBM Carbon 风格的深色主题，定义在 `src/style.css`：

- 深色 base / surface / elevated 背景
- 方正、边框优先的组件样式
- 蓝色主操作按钮
- success、running、failed、pending、critical、high、medium、low 等语义化状态颜色
- IBM Plex Sans 和 IBM Plex Mono 字体

补丁 Diff 使用 `diff2html` 的暗色模式渲染，并在 `src/views/PatchDiff.vue` 中通过 Carbon 风格 CSS 变量重新调色。

## 本地化

界面支持中文和英文，不依赖 `vue-i18n`，而是使用轻量本地辅助模块：

- `src/locales/zh-CN.ts` — 中文资源
- `src/locales/en-US.ts` — 英文资源
- `src/i18n/index.ts` — `useI18n()`、`t()`、`array()`、`setLocale()`

语言切换器位于 `src/App.vue`。当前语言会以 `jvuln-locale` 保存到 `localStorage`，默认语言为中文。

新增 UI 文案时，应同时写入两个 locale 文件，并通过 `t('path.to.key')` 或 `array('path.to.key')` 引用。
