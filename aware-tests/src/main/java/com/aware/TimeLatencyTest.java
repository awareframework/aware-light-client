package com.aware.tests;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.DialogInterface;
import android.widget.Toast;
import com.aware.TimeLatency;

public class TestTimeLatency {
    
    private TimeLatency timeLatency = new TimeLatency();
    private int questionId = 1;
    
    public void test(final Activity activity) {
        // Call startTime() when question appears
        timeLatency.startTime(questionId);
        
        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setTitle("Question " + questionId);
        builder.setMessage("What is 2 + 2?");
        builder.setCancelable(false);
        
        builder.setPositiveButton("4", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showResult(activity, "4");
            }
        });
        
        builder.setNegativeButton("5", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                showResult(activity, "5");
            }
        });
        
        builder.show();
    }
    
    private void showResult(final Activity activity, String answer) {
        // Call questionAnswered() when user responds
        ContentValues result = timeLatency.questionAnswered(questionId);
        
        if (result == null) {
            Toast.makeText(activity, "Error: Question not found", Toast.LENGTH_SHORT).show();
            return;
        }
        
        long timeTaken = result.getAsLong("time_taken");
        String message = String.format("Answer: %s\nTime: %.2f seconds", answer, timeTaken / 1000.0);
        
        AlertDialog.Builder resultBuilder = new AlertDialog.Builder(activity);
        resultBuilder.setTitle("Result");
        resultBuilder.setMessage(message);
        resultBuilder.setPositiveButton("Next", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                questionId++;
                test(activity);
            }
        });
        resultBuilder.setNegativeButton("Done", null);
        resultBuilder.show();
    }
}

