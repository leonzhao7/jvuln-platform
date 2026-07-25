package com.jvuln.collector;

import com.jvuln.llm.LlmClient;
import com.jvuln.llm.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@ExtendWith(MockitoExtension.class)
class PatchCommitInfererTest {

    @Mock LlmClient llmClient;
    @Mock PromptRegistry promptRegistry;
    PatchCommitInferer inferer;

    @BeforeEach void setUp() {
        inferer = new PatchCommitInferer(llmClient, promptRegistry, "");
    }

    @Test void emptyResultWhenNoGithubRepo() {
        PatchCommitInferer.InferenceResult r = inferer.infer(
                "CVE-2021-42392", "desc", "", Arrays.asList("2.0.206"));
        assertFalse(r.hasResult());
    }

    @Test void emptyResultWhenNoFixedVersions() {
        PatchCommitInferer.InferenceResult r = inferer.infer(
                "CVE-2021-42392", "desc", "https://github.com/h2database/h2database",
                Collections.emptyList());
        assertFalse(r.hasResult());
    }

    @Test void groupsByMajorAscending() {
        List<String> result = inferer.groupByMajorAscending(
                Arrays.asList("2.0.206", "1.4.200", "3.5.0"));
        assertEquals(3, result.size());
        assertEquals("1.4.200", result.get(0)); // lowest major first
        assertEquals("2.0.206", result.get(1));
        assertEquals("3.5.0", result.get(2));
    }
}
