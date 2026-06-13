package com.jvuln.analyzer;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CwePatternMatcher {

    private static final List<CweRule> RULES = new ArrayList<>();

    static {
        RULES.add(new CweRule("CWE-44", "Path Equivalence",
                "replace\\('/'\\s*,\\s*'\\.'\\)|replace\\(\"/\"\\s*,\\s*\"\\.\"\\)",
                "Path separator '/' replaced with '.', making path equivalent to different resource (e.g. directory traversal via dot notation)"));

        RULES.add(new CweRule("CWE-22", "Path Traversal",
                "new\\s+File\\(\\s*\\w+\\s*,\\s*\\w*[Pp]ath\\w*\\)|new\\s+File\\(\\s*\\w+Dir\\s*,\\s*converted",
                "User-controlled path used to construct File object, enabling directory traversal"));

        RULES.add(new CweRule("CWE-377", "Insecure Temporary File",
                "createNewFile\\(\\)|deleteOnExit\\(\\)|new\\s+File\\([^)]*Dir[^)]*,\\s*converted",
                "Temporary file created with predictable name, enabling TOCTOU race condition or symlink attacks"));

        RULES.add(new CweRule("CWE-502", "Deserialization of Untrusted Data",
                "readObject\\(\\)|ObjectInputStream|deserializ",
                "Object deserialization from untrusted input can lead to arbitrary code execution"));

        RULES.add(new CweRule("CWE-404", "Improper Resource Shutdown",
                "createNewFile.*(?!finally)|deleteOnExit\\(\\)(?!.*finally)",
                "Temporary file may not be deleted in all code paths (missing finally block cleanup)"));
    }

    public static List<MatchResult> match(String code) {
        List<MatchResult> results = new ArrayList<>();
        for (CweRule rule : RULES) {
            Pattern p = Pattern.compile(rule.pattern, Pattern.DOTALL);
            Matcher m = p.matcher(code);
            while (m.find()) {
                String matched = m.group();
                if (matched.length() > 120) matched = matched.substring(0, 120) + "...";
                results.add(new MatchResult(rule.cweId, rule.cweName, rule.pattern, matched, rule.explanation));
            }
        }
        return results;
    }

    public static class MatchResult {
        public final String cweId;
        public final String cweName;
        public final String pattern;
        public final String matchedCode;
        public final String explanation;

        MatchResult(String cweId, String cweName, String pattern, String matchedCode, String explanation) {
            this.cweId = cweId;
            this.cweName = cweName;
            this.pattern = pattern;
            this.matchedCode = matchedCode;
            this.explanation = explanation;
        }
    }

    private static class CweRule {
        final String cweId;
        final String cweName;
        final String pattern;
        final String explanation;

        CweRule(String cweId, String cweName, String pattern, String explanation) {
            this.cweId = cweId;
            this.cweName = cweName;
            this.pattern = pattern;
            this.explanation = explanation;
        }
    }
}
