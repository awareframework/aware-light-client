package com.aware.phone.ui.prefs;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class ConsentGroupingTest {

    private static JSONObject sensor(String setting) throws Exception {
        return new JSONObject().put("setting", setting).put("value", true);
    }

    @Test
    public void consentRows_separateSensorsFromPluginsAndModelPluginPermissions() throws Exception {
        JSONArray sensors = new JSONArray()
                .put(sensor("status_accelerometer"))
                .put(sensor("status_plugin_ambient_noise"))
                .put(sensor("status_plugin_openweather"))
                .put(sensor("status_plugin_esm_scheduler"));
        JSONArray config = new JSONArray().put(new JSONObject().put("sensors", sensors));

        List<SensorCollection.ConsentRow> rows =
                SensorCollection.consentRowsForConfig(config);

        boolean enteredPlugins = false;
        SensorCollection.ConsentRow ambient = null;
        SensorCollection.ConsentRow weather = null;
        SensorCollection.ConsentRow esm = null;
        for (SensorCollection.ConsentRow row : rows) {
            if (row.group == SensorCollection.ConsentGroup.PLUGIN) enteredPlugins = true;
            if (enteredPlugins) {
                assertEquals(SensorCollection.ConsentGroup.PLUGIN, row.group);
            }
            if ("Ambient Noise plugin".equals(row.label)) ambient = row;
            if ("OpenWeather plugin".equals(row.label)) weather = row;
            if ("ESM Scheduler plugin".equals(row.label)) esm = row;
        }

        assertTrue(enteredPlugins);
        assertNotNull(ambient);
        assertNotNull(weather);
        assertNotNull(esm);
        assertTrue(ambient.requiresGrant());
        assertTrue(weather.requiresGrant());
        assertFalse(esm.requiresGrant());
    }

    @Test
    public void applicationsConsentControlsInstallationsWithoutDuplicateAutomaticRow() throws Exception {
        JSONArray sensors = new JSONArray()
                .put(sensor("status_applications"))
                .put(sensor("status_installations"));
        JSONArray config = new JSONArray().put(new JSONObject().put("sensors", sensors));

        List<SensorCollection.ConsentRow> rows =
                SensorCollection.consentRowsForConfig(config);

        assertEquals(1, rows.size());
        assertEquals("Applications usage", rows.get(0).label);
        assertTrue(rows.get(0).requiresGrant());
        assertTrue(SensorCollection.controlledSettings(rows.get(0).grantItem)
                .contains("status_installations"));
    }

    @Test
    public void installationsAloneRemainsAutomaticBecauseItNeedsNoAccessibility() throws Exception {
        JSONArray sensors = new JSONArray().put(sensor("status_installations"));
        JSONArray config = new JSONArray().put(new JSONObject().put("sensors", sensors));

        List<SensorCollection.ConsentRow> rows =
                SensorCollection.consentRowsForConfig(config);

        assertEquals(1, rows.size());
        assertEquals("Application installations", rows.get(0).label);
        assertFalse(rows.get(0).requiresGrant());
    }
}
