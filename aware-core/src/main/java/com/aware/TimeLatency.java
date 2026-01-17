package com.aware;

import android.content.ContentValues;
import com.aware.utils.Aware_Sensor;

import java.util.ArrayList;
import java.util.List;

/**
 * TimeLatency Sensor
 * 
 * Tracks the time it takes for participants to answer questions.
 * Records start time when a question appears and calculates duration
 * when the question is answered.
 * 
 * 
 */
public class TimeLatency extends Aware_Sensor {
    
    // List to store timing data for multiple questions
    private List<ContentValues> data_values = new ArrayList<ContentValues>();
    
    /**
     * Records the start time when a question appears/becomes visible.
     * 
     * @param questionId The unique identifier for the question
     * @return ContentValues containing the question_id and start_time
     */
    public ContentValues startTime(int questionId) {
        // Get current timestamp in milliseconds
        long beginningTime = System.currentTimeMillis();
        
        // Create a new ContentValues object (like a dictionary) to store this question's data
        ContentValues dataForStartingTime = new ContentValues();
        
        // Store the question ID and start time
        dataForStartingTime.put("question_id", questionId);
        dataForStartingTime.put("start_time", beginningTime);
        dataForStartingTime.put("starting_timestamp", beginningTime);
        
        // Add this entry to our list so we can find it later
        data_values.add(dataForStartingTime);
        
        return dataForStartingTime;
    }
    
    /**
     * Helper method to search for a question's data by its ID.
     * 
     * @param questionId The unique identifier for the question to find
     * @return ContentValues for the question if found, null otherwise
     */
    private ContentValues searchingQuestion(int questionId) {
        // Loop through all stored questions
        for (int a = 0; a < data_values.size(); a++) {
            // Get the ContentValues (dictionary) for this question
            ContentValues data = data_values.get(a);
            
            // Check if this is the question we're looking for
            if (data.getAsInteger("question_id") == questionId) {
                return data; // Found it! Return the data
            }
        }
        // Question not found in the list
        return null;
    }
    
    /**
     * Records the end time when a question is answered and calculates the duration.
     * Updates the existing entry with end_time and time_taken.
     * 
     * @param questionId The unique identifier for the question that was answered
     * @return ContentValues containing all timing data (start_time, end_time, time_taken)
     *         Returns null if the question was not found
     */
    public ContentValues questionAnswered(int questionId) {
        // Get current timestamp when question is answered
        long timeCompleted = System.currentTimeMillis();
        
        // Find the existing entry for this question
        ContentValues dataForCompletedTime = searchingQuestion(questionId);
        
        // Safety check: make sure we found the question
        if (dataForCompletedTime == null) {
            return null; // Question not found - can't calculate time
        }
        
        // Get the start time we stored earlier
        long beginningTime = dataForCompletedTime.getAsLong("start_time");
        
        // Calculate how long it took to answer (in milliseconds)
        long timeTaken = timeCompleted - beginningTime;
        
        // Update the existing entry with end time and duration
        dataForCompletedTime.put("end_time", timeCompleted);
        dataForCompletedTime.put("ending_timestamp", timeCompleted);
        dataForCompletedTime.put("time_taken", timeTaken);
        
        // Return the complete data (now has start_time, end_time, and time_taken)
        return dataForCompletedTime;
    }
}
