# Backend项目重构文档

## 重构概述

将原有的8个模块重构为3个清晰职责的模块，提升代码组织和维护性。

## 重构前后对比

### 重构前（8个模块）
```
backend/
├── jvuln-store        # 数据存储
├── jvuln-llm          # LLM调用
├── jvuln-collector    # Stage 1: 情报收集
├── jvuln-patcher      # Stage 2: 补丁定位
├── jvuln-analyzer     # Stage 3: 代码分析
├── jvuln-generator    # Stage 5: 制品生成
├── jvuln-pipeline     # 流水线编排
└── jvuln-app          # 应用入口
```

### 重构后（3个模块）
```
backend/
├── jvuln-utils        # 通用工具（存储、LLM、HTTP、Pipeline接口）
├── jvuln-stages       # Stage实现（Collector、Patcher、Reasoning、Generator）
└── jvuln-app          # 应用管理（Pipeline、REST API、调度）
```

## 模块职责

### 1. jvuln-utils（通用工具模块）

**职责：** 提供与业务流程无关的通用工具类和基础设施

**包含内容：**
- `com.jvuln.store` - 数据模型和Repository
- `com.jvuln.llm` - LLM调用（LlmCaller、AnthropicCaller）
- `com.jvuln.llm.util` - HTTP和LLM重试工具（HttpUtil、LlmUtil）
- `com.jvuln.pipeline.stage` - Stage接口定义
- `com.jvuln.pipeline.model` - StageResult等模型

**依赖：**
- Spring Boot WebFlux
- Spring Data JPA
- H2 Database
- Jackson

**特性：**
- 无业务逻辑
- 可被其他模块复用
- 包含HTTP/LLM请求重试机制

### 2. jvuln-stages（Stage实现模块）

**职责：** 实现各个Stage的具体功能

**包含内容：**
- `com.jvuln.collector` - Stage 1: 情报收集
  - IntelligenceStage
  - IntelSource接口及实现（GHSA、NVD、OSV等）
- `com.jvuln.patcher` - Stage 2: 补丁分析
  - PatchAnalysisStage
  - analyzer/strategy内部实现
- `com.jvuln.reasoning` - Stage 3: AI推理
  - ReasoningStage
- `com.jvuln.generator` - Stage 4: 制品生成
  - ArtifactGenStage
  - Docker文件生成

**依赖：**
- jvuln-utils
- JavaParser（代码解析）
- JGit（Git操作）
- Jsoup（HTML解析）

**特性：**
- 实现Stage接口
- 只关注业务逻辑
- 依赖utils提供的基础设施

### 3. jvuln-app（应用管理模块）

**职责：** 系统管理、调度、对外接口

**包含内容：**
- `com.jvuln.pipeline` - Pipeline引擎和调度
  - PipelineEngine
  - ReasoningStage（Stage 4）
- `com.jvuln.app.controller` - REST API
- `com.jvuln.app.service` - 业务服务
- Spring Boot主类

**依赖：**
- jvuln-utils
- jvuln-stages
- Spring Boot Web

**特性：**
- 应用启动入口
- 提供HTTP接口
- 协调各Stage执行
- 管理任务生命周期

## 模块依赖关系

```
jvuln-app
   ├── depends on jvuln-utils
   └── depends on jvuln-stages
          └── depends on jvuln-utils

jvuln-utils (无依赖其他内部模块)
```

## 重构步骤记录

1. **创建新模块目录和pom.xml**
   - 创建jvuln-utils和jvuln-stages目录
   - 编写各模块的pom.xml

2. **迁移代码**
   - jvuln-store → jvuln-utils/com/jvuln/store
   - jvuln-llm → jvuln-utils/com/jvuln/llm
   - jvuln-collector → jvuln-stages/com/jvuln/collector
   - jvuln-patcher → jvuln-stages/com/jvuln/patcher
   - jvuln-analyzer → jvuln-stages/com/jvuln/analyzer
   - jvuln-generator → jvuln-stages/com/jvuln/generator
   - jvuln-pipeline → jvuln-app/com/jvuln/pipeline

3. **更新根pom.xml**
   - 修改modules列表
   - 更新dependencyManagement

4. **更新jvuln-app依赖**
   - 移除旧模块依赖
   - 添加jvuln-utils和jvuln-stages依赖

5. **删除旧模块目录**
   - 删除7个旧模块

6. **添加HTTP/LLM重试机制**
   - 创建HttpUtil和LlmUtil工具类
   - 修改LlmCaller使用重试机制
   - 修改GhsaSource使用HttpUtil

7. **编译验证**
   - `mvn clean compile -DskipTests`
   - BUILD SUCCESS ✅

## 优势

### 1. 清晰的职责分离
- Utils: 纯工具，无业务逻辑
- Stages: 纯业务，无基础设施
- App: 纯编排，无具体实现

### 2. 更好的代码复用
- 工具类统一管理
- Stage共享基础设施
- 减少重复代码

### 3. 简化依赖关系
- 从8个模块减少到3个
- 依赖链路更短：App → Stages → Utils
- 更容易理解和维护

### 4. 便于扩展
- 新增Stage只需在jvuln-stages中添加
- 新增工具类只需在jvuln-utils中添加
- 模块边界清晰

### 5. 编译速度提升
- 模块数量减少，编译更快
- 依赖关系简单，Maven构建效率更高

## 后续优化建议

1. **继续完善重试机制**
   - 修改NvdSource、OsvSource使用HttpUtil
   - 修改Patch Analysis内部实现中的HTTP请求

2. **添加单元测试**
   - 为HttpUtil和LlmUtil添加测试
   - 确保重试逻辑正确

3. **文档完善**
   - 为每个模块添加README
   - 说明各模块的使用方式

4. **性能优化**
   - 分析各Stage的性能瓶颈
   - 优化数据库访问
   - 考虑引入缓存机制

## 编译和运行

### 编译
```bash
cd /root/workspace/jvuln-platform/backend
mvn clean compile
```

### 打包
```bash
mvn clean package -DskipTests
```

### 运行
```bash
cd jvuln-app/target
java -jar jvuln-app-1.0.0-SNAPSHOT.jar
```

## 注意事项

1. **包名保持不变**：重构只改变模块结构，不改变包名，确保向后兼容
2. **依赖关系**：严格遵守单向依赖，避免循环依赖
3. **配置文件**：application.yaml等配置文件仍在jvuln-app中
4. **资源文件**：静态资源和前端构建产物仍在jvuln-app中

## 总结

本次重构成功将8个松散的模块整合为3个职责清晰的模块，提升了代码的可维护性和可扩展性。同时添加了HTTP和LLM请求的重试机制，增强了系统的稳定性和容错能力。

编译验证通过，项目结构更加合理，为后续开发打下了良好的基础。
