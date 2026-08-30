package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.sql.SQLWarning;

/**
 * Unit tests for the summary of SQL warnings raised by an upload batch.
 *
 * Every value is sent to MySQL as a string, including numeric and {@code double_*} columns, so the
 * server converts each one implicitly. A server in strict mode raises an error on a bad conversion,
 * but a non-strict server — or a MyISAM table, where strict mode degrades to warnings for multi-row
 * inserts — accepts it as a warning and stores something other than what was sent. A discarded
 * warning is therefore the difference between "1000 rows stored" and "1000 rows stored as
 * something else", which is why the chain is read and reported.
 */
public class JdbcWarningsTest {

    @Test
    public void noWarningChainIsNotReported() {
        // The overwhelmingly common case: nothing should be logged on a clean batch.
        assertNull(Jdbc.describeWarnings(null));
    }

    @Test
    public void aSingleWarningReportsItsStateCodeAndMessage() {
        SQLWarning warning = new SQLWarning("Data truncated for column 'double_values_0'", "01000", 1265);

        String summary = Jdbc.describeWarnings(warning);

        assertNotNull(summary);
        assertTrue(summary, summary.contains("1 warning(s)"));
        assertTrue(summary, summary.contains("01000"));
        assertTrue(summary, summary.contains("1265"));
        assertTrue(summary, summary.contains("Data truncated for column 'double_values_0'"));
    }

    @Test
    public void theWholeChainIsCounted() {
        // MySQL reports one warning per offending row, so the count separates a single odd value
        // from a column whose type no longer matches what the client sends.
        SQLWarning head = new SQLWarning("first", "01000", 1265);
        head.setNextWarning(new SQLWarning("second", "01000", 1265));
        head.setNextWarning(new SQLWarning("third", "01000", 1265));

        String summary = Jdbc.describeWarnings(head);

        assertTrue(summary, summary.contains("3 warning(s)"));
    }

    @Test
    public void theFirstWarningIsTheOneQuoted() {
        SQLWarning head = new SQLWarning("the first one", "01004", 1264);
        head.setNextWarning(new SQLWarning("a later one", "01000", 1265));

        String summary = Jdbc.describeWarnings(head);

        assertTrue(summary, summary.contains("the first one"));
        assertTrue(summary, summary.contains("01004"));
    }

    @Test
    public void aSelfReferencingChainTerminates() {
        // A driver chaining a warning to itself must not hang the sync thread. There is no correct
        // count to assert here, only that the call returns.
        SQLWarning selfReferencing = new SQLWarning("loops", "01000", 1265) {
            @Override
            public SQLWarning getNextWarning() {
                return this;
            }
        };

        String summary = Jdbc.describeWarnings(selfReferencing);

        assertNotNull(summary);
        assertTrue(summary, summary.contains("loops"));
    }

    @Test
    public void aChainLongerThanTheCapStillReportsTheFirstWarning() {
        SQLWarning head = new SQLWarning("head", "01000", 1265);
        SQLWarning tail = head;
        for (int i = 0; i < 1500; i++) {
            SQLWarning next = new SQLWarning("row " + i, "01000", 1265);
            tail.setNextWarning(next);
            tail = next;
        }

        String summary = Jdbc.describeWarnings(head);

        assertTrue(summary, summary.contains("head"));
        // Counting is bounded, so the reported number saturates rather than walking 1501 links.
        assertEquals("1000 warning(s); first: [01000/1265] head", summary);
    }
}
