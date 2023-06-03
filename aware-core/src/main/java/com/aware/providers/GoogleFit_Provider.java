package com.aware.providers;

import android.net.Uri;
import android.provider.BaseColumns;

import com.aware.Aware_Preferences;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.HashMap;

public class GoogleFit_Provider {

    // query paths
    public static final int STEP = 1;
    public static final int CALORIE = 2;

    public static final Map<String, TimeUnit> GRANULARITY_DICT = new HashMap<String, TimeUnit>(){{
        put("day", TimeUnit.DAYS);
        put("hour", TimeUnit.HOURS);
        put("minute", TimeUnit.MINUTES);
    }};;

    public static final Map<String, Integer> DATA_TYPE_DICT = new HashMap<String, Integer>(){{
        put(Aware_Preferences.GF_STEP, 1);
        put(Aware_Preferences.GF_DISTANCE, 2);
        put(Aware_Preferences.GF_SEGMENT, 3);
        put(Aware_Preferences.GF_SPEED, 4);
        put(Aware_Preferences.GF_CALORIE, 5);
        put(Aware_Preferences.GF_HEART_RATE, 6);
        put(Aware_Preferences.GF_WEIGHT, 7);
        put(Aware_Preferences.GF_BODY_FAT_PERCENTAGE, 8);
        put(Aware_Preferences.GF_HYDRATION, 9);
        put(Aware_Preferences.GF_NUTRITION, 10);
        put(Aware_Preferences.GF_POWER, 11);
        put(Aware_Preferences.GF_BMR, 12);
    }};;


    public static final class StepData implements BaseColumns {
        private StepData() {
        }

        public static final String DATABASE_TABLE = "googlefit_steps";

        public static final String _ID = "_id";
        public static final String DEVICE_ID = "device_id";
        public static final String START_TIMESTAMP = "start_timestamp";
        public static final String END_TIMESTAMP = "ebd_timestamp";
        public static final String STEP_COUNT = "step_count";

    }
}
