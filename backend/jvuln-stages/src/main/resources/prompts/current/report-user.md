为下面这个 CVE 生成漏洞分析报告。目标 CVE：**{{cve_id}}**。

严格按照系统提示中的固定中文结构（六个一级章节，顺序与标题不可改）输出，只输出报告 Markdown 本身。

下面是这个 CVE 的全部输入材料。

## 情报（Stage 1）

```json
{{intelligence}}
```

## 漏洞事实（Stage 2）

```json
{{vulnerability_facts}}
```

## 触发链（Stage 3）

```json
{{trigger_chain}}
```

## 根因分析（Stage 3）

```json
{{root_cause}}
```

## 补丁 diff

```diff
{{patch_diff}}
```

## 受影响组件（artifact）

```json
{{artifact}}
```

## 本次生成的 demo / PoC 文件

{{generated_files}}
