package com.aware.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/**
 * Unit tests for reading a study's dataflow out of its configuration, and for the
 * schema check that depends on it.
 *
 * A study whose data goes through the webservice ships no database block at all,
 * deliberately: the phone never contacts MySQL on that path, and the config is
 * served from a public URL, so an address and an account there would be a
 * credential handed to every participant for nothing. That makes "database" a
 * requirement of the direct path rather than of every config — and getting this
 * wrong is not subtle, it rejects the config outright and the participant cannot
 * join at all.
 *
 * The inferred case matters as much as the declared one. The explicit `dataflow`
 * field is new, so every config written before it — including the copy every
 * already-enrolled phone holds — has to be read from `status_webservice` instead.
 */
public class StudyDataflowTest {

    private static JSONObject settings(String setting, String value) throws JSONException {
        JSONObject entry = new JSONObject();
        entry.put("setting", setting);
        entry.put("value", value);
        return entry;
    }

    /** A webservice config: no database block, a study URL, the channel on. */
    private static JSONObject webserviceConfig() throws JSONException {
        JSONObject config = new JSONObject();
        for (String key : new String[]{"questions", "schedules", "study_info"}) {
            config.put(key, new JSONObject());
        }
        config.put("dataflow", "webservice");
        config.put("sensors", new JSONArray()
                .put(settings("status_webservice", "true"))
                .put(settings("webservice_server", "https://study.example.org/1/KEY")));
        return config;
    }

    private static JSONObject directConfig() throws JSONException {
        JSONObject config = new JSONObject();
        for (String key : new String[]{"questions", "schedules", "study_info"}) {
            config.put(key, new JSONObject());
        }
        JSONObject db = new JSONObject();
        db.put("database_host", "db.example.org");
        db.put("database_port", "3306");
        db.put("database_name", "study");
        db.put("database_username", "participant");
        config.put("database", db);
        config.put("dataflow", "direct");
        config.put("sensors", new JSONArray().put(settings("status_webservice", "false")));
        return config;
    }

    @Test
    public void readsTheDeclaredDataflow() throws JSONException {
        assertTrue(StudyUtils.usesWebservice(webserviceConfig()));
        assertFalse(StudyUtils.usesWebservice(directConfig()));
    }

    @Test
    public void fallsBackToTheChannelSettingWhenNoFieldIsDeclared() throws JSONException {
        JSONObject config = webserviceConfig();
        config.remove("dataflow");

        assertTrue("a config predating the field is every enrolled phone's copy",
                StudyUtils.usesWebservice(config));
    }

    @Test
    public void theDeclaredFieldWinsOverTheChannelSetting() throws JSONException {
        JSONObject config = directConfig();
        config.put("sensors", new JSONArray().put(settings("status_webservice", "true")));

        assertFalse(StudyUtils.usesWebservice(config));
    }

    @Test
    public void aConfigWithNeitherSignalReadsAsDirect() throws JSONException {
        JSONObject config = new JSONObject();
        config.put("sensors", new JSONArray());

        assertFalse(StudyUtils.usesWebservice(config));
    }

    @Test
    public void aNullConfigReadsAsDirect() {
        assertFalse(StudyUtils.usesWebservice(null));
    }

    @Test
    public void aWebserviceConfigNeedsNoDatabaseBlock() throws JSONException {
        assertNull("a credential-less config must be joinable",
                StudyUtils.firstMissingRequirement(webserviceConfig()));
    }

    @Test
    public void aWebserviceConfigStillNeedsItsStudyUrl() throws JSONException {
        JSONObject config = webserviceConfig();
        config.put("sensors", new JSONArray().put(settings("status_webservice", "true")));

        assertEquals("webservice_server", StudyUtils.firstMissingRequirement(config));
    }

    @Test
    public void aDirectConfigStillNeedsItsDatabaseBlock() throws JSONException {
        JSONObject config = directConfig();
        config.remove("database");

        assertEquals("database", StudyUtils.firstMissingRequirement(config));
    }

    @Test
    public void aDirectConfigStillNeedsEveryDatabaseField() throws JSONException {
        JSONObject config = directConfig();
        config.getJSONObject("database").put("database_host", "");

        assertEquals("database_host", StudyUtils.firstMissingRequirement(config));
    }

    @Test
    public void readsANamedSettingOutOfTheSensorsList() throws JSONException {
        assertEquals("https://study.example.org/1/KEY",
                StudyUtils.settingValue(webserviceConfig(), "webservice_server"));
    }

    @Test
    public void anAbsentSettingReadsAsEmptyRatherThanNull() throws JSONException {
        assertEquals("", StudyUtils.settingValue(webserviceConfig(), "not_a_setting"));
        assertEquals("", StudyUtils.settingValue(null, "status_webservice"));
    }
}
