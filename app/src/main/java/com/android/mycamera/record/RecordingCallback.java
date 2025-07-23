package com.android.mycamera.record;

public interface RecordingCallback {
    void onRecordingStarted();
    void onRecordingStopped(String message);
}
