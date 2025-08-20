package com.android.mycamera.record;

import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import java.util.Timer;
import java.util.TimerTask;

public class RecordingTimer {
    private Timer timer;
    private int time;
    private final TextView timeTextView;
    
    public RecordingTimer(TextView timeTextView) {
        this.timeTextView = timeTextView;
    }
    
    public void start() {
        stop();
        timer = new Timer();
        time = 0;
        timer.schedule(new TimerTask() {
            @Override
            public void run() {
                new Handler(Looper.getMainLooper()).post(() -> 
                    timeTextView.setText(getFormattedTime())
                );
            }
        }, 1000, 1000);
    }
    
    public void stop() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        time = 0;
        new Handler(Looper.getMainLooper()).post(() -> 
            timeTextView.setText("00:00")
        );
    }
    
    private String getFormattedTime() {
        time += 1;
        int hours = time / 3600;
        int minutes = (time % 3600) / 60;
        int seconds = time % 60;
        
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%d:%02d", minutes, seconds);
        }
    }
}