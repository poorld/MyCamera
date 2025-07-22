package com.android.mycamera;

public interface RecordingCallback {
    void onRecordingStarted();
    void onRecordingStopped(String message);
}
