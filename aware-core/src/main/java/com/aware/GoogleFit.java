package com.aware;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.aware.providers.Aware_Provider;
import com.aware.providers.GoogleFit_Provider;
import com.aware.utils.Jdbc;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.common.Scopes;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.FitnessOptions;
import com.google.android.gms.fitness.data.Bucket;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResponse;
import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;


public class GoogleFit extends AppCompatActivity {

    public static final String TAG = "GoogleFit";
    public static final int GOOGLE_FIT_PERMISSIONS_REQUEST_CODE = 347;
    public static final String CLIENT_ID = "778253109088-ctvtl5bro65fods0f6ji05vsip6e8bf9.apps.googleusercontent.com";
    public static Scope scopesActivityRead = new Scope(Scopes.FITNESS_ACTIVITY_READ);
    public static Scope scopesNutritionRead = new Scope(Scopes.FITNESS_NUTRITION_READ);
    public static Scope scopesBodyRead = new Scope(Scopes.FITNESS_BODY_READ);
    public static Scope scopesBodyWrite = new Scope(Scopes.FITNESS_BODY_READ_WRITE);
    public static Scope scopesLocationRead = new Scope(Scopes.FITNESS_LOCATION_READ);
    private TextView textView;


    private FitnessOptions fitnessOptions = FitnessOptions.builder()
            .addDataType(DataType.TYPE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_STEP_COUNT_DELTA, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_DISTANCE_DELTA, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_DISTANCE_DELTA, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_ACTIVITY_SEGMENT, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_ACTIVITY_SUMMARY, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_SPEED, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_SPEED_SUMMARY, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_CALORIES_EXPENDED, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_CALORIES_EXPENDED, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_HEART_RATE_BPM, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_HEART_RATE_SUMMARY, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_WEIGHT, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_WEIGHT_SUMMARY, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_BODY_FAT_PERCENTAGE, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_BODY_FAT_PERCENTAGE_SUMMARY, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_HYDRATION, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_HYDRATION, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_NUTRITION, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_NUTRITION_SUMMARY, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_POWER_SAMPLE, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_POWER_SUMMARY, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.TYPE_BASAL_METABOLIC_RATE, FitnessOptions.ACCESS_READ)
            .addDataType(DataType.AGGREGATE_BASAL_METABOLIC_RATE_SUMMARY, FitnessOptions.ACCESS_READ)
            .build();

    public static final int GIVENDAY = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d("TAG","GoogleFit Started...");
        setContentView(R.layout.google_fit);
        textView = findViewById(R.id.google_fit_notification);
        fitSignIn();
    }

    private String getStartTimeString(DataPoint dataPoint) {
        long timestamp = dataPoint.getStartTime(TimeUnit.MILLISECONDS);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        return dateFormat.format(new Date(timestamp));
    }

    private String getEndTimeString(DataPoint dataPoint) {
        long timestamp = dataPoint.getEndTime(TimeUnit.MILLISECONDS);
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm");
        return dateFormat.format(new Date(timestamp));
    }

    private boolean oAuthPermissionsApproved() {
        return GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(this), fitnessOptions);
    }

    /**
     * Gets a Google account for use in creating the Fitness client. This is achieved by either
     * using the last signed-in account, or if necessary, prompting the user to sign in.
     * `getAccountForExtension` is recommended over `getLastSignedInAccount` as the latter can
     * return `null` if there has been no sign in before.
     */
    private GoogleSignInAccount getGoogleAccount() {
        return GoogleSignIn.getAccountForExtension(this, fitnessOptions);
    }


    private ArrayList<Integer> requiredDataTypes(){
        Set<String> dataTypes = GoogleFit_Provider.DATA_TYPE_DICT.keySet();
        ArrayList<Integer> requiredType = new ArrayList<>();
        for (String dataType: dataTypes){
            if (Boolean.parseBoolean(Aware.getSetting(getApplicationContext(), dataType))){
                int type_int = GoogleFit_Provider.DATA_TYPE_DICT.get(dataType);
                requiredType.add(type_int);
            }
        }
        return requiredType;
    }


    private void retrieveData(){
        int prestudyRetrievePeriod = Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.GF_PRESTUDY_RETRIEVE_PEERIOD));

        ArrayList<Integer> dataTypes = requiredDataTypes();

        // pre study data
        if (prestudyRetrievePeriod > 0){
            for (int dataType: dataTypes){
                readPreStudyData(dataType);
            }

        }

        // data within study
        int retrievePeriod = Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.GF_RETRIEVAL_PERIOD));
        if (retrievePeriod > 0) {
            for (int d: dataTypes){
                readInStudyData(d);
            }
        }

        onDataCollectionStopped();
    }


    public void onDataCollectionStopped() {
        // Update the content of the TextView here
        textView.setText("Data collection completed.");
        textView.setGravity(android.view.Gravity.CENTER);

    }

    private Boolean permissionPassed(Set<Scope> grantedScopes){
        return grantedScopes.contains(scopesActivityRead) && grantedScopes.contains(scopesNutritionRead) && grantedScopes.contains(scopesBodyRead) && grantedScopes.contains(scopesLocationRead);
    }

    private void fitSignIn() {
        GoogleSignInAccount account = getGoogleAccount();
        if (account != null) {
            Set<Scope> grantedScopes = account.getGrantedScopes();
            Log.i("GOOGLEFIT", String.valueOf(grantedScopes));
            if (oAuthPermissionsApproved() && permissionPassed(grantedScopes)) {
                // Read history data
                retrieveData();
            } else{
                // Check general scopes
                if (!permissionPassed(grantedScopes)) {
                    Log.i(TAG, "Asking permission from user:" + scopesActivityRead.toString());
                    getGoogleAccount().requestExtraScopes(scopesActivityRead);
                    GoogleSignIn.requestPermissions(
                            this,  // your activity
                            GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                            getGoogleAccount(),
                            scopesActivityRead, scopesNutritionRead, scopesBodyRead, scopesLocationRead, scopesBodyWrite
                    );
                }
                // Check specific data type permission
                if (!oAuthPermissionsApproved()) {
                    Log.i(TAG, "Asking permission from user:" + fitnessOptions.toString());
                    GoogleSignIn.requestPermissions(
                            this,
                            GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                            getGoogleAccount(),
                            fitnessOptions
                    );
                }
            }
        } else {
            Log.e(TAG, "The google account retrieved to be null...");
        }
    }




    /**
     * Handles the callback from the OAuth sign in flow, executing the post sign in function
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GOOGLE_FIT_PERMISSIONS_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                Set<Scope> grantedScopes = getGoogleAccount().getGrantedScopes();
                Log.i("GOOGLEFIT", String.valueOf(grantedScopes));
                if (oAuthPermissionsApproved() && permissionPassed(grantedScopes)) {
                    retrieveData();
                } else {
                    // Check general scopes
                    if (!permissionPassed(grantedScopes)) {
                        Log.i(TAG, "Asking permission from user:" + scopesActivityRead.toString());
                        getGoogleAccount().requestExtraScopes(scopesActivityRead);
                        GoogleSignIn.requestPermissions(
                                this,  // your activity
                                GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                                getGoogleAccount(),
                                scopesActivityRead, scopesBodyRead, scopesNutritionRead, scopesLocationRead, scopesBodyWrite
                        );
                    }
                    // Check specific data type permission
                    if (!oAuthPermissionsApproved()) {
                        Log.i(TAG, "Asking permission from user:" + fitnessOptions.toString());
                        GoogleSignIn.requestPermissions(
                                this,
                                GOOGLE_FIT_PERMISSIONS_REQUEST_CODE,
                                getGoogleAccount(),
                                fitnessOptions
                        );
                    }
                }

            } else if (resultCode == Activity.RESULT_CANCELED) {
                // The user cancelled the login dialog before selecting any action.
                Log.e(TAG, "User cancelled the dialog");
            } else {
                Log.e(TAG, "Authorisation failed, result code "+ resultCode);
            }
        }
    }

    /**
     * Retrieve the pre study data according to a given number of days
     * The retriving period is the number of days prior to the sign-up date
     */
    private Task<DataReadResponse> readPreStudyData(int data_type) {
        int prestudyRetrievePeriod = Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.GF_PRESTUDY_RETRIEVE_PEERIOD));
        Log.d("TTTTT", String.valueOf(prestudyRetrievePeriod));

        // Begin by creating the query.
        DataReadRequest readRequest = queryPreStudyData(prestudyRetrievePeriod, data_type);


        // Invoke the History API to fetch the data with the query
        MyOnSuccessListener listener = new MyOnSuccessListener(this, data_type);
        return Fitness.getHistoryClient(this, getGoogleAccount())
                .readData(readRequest)
                .addOnSuccessListener(listener)
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        Log.e(TAG, "There was a problem reading the data.", e);
                        Log.i(TAG, e.toString());
                    }
                });
    }

    /**
     * Retrieve the google fit data during the study according to a given number of days
     * The retriving period is the number of days after the sign-up date
     */
    private Task<DataReadResponse> readInStudyData(int data_type) {
        int retrievePeriod = Integer.parseInt(Aware.getSetting(getApplicationContext(), Aware_Preferences.GF_RETRIEVAL_PERIOD));

        // Begin by creating the query.
        DataReadRequest readRequest = queryInStudyData(retrievePeriod, data_type);


        // Invoke the History API to fetch the data with the query
        MyOnSuccessListener listener = new MyOnSuccessListener(this, data_type);
        return Fitness.getHistoryClient(this, getGoogleAccount())
                .readData(readRequest)
                .addOnSuccessListener(listener)
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        Log.e(TAG, "There was a problem reading the data.", e);
                        Log.i(TAG, e.toString());
                    }
                });
    }

    public class MyOnSuccessListener implements OnSuccessListener<DataReadResponse> {
        private Context mContext;
        public int type;

        public MyOnSuccessListener(Context context, int data_type) {
            mContext = context;
            type = data_type;
        }

        @Override
        public void onSuccess(DataReadResponse dataReadResponse) {
            processData(dataReadResponse, type);
        }
    }

    private DataReadRequest prepareRequest(int data_type, String granularity, long startTime, long endTime){
        // step counts
        if (data_type == 1){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_STEP_COUNT_DELTA, DataType.AGGREGATE_STEP_COUNT_DELTA)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // distance
        if (data_type == 2){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_DISTANCE_DELTA, DataType.AGGREGATE_DISTANCE_DELTA)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // activity segment
        if (data_type == 3){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_ACTIVITY_SEGMENT, DataType.AGGREGATE_ACTIVITY_SUMMARY)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // speed
        if (data_type == 4){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_SPEED, DataType.AGGREGATE_SPEED_SUMMARY)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // calorie
        if (data_type == 5){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_CALORIES_EXPENDED, DataType.AGGREGATE_CALORIES_EXPENDED)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // heart rate
        if (data_type == 6){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_HEART_RATE_BPM, DataType.AGGREGATE_HEART_RATE_SUMMARY)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // weight
        if (data_type == 7){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_WEIGHT, DataType.AGGREGATE_WEIGHT_SUMMARY)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // body fat
        if (data_type == 8){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_BODY_FAT_PERCENTAGE, DataType.AGGREGATE_BODY_FAT_PERCENTAGE_SUMMARY)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // hydration
        if (data_type == 9){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_HYDRATION, DataType.AGGREGATE_HYDRATION)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // nutrition
        if (data_type == 10){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_NUTRITION, DataType.AGGREGATE_NUTRITION_SUMMARY)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // power
        if (data_type == 11){
            return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_POWER_SAMPLE, DataType.AGGREGATE_POWER_SUMMARY)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
        }

        // BMR
        return new DataReadRequest.Builder()
                    .aggregate(DataType.TYPE_BASAL_METABOLIC_RATE, DataType.AGGREGATE_BASAL_METABOLIC_RATE_SUMMARY)
                    .bucketByTime(1, GoogleFit_Provider.GRANULARITY_DICT.get(granularity))
                    .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                    .build();
    }


    private DataReadRequest queryPreStudyData(int preStudyRetrievePeriod, int data_type) {
        Long t = null;
        // time of signing up
        Cursor study = Aware.getStudy(getApplicationContext(), Aware.getSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER));


        if (study != null && study.moveToFirst()) {
             t = study.getLong(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED));
            study.close();
        }


        if (t == null) {
            return null;
        }
        Date signupDay = new Date(t);

        // N Days before signing up
        Date preRetrieveDay = getDateNDaysBefore(signupDay, preStudyRetrievePeriod);

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getDefault());
        calendar.setTime(signupDay);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        long endTime = calendar.getTimeInMillis();

        calendar.setTime(preRetrieveDay);
        calendar.add(Calendar.DAY_OF_YEAR, 1); // Add one day to include the end date
        long startTime = calendar.getTimeInMillis();

        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE MMM dd - HH:mm", Locale.getDefault());
        dateFormat.setTimeZone(TimeZone.getDefault());

        Log.i(TAG, "Range Start: " + dateFormat.format(startTime));
        Log.i(TAG, "Range End: " + dateFormat.format(endTime));

        String granularity = Aware.getSetting(getApplicationContext(), Aware_Preferences.GF_GRANULARITY);

        return prepareRequest(data_type, granularity, startTime, endTime);
    }




    private DataReadRequest queryInStudyData(int retrievePeriod, int data_type) {
        // time of signing up
        Long t = null;
        Cursor study = Aware.getStudy(getApplicationContext(), Aware.getSetting(getApplicationContext(), Aware_Preferences.WEBSERVICE_SERVER));
        if (study != null && study.moveToFirst()) {
            t = study.getLong(study.getColumnIndex(Aware_Provider.Aware_Studies.STUDY_JOINED));
            study.close();
        }
        if (t == null) {
            return null;
        }

        Date signupDay = new Date(t);

        // Calculate the retrieve day (days before signupDay)
        Date retrieveDay = getDateNDaysAfter(signupDay, retrievePeriod);

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getDefault());
        calendar.setTime(signupDay);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        long startTime = calendar.getTimeInMillis();

        calendar.setTime(retrieveDay);
        calendar.add(Calendar.DAY_OF_YEAR, 1); // Add one day to include the end date
        long endTime = calendar.getTimeInMillis();

        SimpleDateFormat dateFormat = new SimpleDateFormat("EEE MMM dd - HH:mm", Locale.getDefault());
        dateFormat.setTimeZone(TimeZone.getDefault());

        Log.i(TAG, "Range Start: " + dateFormat.format(startTime));
        Log.i(TAG, "Range End: " + dateFormat.format(endTime));

        String granularity = Aware.getSetting(getApplicationContext(), Aware_Preferences.GF_GRANULARITY);
        return prepareRequest(data_type, granularity, startTime, endTime);
    }


    public static Date getDateNDaysBefore(Date d, int n) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(d);
        calendar.add(Calendar.DAY_OF_YEAR, -n);
        return calendar.getTime();
    }

    public static Date getDateNDaysAfter(Date d, int n) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(d);
        calendar.add(Calendar.DAY_OF_YEAR, n);
        return calendar.getTime();
    }

    private int calculateNumberOfDays(Date startDate) {
        Calendar today = Calendar.getInstance();
        today.setTimeZone(TimeZone.getDefault());
        today.set(Calendar.HOUR_OF_DAY, 0);
        today.set(Calendar.MINUTE, 0);
        today.set(Calendar.SECOND, 0);
        long endTime = today.getTimeInMillis(); // End time is today (exclusive)

        Calendar calendar = Calendar.getInstance();
        calendar.setTimeZone(TimeZone.getDefault());
        calendar.setTime(startDate); // Set the calendar to the start date
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        long startTime = calendar.getTimeInMillis(); // Start time is the start date (inclusive)

        long diffMillis = endTime - startTime;
        return (int) TimeUnit.MILLISECONDS.toDays(diffMillis);
    }


    /**
     * Collect the aggregated data from data buckets
     */
    private void processData(DataReadResponse dataReadResult, int data_type) {
        // alternate: use dataReadResult.getDataSets().isEmpty()
        if (!dataReadResult.getBuckets().isEmpty()) {
            Log.i(TAG, "Number of returned buckets of DataSets is: " + dataReadResult.getBuckets().size());
            for (Bucket bucket : dataReadResult.getBuckets()) {
                for (DataSet dataSet : bucket.getDataSets()) {
                    try {
                        dumpDataSet(dataSet, data_type);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private void dumpDataSet(DataSet dataSet, int dataType) throws JSONException {
        Log.i(TAG, "Data returned for Data type: " + dataSet.getDataType().getName());
        Log.i(TAG, String.valueOf(dataSet));

        JSONArray rows = new JSONArray();
        if (!dataSet.isEmpty()){
            for (DataPoint dp : dataSet.getDataPoints()) {
                if (dp.getDataType() != null){
                    // Logs
                    Log.d(TAG, dp.toString());
                    Log.d(TAG, "Data point:");
                    Log.d(TAG, "\tType: " + dp.getDataType().getName());
                    Log.d(TAG, "\tStart: " + getStartTimeString(dp));
                    Log.d(TAG, "\tEnd: " + getEndTimeString(dp));


                    // Collect rows
                    JSONObject jsonObject = new JSONObject();
                    // 1): device_id
                    jsonObject.put("device_id", Aware.getSetting(getApplicationContext(), Aware_Preferences.DEVICE_ID));
                    // 2) + 3): start & end timestamp
                    jsonObject.put("start_timestamp",dp.getStartTime(TimeUnit.MILLISECONDS));
                    jsonObject.put("end_timestamp",dp.getEndTime(TimeUnit.MILLISECONDS));
                    // Inserting structure of one row
                    jsonObject = collectDataField(dp, dataType, jsonObject);

                    rows.put(jsonObject);
                }
            }
        }
        Log.d(TAG, "WRITE DATA NOW");

        // Create a new instance of the InsertDataAsyncTask class
        InsertDataAsyncTask task = new InsertDataAsyncTask(); // TODO: dynamic table name

        // Call the execute() method to start the task
        task.execute(rows, dataType);
    }


    public JSONObject collectDataField(DataPoint dp, int data_type, JSONObject jsonObject){
        if (data_type == 1) return collectRow(dp, jsonObject, new String[]{"steps"}); // step
        if (data_type == 2) return collectRow(dp, jsonObject, new String[]{"distance"}); // distance
        if (data_type == 3) return collectRow(dp, jsonObject, new String[]{"activity"}); // activity: refer to https://developers.google.com/fit/rest/v1/reference/activity-types
        if (data_type == 4) return collectRow(dp, jsonObject, new String[]{"average", "max", "min"}); // speed
        if (data_type == 5) return collectRow(dp, jsonObject, new String[]{"calories"}); // calorie
        if (data_type == 6) return collectRow(dp, jsonObject, new String[]{"min", "max"}); // heart rate Todo: Test
        if (data_type == 7) return collectRow(dp, jsonObject, new String[]{"min", "max"}); //weight
        if (data_type == 8) return collectRow(dp, jsonObject, new String[]{"min", "max"}); // body fat
        if (data_type == 9) return collectRow(dp, jsonObject, new String[]{"volume"}); // hydration
        if (data_type == 10) return collectRowNutrients(dp, jsonObject, new String[]{"nutrients", "meal_type"}); //nutrition 0: Unknown 1: Breakfast 2: Lunch 3: Dinner 4: Snack
        if (data_type == 11) return collectRow(dp, jsonObject, new String[]{"max","min"}); // power
        return collectRow(dp, jsonObject, new String[]{"max","min"}); // bmr
    }

    public JSONObject collectRowNutrients(DataPoint dp, JSONObject jsonObject, String[] fieldNames){
        try {
            // 4) and beyond: field values
            for (Field field : dp.getDataType().getFields()) {
                Log.i(TAG, "\tField: " + field.getName() + " Value: " + dp.getValue(field));
                for (String name: fieldNames){
                    if (field.getName().equals(name)){
                        Object temp = dp.getValue(field);
                        Log.d("TINA", temp.toString());
                        jsonObject.put(name, temp.toString()); // This `name` should be the same as the table column
                    }
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject;
    }

    public JSONObject collectRow(DataPoint dp, JSONObject jsonObject, String[] fieldNames){
        try {
            // 4) and beyond: field values
            for (Field field : dp.getDataType().getFields()) {
                Log.i(TAG, "\tField: " + field.getName() + " Value: " + dp.getValue(field));
                for (String name: fieldNames){
                    if (field.getName().equals(name)){
                        jsonObject.put(name, dp.getValue(field)); // This `name` should be the same as the table column

                    }
                }
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return jsonObject;
    }

    public void insertToTable(JSONArray rows, int dataType){
        String table = null;
        if (dataType == 1) table = "google_fit_steps"; // Y
        if (dataType == 2) table = "google_fit_distance"; // Y
        if (dataType == 3) table = "google_fit_activity"; // Y
        if (dataType == 4) table = "google_fit_speed";
        if (dataType == 5) table = "google_fit_calories"; // Y
        if (dataType == 6) table = "google_fit_heart_rate";
        if (dataType == 7) table = "google_fit_weight"; //Y
        if (dataType == 8) table = "google_fit_body_fat"; // Y
        if (dataType == 9) table = "google_fit_hydration";
        if (dataType == 10) table = "google_fit_nutrition"; //Y
        if (dataType == 11) table = "google_fit_power";
        if (dataType == 12) table = "google_fit_bmr";

        if (table != null){
            boolean dataInserted = Jdbc.insertData(getApplicationContext(), table, rows);

            if (!dataInserted) {
                Log.d(Aware.TAG, "INSERT UNSUCCESS");
            } else {
                Log.d(Aware.TAG, "INSERT SUCCESS");
            }
        }

    }

    private class InsertDataAsyncTask extends AsyncTask<Object, Void, Void> {

        @Override
        protected Void doInBackground(Object... params) {
            JSONArray jsonArray = (JSONArray) params[0];
            int dataType = (int) params[1];
            insertToTable(jsonArray, dataType); // Call insertData() in the background thread
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            // Do any UI updates here if needed
        }
    }





}