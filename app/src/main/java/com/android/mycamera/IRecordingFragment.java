package com.android.mycamera;

public interface IRecordingFragment {
    void startRecording(String resolution, int frameRate);
    void stopRecording();
    boolean isCurrentlyRecording();
    void setRecordingCallback(RecordingCallback callback);
}
