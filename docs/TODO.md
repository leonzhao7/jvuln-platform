# TODO

## DescriptionCorrector prompt优化

**问题**: DescriptionCorrector的LLM prompt太保守，无法纠正明显错误的CVE描述。

**复现**: CVE-2023-24163，官方描述为"SQL Injection"，但issue讨论中实际是反序列化漏洞。LLM读取了文章内容后仍判定描述正确，未做纠正。

**原因**: 当前system prompt缺乏具体的对比判断框架，LLM倾向于保守不改。

**修复方向**: 改写`buildSystemPrompt()`，引导LLM分步对比——先从文章提取漏洞类型，再从官方描述提取漏洞类型，最后比较是否一致。不一致则纠正。

**文件**: `backend/jvuln-stages/src/main/java/com/jvuln/collector/DescriptionCorrector.java`
