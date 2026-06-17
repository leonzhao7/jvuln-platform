# 后端重构记录与后续计划

**日期**: 2026-06-17  
**范围**: `/backend` 后端代码  
**目标**: 在不改变功能和接口字段语义的前提下，完成第一轮低风险重构，并输出后续可执行拆分计划。  
**依据**: `/root/workspace/java-guideline-ali.md`

## 本轮已实施

### 1. 控制器瘦身

已将 `AnalysisController` 中的非路由职责下沉到服务层，控制器主要保留：

- 路由定义
- 参数基础校验
- HTTP 状态码转换
- 调用应用服务

新增类：

- `com.jvuln.service.AnalysisQueryService`
- `com.jvuln.service.AnalysisStatusSyncService`
- `com.jvuln.controller.support.ApiResponseFactory`

#### 本次拆出的职责

从 `AnalysisController` 中迁出的逻辑包括：

- diff 文件读取与筛选
- report 文件读取
- transcript 文件读取与过滤
- Stage 4 结果归一化
- 磁盘 stage 状态同步

### 2. 修复阶段状态同步缺陷

已修复 `sync-status` 中对 artifacts 阶段的硬编码错误：

- 旧逻辑按 `stageNum != 5` 判断是否需要解析 artifacts 结果
- 当前项目总阶段数为 4，导致 Stage 4 的失败/暂停状态无法被正确识别

本次已改为：

- 使用 `PipelineConstants.STAGE_ARTIFACTS`
- 使用 `PipelineConstants.TOTAL_STAGES`

### 3. 统一控制器错误返回构造

新增 `ApiResponseFactory`，用于收敛以下场景的响应构造：

- `400 Bad Request`
- `409 Conflict`
- `500 Internal Server Error`

当前目标是降低控制器中重复的 `Map<String, String>` 拼装代码，保持对前端兼容。

### 4. 收敛 ObjectMapper 配置

`WorkspaceManager` 已改为使用 Spring 注入的 `ObjectMapper` 副本，不再自行 new：

- 保留现有 `INDENT_OUTPUT`
- 继承全局 Jackson 配置

这一步为后续统一替换项目内散落的 `new ObjectMapper()` 做准备。

## 本轮未改动但已确认的问题

以下问题已经确认，但本轮为了控制风险未做大改：

### 1. DTO / VO / 统一响应模型仍未完全落地

当前控制器仍存在：

- `Map<String, String>` 作为请求体
- 直接返回 JPA Entity
- `ResponseEntity<?>` 广泛使用

后续建议：

- 引入 `CreateAnalysisRequest`
- 引入 `AnalysisSummaryVO`
- 引入 `StageDetailVO`
- 引入统一 `ApiResponse<T>`
- 配套 `@RestControllerAdvice`

### 2. `ConfigController` 仍然承担过多职责

当前同时负责：

- LLM 配置增删改查
- LLM 连通性测试
- Java Profile 管理
- 运行时路径校验

后续建议拆分：

- `LlmConfigService`
- `LlmConnectivityTestService`
- `JavaProfileService`
- `JavaHomePathValidator`

### 3. 大类拆分尚未开始

以下类仍属于后续重点拆分对象：

- `ArtifactGenStage`
- `PatchAnalysisStage`
- `LlmCaller`
- `AiPatchSearchStrategy`

## 下一阶段建议

### 阶段一：接口模型标准化

优先级：高  
风险：低

建议内容：

- 引入请求 DTO
- 引入响应 VO
- 引入统一异常处理
- 禁止控制器直接返回实体类

### 阶段二：基础设施统一

优先级：高  
风险：中低

建议内容：

- 替换项目内散落的 `new ObjectMapper()`
- 统一 WebClient 创建方式
- 统一外部进程执行入口
- 统一日志字段规范

### 阶段三：大类拆分

优先级：中高  
风险：中高

建议内容：

- 将 `ArtifactGenStage` 拆为 orchestrator + validation + transcript + checkpoint + workspace bootstrap
- 将 `PatchAnalysisStage` 拆为 patch locating、diff analysis、result assemble
- 将 `LlmCaller` 拆为 request builder、response parser、stream parser

## 建议的提交顺序

1. 接口模型与全局异常
2. ObjectMapper 与工具类统一
3. `ConfigController` 服务化
4. `PatchAnalysisStage` 拆分
5. `ArtifactGenStage` 拆分

## 风险说明

本轮变更刻意避免了以下高风险动作：

- 修改接口字段名
- 修改前端调用路径
- 调整 stage 文件结构
- 重写 artifacts 阶段主流程

因此本轮更适合作为后续深层重构的基础整理版本。
