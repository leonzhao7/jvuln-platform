package com.jvuln.generator.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataExtractorTest {

    @Test
    void trimIntelligenceKeepsOnlyReportFields() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DataExtractor extractor = new DataExtractor(mapper);

        Map<String, Object> data = new HashMap<>();
        data.put("cveId", "CVE-2099-0001");
        data.put("cweId", "CWE-502");
        data.put("description", "demo");
        data.put("cvss", 9.8);
        data.put("fixedVersion", "1.2.3");
        data.put("artifact", "g:a");
        data.put("affectedVersions", "< 1.2.3");
        data.put("rawReferences", "should be dropped");

        String json = extractor.trimIntelligence(data);

        assertTrue(json.contains("CVE-2099-0001"));
        assertTrue(json.contains("CWE-502"));
        assertFalse(json.contains("rawReferences"));

        @SuppressWarnings("unchecked")
        Map<String, Object> parsed = mapper.readValue(json, Map.class);
        assertEquals("g:a", parsed.get("artifact"));
        assertFalse(parsed.containsKey("rawReferences"));
    }

    @Test
    void trimIntelligenceHandlesNull() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        DataExtractor extractor = new DataExtractor(mapper);
        String json = extractor.trimIntelligence(null);
        assertEquals("{}", json.replaceAll("\\s", ""));
    }
}
