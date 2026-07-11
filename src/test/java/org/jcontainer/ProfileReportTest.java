package org.jcontainer;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProfileReportTest {

    @Test
    void testRenderIncludesExpectedSummaryFields() {
        ProfileReport report = new ProfileReport(
                Path.of("/tmp/profile/trace.410"),
                2,
                7,
                3
        );

        assertEquals("""
                Profile report:
                  Root trace: /tmp/profile/trace.410
                  Descendant traces: 2
                  Syscalls: 7
                  Discarded lines: 3
                """, report.render());
    }
}
