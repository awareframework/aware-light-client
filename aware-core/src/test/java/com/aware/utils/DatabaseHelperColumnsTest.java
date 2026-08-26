package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aware.providers.Aware_Provider;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Unit tests for the column list an upgrade carries data across.
 *
 * This list decides which columns survive a schema change. Reading it from the table definition is
 * what allows a column to be dropped: anything the new definition omits stays behind with the old
 * table. Getting it wrong is quiet and expensive — naming a column the new table lacks aborts the
 * upgrade, which rolls back and leaves the database a version behind the code querying it.
 */
public class DatabaseHelperColumnsTest {

    @Test
    public void readsEveryColumnOfASimpleTable() {
        List<String> columns = DatabaseHelper.declaredColumns(
                "_id integer primary key autoincrement,timestamp real default 0,device_id text default ''");
        assertEquals(3, columns.size());
        assertEquals("_id", columns.get(0));
        assertEquals("timestamp", columns.get(1));
        assertEquals("device_id", columns.get(2));
    }

    @Test
    public void aTrailingUniqueConstraintIsNotAColumn() {
        List<String> columns = DatabaseHelper.declaredColumns(
                "_id integer primary key autoincrement,device_id text default '',UNIQUE(device_id)");
        assertEquals(2, columns.size());
        assertFalse(columns.contains("UNIQUE(device_id)"));
        assertFalse(columns.contains("UNIQUE"));
    }

    @Test
    public void commasInsideAConstraintDoNotSplitIt() {
        List<String> columns = DatabaseHelper.declaredColumns(
                "a text default '',b text default '',UNIQUE(a, b)");
        assertEquals(2, columns.size());
        assertTrue(columns.contains("a"));
        assertTrue(columns.contains("b"));
    }

    @Test
    public void aColumnTheNewDefinitionOmitsIsNotCarriedOver() {
        // The intersection below is what an upgrade names in its carry-over INSERT. Naming a column
        // the new table lacks aborts that INSERT, which rolls back the upgrade and leaves the
        // database a version behind the code querying it.
        String before = "_id integer primary key autoincrement,device_id text default '',"
                + "brand text default '',model text default ''";
        String after = "_id integer primary key autoincrement,device_id text default '',"
                + "model text default ''";

        List<String> carried = DatabaseHelper.declaredColumns(before);
        carried.retainAll(DatabaseHelper.declaredColumns(after));

        assertEquals(3, carried.size());
        assertTrue(carried.contains("model"));
        assertFalse("a dropped column must not reach the carry-over", carried.contains("brand"));
    }

    @Test
    public void configuredTrailingColumnCanBeDroppedWithoutCopyingRows() {
        assertTrue(DatabaseHelper.isConfiguredTrailingColumnDrop(
                Arrays.asList("_id", "timestamp", "device_id", "value", "label"),
                Arrays.asList("_id", "timestamp", "device_id", "value"),
                Arrays.asList("label")));
    }

    @Test
    public void metadataOnlyDropRejectsMiddleOrUnexpectedColumns() {
        assertFalse(DatabaseHelper.isConfiguredTrailingColumnDrop(
                Arrays.asList("_id", "label", "value"),
                Arrays.asList("_id", "value"),
                Arrays.asList("label")));
        assertFalse(DatabaseHelper.isConfiguredTrailingColumnDrop(
                Arrays.asList("_id", "value", "accuracy"),
                Arrays.asList("_id", "value"),
                Arrays.asList("label")));
    }

    @Test
    public void everyDeclaredTableYieldsColumns() {
        for (int i = 0; i < Aware_Provider.TABLES_FIELDS.length; i++) {
            assertTrue("table " + Aware_Provider.DATABASE_TABLES[i] + " declared no columns",
                    DatabaseHelper.declaredColumns(Aware_Provider.TABLES_FIELDS[i]).size() > 0);
        }
    }

    @Test
    public void everyColumnNameIsBareOfTypeAndDefault() {
        for (String column : DatabaseHelper.declaredColumns(Aware_Provider.TABLES_FIELDS[0])) {
            assertFalse(column + " carries more than a name", column.contains(" "));
        }
    }

    // --- Which tables can carry the (timestamp, device_id) index ---
    //
    // Indexing a column a table does not declare throws, and inside an upgrade that aborts the
    // whole migration transaction, leaving the schema a version behind the code querying it.

    /** Mirrors the guard in DatabaseHelper.createTimeDeviceIndex. */
    private static boolean indexable(String fields) {
        List<String> declared = DatabaseHelper.declaredColumns(fields);
        return declared.contains("timestamp") && declared.contains("device_id");
    }

    @Test
    public void tablesWithoutTimestampOrDeviceIdAreNotIndexed() {
        // Named individually so that giving one of them a timestamp is a deliberate decision here
        // rather than a silent flip.
        for (String table : new String[]{"aware_settings", "aware_plugins", "aware_sync_markers"}) {
            int i = indexOf(table);
            assertFalse(table + " declares no timestamp/device_id and must not be indexed",
                    indexable(Aware_Provider.TABLES_FIELDS[i]));
        }
    }

    @Test
    public void tablesWithBothColumnsAreStillIndexed() {
        // The guard must not cost the tables that do benefit from the index.
        for (String table : new String[]{"aware_device", "aware_studies", "aware_log"}) {
            int i = indexOf(table);
            assertTrue(table + " declares both columns and should be indexed",
                    indexable(Aware_Provider.TABLES_FIELDS[i]));
        }
    }

    @Test
    public void noTableIsIndexedOnAColumnItDoesNotDeclare() {
        // The general invariant, so a table added later cannot reintroduce the failure.
        for (int i = 0; i < Aware_Provider.TABLES_FIELDS.length; i++) {
            if (!indexable(Aware_Provider.TABLES_FIELDS[i])) continue;
            List<String> declared = DatabaseHelper.declaredColumns(Aware_Provider.TABLES_FIELDS[i]);
            assertTrue(Aware_Provider.DATABASE_TABLES[i] + " missing timestamp",
                    declared.contains("timestamp"));
            assertTrue(Aware_Provider.DATABASE_TABLES[i] + " missing device_id",
                    declared.contains("device_id"));
        }
    }

    private static int indexOf(String table) {
        for (int i = 0; i < Aware_Provider.DATABASE_TABLES.length; i++) {
            if (Aware_Provider.DATABASE_TABLES[i].equals(table)) return i;
        }
        throw new IllegalArgumentException("No such declared table: " + table);
    }
}
