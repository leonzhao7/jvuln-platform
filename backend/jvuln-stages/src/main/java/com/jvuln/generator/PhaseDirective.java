package com.jvuln.generator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class PhaseDirective {
    final AgentPhase phase;
    final String gap;
    final String expected;
    final String actual;
    final String fixHint;
    final List<String> allowedNextActions;

    PhaseDirective(AgentPhase phase, String gap, String expected, String actual,
                   String fixHint, List<String> allowedNextActions) {
        this.phase = phase;
        this.gap = gap == null ? "" : gap;
        this.expected = expected == null ? "" : expected;
        this.actual = actual == null ? "" : actual;
        this.fixHint = fixHint == null ? "" : fixHint;
        this.allowedNextActions = allowedNextActions == null
                ? Collections.<String>emptyList()
                : new ArrayList<>(allowedNextActions);
    }

    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", phase == null ? "" : phase.name());
        out.put("gap", gap);
        out.put("expected", expected);
        out.put("actual", actual);
        out.put("fixHint", fixHint);
        out.put("allowedNextActions", allowedNextActions);
        return out;
    }
}
