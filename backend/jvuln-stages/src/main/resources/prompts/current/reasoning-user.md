## CVE 原始声明（可能不准确）
{{intelligence}}

## Stage 3 漏洞事实校验（优先采用）
{{vulnerability_facts}}

## 代码结构化分析
{{code_analysis}}

请按以下 JSON 格式输出分析结果：
{
  "trigger_chain": {
    "summary": "一句话概括触发路径",
    "steps": [
      {"seq": 1, "class": "类名", "method": "方法名", "description": "该步骤做了什么"}
    ],
    "entry_point": "外部可达的入口（HTTP 接口/反序列化入口等）",
    "sink": "最终危险操作"
  },
  "code_analysis": {
    "vuln_root_cause": "根因分析（为什么这段代码有漏洞）",
    "vuln_code_walkthrough": [
      {"line": "代码行内容", "explanation": "为什么有问题"}
    ],
    "fix_description": "补丁做了什么",
    "fix_completeness": "修复是否完整，有无绕过可能"
  },
  "impact": {
    "severity": "严重程度",
    "attack_vector": "攻击向量",
    "prerequisites": ["前置条件1", "前置条件2"],
    "consequences": ["可造成的后果"],
    "real_world_scenarios": ["真实利用场景描述"]
  },
  "secure_coding": {
    "violated_principles": ["违反了哪些安全编码原则"],
    "recommendations": ["给开发者的修复建议"],
    "similar_patterns": ["类似的漏洞模式需要注意"]
  },
  "detection_points": [
    {
      "id": "DP-1",
      "type": "dependency",
      "description": "使用了受影响版本的组件",
      "artifact": "groupId:artifactId",
      "affectedVersionRange": ">=x.x.x <y.y.y",
      "fixedVersion": "y.y.y"
    },
    {
      "id": "DP-2",
      "type": "code_pattern",
      "description": "代码中存在漏洞模式",
      "cweId": "CWE-xxx",
      "pattern": "用于匹配漏洞代码的正则表达式",
      "className": "包含漏洞模式的类名",
      "methodName": "包含漏洞模式的方法名",
      "matchTarget": "vulnerable_code"
    },
    {
      "id": "DP-3",
      "type": "config_risk",
      "description": "触发漏洞所需的配置条件",
      "configKeys": [
        {"key": "配置键名", "riskyValue": "触发风险的值"}
      ]
    },
    {
      "id": "DP-4",
      "type": "api_usage",
      "description": "代码中调用了危险 API",
      "dangerousApis": ["危险API调用1", "危险API调用2"],
      "safeAlternatives": ["安全替代方案1", "安全替代方案2"]
    }
  ]
}

detection_points 说明：

检测目标是【使用了该漏洞组件的用户项目】，不是漏洞组件自身的源码。
用户项目通常只是通过 Maven/Gradle 依赖引入了漏洞组件，自身代码中不会包含组件内部的实现类（如 Tomcat 的 DefaultServlet、Spring 的 AbstractAutowireCapableBeanFactory 等）

生成规则：
- type=dependency：几乎所有 CVE 都应包含。检查用户 pom.xml/build.gradle 中是否依赖了受影响版本
- type=config_risk：当漏洞需要特定配置才能触发时使用。检查用户项目的 application.yml/properties、web.xml、server.xml 等配置文件
- type=code_pattern：仅当漏洞模式可能出现在用户自己编写的代码中时才使用。例如 SQL 拼接注入（用户代码会写 SQL）、不安全反序列化（用户代码会调用 readObject）。不要为漏洞组件内部的实现细节生成 code_pattern
- type=api_usage：仅当用户代码可能直接调用漏洞组件暴露的危险 API 时才使用。例如用户代码调用 ObjectInputStream.readObject()、用户代码调用 Runtime.exec()。不要列举漏洞组件内部的私有方法调用

反面示例（不要生成）：
- 为 Tomcat CVE 生成检测 DefaultServlet.executePartialPut() 中 path.replace('/', '.') 的 code_pattern → 错误，这是 Tomcat 内部代码，用户项目中不存在
- 为 Spring CVE 生成检测 CachedIntrospectionResults 内部调用的 api_usage → 错误，用户代码不会直接调用这些内部类

正面示例（应该生成）：
- 为 MyBatis SQL 注入 CVE 生成检测用户 Mapper XML 中 ${} 拼接的 code_pattern → 正确，这是用户自己写的代码
- 为反序列化 CVE 生成检测用户代码中 ObjectInputStream.readObject() 调用的 api_usage → 正确，用户可能在自己的代码中调用

请根据实际漏洞特征生成 2-6 个检测要点，覆盖依赖、代码、配置和 API 各维度（不必每种类型都有，根据漏洞特性选择最相关的），宁可少而准，不要多而泛。

