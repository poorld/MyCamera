package com.android.mycamera.record;

public interface IRecordingFragment {
    void startRecording(String resolution, int frameRate);
    void stopRecording();
    boolean isCurrentlyRecording();
    void setRecordingCallback(RecordingCallback callback);
}
