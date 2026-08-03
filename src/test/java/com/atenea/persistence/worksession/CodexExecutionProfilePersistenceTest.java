package com.atenea.persistence.worksession;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import jakarta.persistence.Column;
import java.lang.reflect.Field;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CodexExecutionProfilePersistenceTest {

    private final CodexReasoningEffortConverter converter =
            new CodexReasoningEffortConverter();

    @Test
    void mapsOnlyCanonicalReasoningEffortValues() {
        Map<CodexReasoningEffort, String> expected = Map.of(
                CodexReasoningEffort.NONE, "none",
                CodexReasoningEffort.LOW, "low",
                CodexReasoningEffort.MEDIUM, "medium",
                CodexReasoningEffort.HIGH, "high",
                CodexReasoningEffort.XHIGH, "xhigh",
                CodexReasoningEffort.MAX, "max"
        );

        expected.forEach((effort, canonical) -> {
            assertEquals(canonical, converter.convertToDatabaseColumn(effort));
            assertEquals(effort, converter.convertToEntityAttribute(canonical));
        });
        assertNull(converter.convertToDatabaseColumn(null));
        assertNull(converter.convertToEntityAttribute(null));
        assertThrows(
                IllegalArgumentException.class,
                () -> converter.convertToEntityAttribute("ultra")
        );
    }

    @Test
    void agentRunProfileColumnsAreImmutableAfterInsert() throws Exception {
        for (String fieldName : new String[] {
                "codexModelId",
                "codexModelSource",
                "codexReasoningEffort",
                "codexEffortSource",
                "codexCatalogRevision",
                "codexVersion"
        }) {
            Field field = AgentRunEntity.class.getDeclaredField(fieldName);
            Column column = field.getAnnotation(Column.class);
            assertFalse(column.updatable(), fieldName);
        }
    }

    @Test
    void sessionDefaultsAndRunSnapshotRemainIndependent() {
        WorkSessionEntity session = new WorkSessionEntity();
        session.setDefaultCodexReasoningEffort(CodexReasoningEffort.MEDIUM);

        AgentRunEntity run = new AgentRunEntity();
        run.setCodexModelId("gpt-5.6-sol");
        run.setCodexModelSource(ExecutionProfileSource.PROJECT);
        run.setCodexReasoningEffort(session.getDefaultCodexReasoningEffort());
        run.setCodexEffortSource(ExecutionProfileSource.WORK_SESSION);
        run.setCodexCatalogRevision("a".repeat(64));
        run.setCodexVersion("0.145.0");

        session.setDefaultCodexReasoningEffort(CodexReasoningEffort.HIGH);

        assertEquals(CodexReasoningEffort.HIGH, session.getDefaultCodexReasoningEffort());
        assertEquals(CodexReasoningEffort.MEDIUM, run.getCodexReasoningEffort());
        assertEquals(ExecutionProfileSource.WORK_SESSION, run.getCodexEffortSource());
        assertEquals("a".repeat(64), run.getCodexCatalogRevision());
        assertEquals("0.145.0", run.getCodexVersion());
    }
}
