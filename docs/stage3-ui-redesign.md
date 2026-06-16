# Stage 3 (AI 推理) UI 重新设计

## 设计日期
2026-06-16

## 设计目标
优化 Stage 3 (AI Reasoning) 的信息展示，提升可读性、交互性和视觉层次。

## 主要改进

### 1. 新增 Summary Bar
在页面顶部显示核心指标概览：
- 严重程度 (Severity)
- 触发步骤数 (Trigger Steps)
- 代码走查条目数 (Code Walkthrough)
- 检测要点数 (Detection Points)
- 攻击向量 (Attack Vector)

**优势**：快速了解漏洞关键信息，无需滚动页面。

### 2. 触发链路流程图化 (Trigger Chain Flow)
**旧设计**：扁平列表 + 左侧边框
**新设计**：垂直流程图
- 左侧渐变连接线（蓝色渐变到红色）
- 每个步骤用圆点标记
- 最后一步（sink）用红色高亮
- Hover 高亮效果

**优势**：
- 视觉上更直观展示调用链路
- 颜色渐变表达风险递增
- 明确标识危险操作点

### 3. 根因分析视觉强化
**旧设计**：普通文本段落
**新设计**：左侧红色边框强调块

**优势**：突出核心问题描述，视觉权重更高。

### 4. 代码走查分栏设计
**旧设计**：一行代码 + 一行解释（flex-direction: column）
**新设计**：上下分栏布局
- 上方：深色背景代码区
- 下方：浅色背景解释区

**优势**：类似 IDE 的代码展示，更符合开发者习惯。

### 5. 修复信息并排展示
**旧设计**：`fix_description` 和 `fix_completeness` 各占一行
**新设计**：2列 grid 卡片并排

**优势**：节省垂直空间，信息密度更高。

### 6. Impact + Secure Coding 合并为 Tab
**旧设计**：两个独立 section 平铺
**新设计**：合并为 Tab 切换面板
- Tab 1: 影响评估 (Impact)
- Tab 2: 安全编码 (Secure Coding)

**优势**：节省垂直空间，减少滚动距离。

### 7. 检测要点增加类型过滤
**旧设计**：直接展示所有检测点卡片
**新设计**：顶部增加类型过滤标签
- 全部 / 依赖检测 / API调用 / 代码模式 / 配置风险
- 每个标签显示该类型数量
- 点击切换显示

**优势**：快速定位特定类型的检测点。

## 实现细节

### 文件修改
- `frontend/src/views/AnalysisDetail.vue`
  - 模板 (template): 完全重写 Stage 3 section（行 455-598）
  - 脚本 (script): 新增 `activeTab`、`dpFilter`、`dpCountByType()`、`filteredDetectionPoints`
  - 样式 (style): 新增 `rs-*` 前缀的样式类，保留 `jv-*` 旧类供 Stage 4 使用

### CSS 类命名
- 新设计使用 `rs-*` 前缀（Reasoning Redesign）
- 旧类名 `jv-reasoning-section`、`jv-field-label` 保留给 Stage 4 使用
- 检测点类型标签：`rs-dp-type-{dependency|code_pattern|config_risk|api_usage}`

### 数据兼容性
**无后端改动**，完全兼容现有 Stage 3 JSON 数据结构：
- `trigger_chain`: { summary, steps[], entry_point, sink }
- `code_analysis`: { vuln_root_cause, vuln_code_walkthrough[], fix_description, fix_completeness }
- `impact`: { severity, attack_vector, prerequisites[], consequences[], real_world_scenarios[] }
- `secure_coding`: { violated_principles[], recommendations[], similar_patterns[] }
- `detection_points[]`: { id, type, description, ... }

## 视觉特性

### 配色
- Summary Bar: 深色背景 `--bg-surface` + 细边框
- 流程线渐变: `linear-gradient(180deg, var(--accent) 0%, var(--critical) 100%)`
- 入口标记: 绿色 `--success`
- 危险操作标记: 红色 `--critical`
- 检测点类型标签配色保持原设计

### 交互
- Tab 切换: `active` 类显示底部蓝色边框
- 检测点过滤: `active` 类显示蓝色背景高亮
- 流程步骤 Hover: 边框变为蓝色半透明

## 预览
静态 mockup 文件：`docs/stage3-mockup.html`
- 使用真实 CVE-2022-1471 数据
- 可在浏览器直接打开预览

## 构建验证
```bash
npm run build  # ✓ 通过
npx vue-tsc --noEmit  # ✓ 类型检查通过
```

## 未来优化方向
1. 触发链路支持横向展示（当步骤数较少时）
2. 代码走查支持语法高亮
3. 检测点支持导出为 JSON/YAML
4. Summary Bar 支持复制为 Markdown
