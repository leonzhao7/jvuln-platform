# JVuln Platform 开发路线图

**版本**: v1.0.0 → v2.0.0  
**时间跨度**: 2026年6月 - 2026年12月  
**目标**: 从教育原型到生产级安全分析平台

---

## 🎯 版本规划

### Phase 1: 稳定性和安全 (v1.1.0) - 2周
**目标**: 修复安全漏洞，提升系统稳定性

#### 安全加固 🔴
- [ ] **修复路径遍历漏洞**
  ```java
  // WorkspaceManager.java
  public Path getCvePath(String cveId) {
      // 添加路径规范化
      Path normalized = Paths.get(cveId).normalize();
      if (normalized.toString().contains("..")) {
          throw new SecurityException("Invalid CVE ID");
      }
      return baseDir.resolve(normalized);
  }
  ```

- [ ] **实现全局异常处理器**
  ```java
  @ControllerAdvice
  public class GlobalExceptionHandler {
      @ExceptionHandler(Exception.class)
      public ResponseEntity<ErrorResponse> handleException(Exception e) {
          log.error("Unexpected error", e);
          return ResponseEntity.status(500)
              .body(new ErrorResponse("INTERNAL_ERROR", e.getMessage()));
      }
  }
  ```

- [ ] **添加请求参数验证**
  ```java
  public class CreateAnalysisRequest {
      @Pattern(regexp = "^CVE-\\d{4}-\\d{4,}$", message = "Invalid CVE ID format")
      @NotNull
      private String cveId;
  }
  ```

- [ ] **禁用生产环境H2 Console**
  ```yaml
  # application-prod.yml
  spring:
    h2:
      console:
        enabled: false
  ```

#### 稳定性提升
- [ ] 修复SSE连接泄漏
- [ ] 添加前端错误处理和重试机制
- [ ] 实现Pipeline超时机制
- [ ] 清理Deprecated代码

**交付物**:
- ✅ 安全漏洞修复报告
- ✅ 稳定性测试报告
- ✅ v1.1.0 Release Notes

---

### Phase 2: 测试和监控 (v1.2.0) - 3周
**目标**: 建立测试体系，实现可观测性

#### 测试框架
- [ ] **搭建测试基础设施**
  ```xml
  <dependencies>
      <dependency>
          <groupId>org.springframework.boot</groupId>
          <artifactId>spring-boot-starter-test</artifactId>
          <scope>test</scope>
      </dependency>
      <dependency>
          <groupId>org.mockito</groupId>
          <artifactId>mockito-core</artifactId>
          <scope>test</scope>
      </dependency>
  </dependencies>
  ```

- [ ] **核心业务逻辑单元测试** (目标覆盖率: 60%)
  ```java
  @SpringBootTest
  class DiffRelevanceFilterTest {
      @Test
      void shouldFilterIrrelevantFiles() {
          // 测试过滤逻辑
      }
  }
  ```

- [ ] **集成测试**
  ```java
  @SpringBootTest
  @AutoConfigureMockMvc
  class AnalysisControllerIntegrationTest {
      @Test
      void testCompletePipeline() {
          // 端到端测试
      }
  }
  ```

- [ ] **前端单元测试**
  ```typescript
  import { describe, it, expect } from 'vitest'
  import { mount } from '@vue/test-utils'
  import Dashboard from '../Dashboard.vue'
  
  describe('Dashboard', () => {
      it('renders stats correctly', () => {
          // 测试组件渲染
      })
  })
  ```

#### 可观测性
- [ ] **集成Spring Boot Actuator**
  ```yaml
  management:
    endpoints:
      web:
        exposure:
          include: health,metrics,prometheus
    metrics:
      tags:
        application: ${spring.application.name}
  ```

- [ ] **自定义业务指标**
  ```java
  @Component
  public class PipelineMetrics {
      private final MeterRegistry registry;
      
      public void recordAnalysisComplete(String cveId, long durationMs) {
          registry.timer("jvuln.analysis.duration",
              "cve", cveId).record(durationMs, TimeUnit.MILLISECONDS);
      }
  }
  ```

- [ ] **结构化日志**
  ```java
  log.info("Pipeline completed: cveId={}, duration={}ms, stages={}", 
      cveId, duration, completedStages);
  ```

- [ ] **审计日志**
  ```java
  @Aspect
  @Component
  public class AuditAspect {
      @Around("@annotation(Audited)")
      public Object audit(ProceedingJoinPoint pjp) {
          // 记录审计事件
      }
  }
  ```

**交付物**:
- ✅ 测试覆盖率报告
- ✅ Grafana监控大盘配置
- ✅ 日志规范文档

---

### Phase 3: 性能优化 (v1.3.0) - 2周
**目标**: 提升系统性能和响应速度

#### 缓存实现
- [ ] **LLM响应缓存**
  ```java
  @Cacheable(value = "llm-reasoning", key = "#cveId")
  public LlmResponse reasoning(String cveId, String prompt) {
      return llmClient.chat(request);
  }
  ```

- [ ] **CVE情报缓存**
  ```java
  @Configuration
  @EnableCaching
  public class CacheConfig {
      @Bean
      public CacheManager cacheManager() {
          return new CaffeineCacheManager("llm-reasoning", "cve-intel");
      }
  }
  ```

#### 数据库优化
- [ ] 添加必要的数据库索引
- [ ] 实现分页查询
- [ ] 优化N+1查询

#### 并发控制
- [ ] **使用Redis分布式锁**
  ```java
  @Component
  public class RedisPipelineLock {
      public boolean tryLock(String cveId) {
          return redisTemplate.opsForValue()
              .setIfAbsent("lock:" + cveId, "1", 10, TimeUnit.MINUTES);
      }
  }
  ```

**交付物**:
- ✅ 性能测试报告
- ✅ 优化前后对比
- ✅ 容量规划建议

---

### Phase 4: 功能增强 (v1.4.0) - 4周
**目标**: 添加实用的新功能

#### 批量分析
- [ ] **批量CVE导入**
  ```java
  @PostMapping("/api/analysis/batch")
  public ResponseEntity<?> batchAnalysis(
      @RequestBody List<String> cveIds) {
      // 批量提交分析任务
  }
  ```

- [ ] **分析队列管理**
  ```java
  @Component
  public class AnalysisQueue {
      private final Queue<String> pending = new ConcurrentLinkedQueue<>();
      
      @Scheduled(fixedDelay = 5000)
      public void processPending() {
          // 处理队列中的CVE
      }
  }
  ```

#### 分析历史对比
- [ ] **CVE版本追踪**
  ```sql
  CREATE TABLE cve_analysis_history (
      id BIGINT PRIMARY KEY,
      cve_id VARCHAR(50),
      analysis_version INT,
      created_at TIMESTAMP,
      data JSON
  );
  ```

- [ ] **Diff视图**
  ```vue
  <template>
    <div class="history-diff">
      <DiffViewer :old="oldAnalysis" :new="newAnalysis" />
    </div>
  </template>
  ```

#### 自定义规则
- [ ] **CWE规则管理界面**
  ```vue
  <template>
    <el-form>
      <el-form-item label="CWE ID">
        <el-input v-model="rule.cweId" />
      </el-form-item>
      <el-form-item label="Pattern">
        <el-input type="textarea" v-model="rule.pattern" />
      </el-form-item>
    </el-form>
  </template>
  ```

#### 报告导出
- [ ] PDF报告生成
- [ ] Markdown导出
- [ ] JSON数据导出

**交付物**:
- ✅ 功能演示视频
- ✅ 用户手册更新
- ✅ API文档更新

---

### Phase 5: 多语言支持 (v1.5.0) - 4周
**目标**: 支持Python和Go语言的漏洞分析

#### 架构重构
- [ ] **抽象CodeAnalyzer接口**
  ```java
  public interface CodeAnalyzer {
      String language();
      List<AnalysisResult> analyze(List<FileChange> changes);
  }
  
  @Component
  public class JavaCodeAnalyzer implements CodeAnalyzer {
      @Override
      public String language() { return "java"; }
  }
  
  @Component
  public class PythonCodeAnalyzer implements CodeAnalyzer {
      @Override
      public String language() { return "python"; }
  }
  ```

- [ ] **动态Analyzer选择**
  ```java
  @Service
  public class CodeAnalysisService {
      private final Map<String, CodeAnalyzer> analyzers;
      
      public AnalysisResult analyze(String filePath, String content) {
          String lang = detectLanguage(filePath);
          CodeAnalyzer analyzer = analyzers.get(lang);
          return analyzer.analyze(content);
      }
  }
  ```

#### Python分析器实现
- [ ] 使用`ast`模块解析Python代码
- [ ] Python CWE模式库
- [ ] Python漏洞触发链分析

#### Go分析器实现
- [ ] 使用`go/parser`解析Go代码
- [ ] Go CWE模式库
- [ ] Go漏洞触发链分析

**交付物**:
- ✅ 多语言支持文档
- ✅ Python CVE验证案例
- ✅ Go CVE验证案例

---

### Phase 6: 企业级特性 (v2.0.0) - 6周
**目标**: 满足企业级部署需求

#### 用户权限系统
- [ ] **Spring Security集成**
  ```java
  @Configuration
  @EnableWebSecurity
  public class SecurityConfig {
      @Bean
      public SecurityFilterChain filterChain(HttpSecurity http) {
          return http
              .authorizeHttpRequests(auth -> auth
                  .requestMatchers("/api/admin/**").hasRole("ADMIN")
                  .requestMatchers("/api/analysis/**").hasRole("ANALYST")
                  .anyRequest().authenticated()
              )
              .build();
      }
  }
  ```

- [ ] **用户管理界面**
- [ ] **角色权限管理**
- [ ] **操作审计日志**

#### 团队协作
- [ ] **分析结果分享**
  ```java
  @PostMapping("/api/analysis/{cveId}/share")
  public ResponseEntity<?> shareAnalysis(
      @PathVariable String cveId,
      @RequestBody List<String> userIds) {
      // 分享给团队成员
  }
  ```

- [ ] **评论和标注**
- [ ] **分析结果评分**

#### 私有化部署
- [ ] **Docker镜像**
  ```dockerfile
  FROM openjdk:8-jre-alpine
  COPY jvuln-app.jar /app.jar
  ENTRYPOINT ["java", "-jar", "/app.jar"]
  ```

- [ ] **Docker Compose配置**
  ```yaml
  version: '3.8'
  services:
    jvuln-backend:
      build: ./backend
      ports:
        - "8080:8080"
    jvuln-frontend:
      build: ./frontend
      ports:
        - "80:80"
    redis:
      image: redis:7-alpine
    postgres:
      image: postgres:15-alpine
  ```

- [ ] **Kubernetes部署配置**
- [ ] **离线CVE数据库**
- [ ] **本地LLM支持(Ollama)**

#### API网关
- [ ] 请求限流
- [ ] API密钥管理
- [ ] OpenAPI 3.0文档

**交付物**:
- ✅ 企业版部署文档
- ✅ 管理员手册
- ✅ v2.0.0完整发布包

---

## 🛠️ 技术升级计划

### Spring Boot 3.x升级 (Q3 2026)
**当前**: Spring Boot 2.7.18 (已EOL)  
**目标**: Spring Boot 3.2.x

**升级步骤**:
1. Java 8 → Java 17
2. javax.* → jakarta.*
3. 依赖兼容性检查
4. 测试所有功能

### 前端技术栈升级
- [ ] Vue 3.5 → 最新版
- [ ] TypeScript 6.0 → 最新版
- [ ] Vite 8.0 → 最新版

---

## 📊 KPI指标

### v1.1.0
- 安全漏洞: 0个
- 稳定性: 无Crash
- 代码覆盖率: >30%

### v1.2.0
- 代码覆盖率: >60%
- 监控大盘: 完整
- 日志规范: 100%

### v1.3.0
- 分析速度提升: >30%
- 缓存命中率: >50%
- 并发支持: 10+ tasks

### v2.0.0
- 用户满意度: >90%
- 企业客户: >5家
- 社区贡献者: >10人

---

## 🎓 学习资源

### 推荐阅读
1. **《Clean Code》** - Robert C. Martin
2. **《Designing Data-Intensive Applications》** - Martin Kleppmann
3. **《Spring in Action》** - Craig Walls

### 相关项目
1. **Snyk** - 漏洞扫描工具
2. **Trivy** - 容器漏洞扫描
3. **CodeQL** - 代码分析引擎

---

*路线图会根据实际进展动态调整*