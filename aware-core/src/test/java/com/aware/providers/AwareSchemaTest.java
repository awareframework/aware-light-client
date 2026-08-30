package com.aware.providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.aware.providers.Aware_Provider.Aware_Device;
import com.aware.providers.Aware_Provider.Aware_Log;
import com.aware.providers.Aware_Provider.Aware_Studies;
import com.aware.providers.Aware_Provider.Aware_Sync_Markers;
import com.aware.utils.DeviceFacts;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * Ties the column names the framework writes to the schema it creates.
 *
 * These are the mismatches that stay quiet until they cost data: a column compared but absent reads
 * as changed on every check, and a column written but absent from the remote table fails the whole
 * upload batch for that table. Both are plain string arrays, so both are checkable here.
 */
public class AwareSchemaTest {

    private static final List<String> TABLES = Arrays.asList(Aware_Provider.DATABASE_TABLES);

    private static String schemaOf(String table) {
        return Aware_Provider.TABLES_FIELDS[TABLES.indexOf(table)];
    }

    @Test
    public void everyComparedDeviceColumnExistsInTheDeviceTable() {
        // A compared column the table lacks is read back as null on every check, so the stored row
        // never matches the device and gets rewritten on every service start — each rewrite reaching
        // the server as a device change that did not happen.
        String schema = schemaOf("aware_device");
        for (String column : DeviceFacts.COMPARED_COLUMNS) {
            assertTrue(column + " is compared but missing from aware_device",
                    schema.contains(column + " text"));
        }
    }

    @Test
    public void theDeviceTableCarriesNoUnsupportedHardwareColumns() {
        // Each of these either stopped being reported by Android, or repeated what another column
        // already said. A column reinstated here reaches the research database as a column the remote
        // table does not have, which fails the whole upload batch for aware_device.
        String schema = schemaOf("aware_device");
        for (String column : new String[]{"brand", "serial", "release_type"}) {
            assertFalse(column + " is declared in aware_device again",
                    schema.contains(column + " text"));
        }
    }

    @Test
    public void theDeviceTableCarriesTheParticipantFacingLabel() {
        assertTrue(schemaOf("aware_device").contains(Aware_Device.LABEL + " text"));
    }

    @Test
    public void theDeviceTableHoldsOneRowPerDevice() {
        // get_device_info relies on this to update in place rather than accumulate rows.
        assertTrue(schemaOf("aware_device").contains("UNIQUE(" + Aware_Device.DEVICE_ID + ")"));
    }

    @Test
    public void theLogCarriesItsType() {
        assertTrue(schemaOf("aware_log").contains(Aware_Log.LOG_TYPE + " text"));
    }

    @Test
    public void studiesCarryTheirLastUpdate() {
        assertTrue(schemaOf("aware_studies").contains(Aware_Studies.STUDY_UPDATED + " real"));
    }

    @Test
    public void thereIsOneSyncMarkerPerTable() {
        // The marker store answers "how far did this table get" with a single row, so writing a
        // marker has to supersede that table's previous one rather than pile up beside it.
        assertTrue(schemaOf("aware_sync_markers")
                .contains("UNIQUE(" + Aware_Sync_Markers.MARKER_TABLE + ")"));
    }

    @Test
    public void aSyncMarkerCarriesTheRowItStoppedAt() {
        // The cursor an upload resumes from is a row id: it is distinct per row and assigned in
        // insertion order, so a batch resumes on the row after the last one the server took.
        assertTrue(schemaOf("aware_sync_markers")
                .contains(Aware_Sync_Markers.MARKER_LAST_ID + " integer"));
    }

    @Test
    public void everyDeclaredTableHasASchema() {
        assertTrue(Aware_Provider.DATABASE_TABLES.length == Aware_Provider.TABLES_FIELDS.length);
    }

    @Test
    public void theMarkerTableIsNotOneOfTheUploadedThree() {
        // Aware_Sync uploads indices 0, 3 and 4. Markers are the phone's own bookkeeping and adding
        // them to that set would send them to the server and subject them to its retention.
        int markers = TABLES.indexOf("aware_sync_markers");
        assertTrue(markers != 0 && markers != 3 && markers != 4);
    }
}
