package com.jvuln.tracer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class TracerEventWriterTest {

    @Test
    void writesJsonlLine(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("trace.jsonl");
        try (TracerEventWriter w = new TracerEventWriter(out.toString(), 5 * 1024 * 1024L)) {
            w.writeEvent(1, 0, "org.h2.Foo", "bar", new String[]{"argA"}, "ret:ok");
        }
        List<String> lines = Files.readAllLines(out);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("\"seq\":1"));
        assertTrue(lines.get(0).contains("\"class\":\"org.h2.Foo\""));
        assertTrue(lines.get(0).contains("\"method\":\"bar\""));
        assertTrue(lines.get(0).contains("argA"));
    }

    @Test
    void stopsWritingAtFileCap(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("trace.jsonl");
        try (TracerEventWriter w = new TracerEventWriter(out.toString(), 100L)) {
            for (int i = 0; i < 20; i++) {
                w.writeEvent(i, 0, "Cls", "m", new String[]{"x"}, "r");
            }
        }
        long size = Files.size(out);
        assertTrue(size <= 200, "Expected file to be capped, got " + size);
    }

    @Test
    void capsArgAt512Chars(@TempDir Path tmp) throws Exception {
        Path out = tmp.resolve("trace.jsonl");
        String longArg = new String(new char[1000]).replace('\0', 'A');
        try (TracerEventWriter w = new TracerEventWriter(out.toString(), 5 * 1024 * 1024L)) {
            w.writeEvent(1, 0, "C", "m", new String[]{longArg}, "r");
        }
        String content = new String(Files.readAllBytes(out));
        assertFalse(content.contains(new String(new char[513]).replace('\0', 'A')), "arg should be capped at 512");
        assertTrue(content.contains(new String(new char[512]).replace('\0', 'A')));
    }
}
