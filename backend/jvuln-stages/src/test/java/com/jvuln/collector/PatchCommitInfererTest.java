package com.jvuln.collector;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jvuln.llm.LlmClient;
import com.jvuln.llm.PromptRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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

    @Test void matchReturnedShasAcceptsKnownFullSha() throws Exception {
        String fullSha = "0ebf1422abcdef1234567890abcdef1234567890";
        Set<String> knownShas = new LinkedHashSet<>(Arrays.asList(
                fullSha, "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4"));
        JsonNode commitsNode = new ObjectMapper().readTree("[\"" + fullSha + "\"]");

        List<String> urls = inferer.matchReturnedShas(commitsNode, knownShas, "octocat", "repo");

        assertEquals(1, urls.size());
        assertEquals("https://github.com/octocat/repo/commit/" + fullSha, urls.get(0));
    }

    @Test void matchReturnedShasResolvesPrefixToFullSha() throws Exception {
        String fullSha = "0ebf1422abcdef1234567890abcdef1234567890";
        Set<String> knownShas = new LinkedHashSet<>(Arrays.asList(
                fullSha, "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4"));
        JsonNode commitsNode = new ObjectMapper().readTree("[\"0ebf142\"]");

        List<String> urls = inferer.matchReturnedShas(commitsNode, knownShas, "octocat", "repo");

        assertEquals(1, urls.size());
        // URL must use the canonical FULL sha, not the 7-char prefix the LLM returned
        assertEquals("https://github.com/octocat/repo/commit/" + fullSha, urls.get(0));
    }

    @Test void matchReturnedShasSkipsHallucinatedSha() throws Exception {
        Set<String> knownShas = new LinkedHashSet<>(Arrays.asList(
                "0ebf1422abcdef1234567890abcdef1234567890",
                "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4"));
        // A plausible-looking but fabricated 40-char sha absent from the log
        JsonNode commitsNode = new ObjectMapper().readTree(
                "[\"deadbeef00112233445566778899aabbccddeeff\"]");

        List<String> urls = inferer.matchReturnedShas(commitsNode, knownShas, "octocat", "repo");

        assertEquals(0, urls.size());
    }

    @Test void matchReturnedShasDeduplicates() throws Exception {
        String fullSha = "0ebf1422abcdef1234567890abcdef1234567890";
        Set<String> knownShas = new LinkedHashSet<>(Collections.singletonList(fullSha));
        // Same commit returned twice (full sha + its prefix)
        JsonNode commitsNode = new ObjectMapper().readTree(
                "[\"" + fullSha + "\", \"0ebf142\"]");

        List<String> urls = inferer.matchReturnedShas(commitsNode, knownShas, "octocat", "repo");

        assertEquals(1, urls.size());
        assertEquals("https://github.com/octocat/repo/commit/" + fullSha, urls.get(0));
    }
}
