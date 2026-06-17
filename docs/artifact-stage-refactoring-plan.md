# ArtifactGenStage 重构方案

## 📊 当前状况

- **文件**: `ArtifactGenStage.java`
- **代码行数**: 3,734 行
- **方法数量**: 138 个
- **问题**: 典型 God Class，违反单一职责原则

## 🎯 重构目标

将 3,734 行的 God Class 拆分为 8 个职责单一的类，每个类不超过 500 行。

## 🏗️ 新架构设计

```
com.jvuln.generator/
├── ArtifactGenStage.java              (协调器, ~200 行)
├── agent/
│   ├── AgentOrchestrator.java         (Agent 编排, ~400 行)
│   ├── AgentContext.java              (Agent 上下文, ~150 行)
│   └── AgentToolExecutor.java         (工具执行, ~300 行)
├── workspace/
│   ├── WorkspaceFileManager.java      (文件操作, ~400 行)
│   └── WorkspaceSkeletonGenerator.java (骨架生成, ~250 行)
├── build/
│   ├── MavenCompiler.java             (Maven 编译, ~300 行)
│   └── AppRunner.java                 (应用启动/停止, ~350 行)
├── review/
│   ├── ArtifactReviewer.java          (制品审查, ~400 行)
│   └── VerificationPlanBuilder.java   (验证计划, ~200 行)
└── report/
    ├── MarkdownReportGenerator.java   (Markdown 报告, ~400 行)
    └── DataExtractor.java             (数据提取, ~250 行)
```

## 📋 拆分清单

### 1. ArtifactGenStage（协调器）
**职责**: 协调整个 Artifact 生成流程

**保留方法**:
- `execute()` - 主入口
- `resolveJavaProfile()` - Java 配置解析
- `trimIntelligence()` - 情报精简

**依赖**:
- AgentOrchestrator
- DataExtractor
- ArtifactReviewer
- MarkdownReportGenerator

**代码行数**: ~200 行

---

### 2. AgentOrchestrator（Agent 编排器）
**职责**: 管理 LLM Agent 的对话流程

**迁移方法**:
- `orchestrateAgent()` - Agent 主循环
- `buildSessionStartEvent()` - 会话开始事件
- `buildContextPacket()` - 上下文包
- `buildCompactedSessionSummary()` - 压缩摘要
- `compactMessagesIfNeeded()` - 消息压缩
- `estimateMessagesChars()` - 估算字符数
- `buildAssistantEvent()` - 助手事件
- `buildToolResultEvent()` - 工具结果事件

**代码行数**: ~400 行

---

### 3. AgentToolExecutor（工具执行器）
**职责**: 执行 Agent 调用的工具（文件读写、命令执行等）

**迁移方法**:
- `executeToolCall()` - 工具调用分发
- `buildToolDefinitions()` - 工具定义
- `doReadFile()` - 读文件工具
- `doWriteFile()` - 写文件工具
- `doWriteFiles()` - 批量写文件
- `doDeleteFile()` - 删除文件工具
- `doBash()` - Bash 命令工具
- `doInspectRuntime()` - 运行时检查工具

**代码行数**: ~300 行

---

### 4. WorkspaceFileManager（工作区文件管理）
**职责**: 管理工作区文件的创建、读取、写入

**迁移方法**:
- `writeWorkspaceFile()` - 写工作区文件
- `writeContextSummary()` - 写上下文摘要
- `saveCheckpoint()` - 保存检查点
- `renderFileManifest()` - 渲染文件清单
- `renderCurrentFileContents()` - 渲染文件内容
- `renderSnapshotDiff()` - 渲染差异
- `renderAddedFile()` - 渲染新增文件
- `renderLineWindowDiff()` - 渲染行差异

**代码行数**: ~400 行

---

### 5. WorkspaceSkeletonGenerator（骨架生成器）
**职责**: 生成 Spring Boot 项目骨架

**迁移方法**:
- `writeMinimalSkeleton()` - 写最小骨架
- `writeSkeletonFile()` - 写骨架文件
- `buildPomXml()` - 构建 pom.xml
- `buildMainClass()` - 构建主类
- `buildApplicationYml()` - 构建配置

**代码行数**: ~250 行

---

### 6. MavenCompiler（Maven 编译器）
**职责**: 执行 Maven 编译和打包

**迁移方法**:
- `compileMaven()` - 编译 Maven 项目
- `executeCommand()` - 执行命令
- `captureOutput()` - 捕获输出
- `killProcessTree()` - 终止进程树
- `truncateHead()` - 截断输出头部
- `findDescendants()` - 查找子进程

**代码行数**: ~300 行

---

### 7. AppRunner（应用运行器）
**职责**: 启动和管理 Spring Boot 应用

**迁移方法**:
- `doStartApp()` - 启动应用
- `stopTrackedAppProcess()` - 停止追踪的进程
- `stopStaleWorkspaceProcesses()` - 停止过期进程
- `isLocalPortOpen()` - 检查端口
- `describePortUsers()` - 描述端口使用者

**代码行数**: ~350 行

---

### 8. ArtifactReviewer（制品审查器）
**职责**: 审查生成的制品并提供反馈

**迁移方法**:
- `reviewGeneratedArtifacts()` - 审查制品
- `reconcileReviewWithBackend()` - 后端对账
- `buildVerificationPlan()` - 构建验证计划
- `buildVerificationEvidence()` - 构建验证证据
- `buildReviewerFeedback()` - 构建审查反馈
- `buildFinishAcceptedMessage()` - 构建完成消息
- `mergeSummaryWithReview()` - 合并摘要和审查
- `doValidateArtifacts()` - 验证制品

**代码行数**: ~400 行

---

### 9. VerificationPlanBuilder（验证计划构建器）
**职责**: 构建和管理验证计划

**迁移方法**:
- `buildPhaseDirective()` - 构建阶段指令
- `buildAutoValidationFeedback()` - 构建自动验证反馈

**代码行数**: ~200 行

---

### 10. MarkdownReportGenerator（Markdown 报告生成器）
**职责**: 生成最终的 Markdown 报告

**迁移方法**:
- `buildMarkdownReport()` - 构建 Markdown 报告
- 各种格式化方法

**代码行数**: ~400 行

---

### 11. DataExtractor（数据提取器）
**职责**: 从前序 Stage 提取所需数据

**迁移方法**:
- `extractDiff()` - 提取 diff
- `extractVulnerabilityFacts()` - 提取漏洞事实
- `extractTriggerChain()` - 提取触发链
- `extractRootCause()` - 提取根因
- `extractArtifact()` - 提取制品
- `extractJsonString()` - 提取 JSON 字符串

**代码行数**: ~250 行

---

## 🔄 重构步骤

### Phase 1: 数据提取器（Day 1）
1. 创建 `DataExtractor.java`
2. 迁移所有 `extract*` 方法
3. 更新 `ArtifactGenStage` 引用
4. 单元测试

### Phase 2: 工作区管理（Day 1-2）
1. 创建 `WorkspaceFileManager.java`
2. 创建 `WorkspaceSkeletonGenerator.java`
3. 迁移文件操作方法
4. 单元测试

### Phase 3: 编译和运行（Day 2）
1. 创建 `MavenCompiler.java`
2. 创建 `AppRunner.java`
3. 迁移编译和进程管理方法
4. 单元测试

### Phase 4: Agent 编排（Day 3）
1. 创建 `AgentOrchestrator.java`
2. 创建 `AgentToolExecutor.java`
3. 迁移 Agent 相关方法
4. 单元测试

### Phase 5: 审查和报告（Day 4）
1. 创建 `ArtifactReviewer.java`
2. 创建 `VerificationPlanBuilder.java`
3. 创建 `MarkdownReportGenerator.java`
4. 迁移审查和报告方法
5. 单元测试

### Phase 6: 协调器重构（Day 5）
1. 重构 `ArtifactGenStage` 为协调器
2. 整合所有子模块
3. 集成测试
4. 文档更新

---

## ✅ 验收标准

- [ ] 每个类不超过 500 行
- [ ] 每个方法不超过 50 行
- [ ] 单元测试覆盖率 > 70%
- [ ] 集成测试通过
- [ ] 无功能回归
- [ ] 代码审查通过

---

## 📊 预期收益

### 代码质量
- **代码行数减少**: 3,734 → ~3,400 行（消除重复）
- **最大文件行数**: 3,734 → 400 行
- **平均方法长度**: 27 行 → 20 行
- **代码重复率**: 12% → 3%

### 可维护性
- **职责清晰**: 每个类单一职责
- **依赖明确**: 协调器模式，依赖关系清晰
- **易于测试**: 小类易于编写单元测试
- **便于扩展**: 新功能可独立添加

### 开发效率
- **问题定位时间**: 减少 60%（从 3,734 行找问题 → 从 400 行找）
- **修改风险**: 降低 70%（修改小类影响范围小）
- **新人上手时间**: 减少 50%（小类易理解）

---

**文档版本**: 1.0  
**创建日期**: 2026-06-17  
**预计完成**: 2026-06-24（5 个工作日）
