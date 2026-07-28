为下面这个 CVE 生成漏洞分析报告。目标 CVE：**{{cve_id}}**。

严格按照系统提示中的固定中文结构（六个一级章节，顺序与标题不可改）输出，只输出报告 Markdown 本身。

下面是这个 CVE 的全部输入材料，**按可信度从高到低排列（Stage 4 → Stage 1）**。当不同来源冲突时，以更靠前、可信度更高的来源为准。

## demo / PoC（Stage 4，可信度最高：已实际构建并复现验证）

{{generated_files}}

## 触发链（Stage 3）

```json
{{trigger_chain}}
```

## 根因分析（Stage 3）

```json
{{root_cause}}
```

## 漏洞事实（Stage 2）

```json
{{vulnerability_facts}}
```

## 补丁 diff（Stage 2）

```diff
{{patch_diff}}
```

## 情报（Stage 1，可信度最低：来源原始声明，可能不准确甚至有争议）

```json
{{intelligence}}
```

## 受影响组件（artifact，来自 Stage 1）

```json
{{artifact}}
```
