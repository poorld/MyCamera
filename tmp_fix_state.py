from pathlib import Path

# Fix BaseCameraStrategy notifyRecordingStopped
p = Path(r"app\src\main\java\com\android\mycamera\camera\strategy\BaseCameraStrategy.java")
t = p.read_text(encoding="utf-8")
old = """    protected void notifyRecordingStopped() {
        notifyStateChanged(CameraState.OPENED);
        for (CameraStateListener listener : stateListeners) {
            listener.onRecordingStopped();
        }
    }"""
new = """    protected void notifyRecordingStopped() {
        // Stay on preview pipeline; do not go back to OPENED or MainActivity will restart preview.
        notifyStateChanged(CameraState.PREVIEW_STARTED);
        for (CameraStateListener listener : stateListeners) {
            listener.onRecordingStopped();
        }
    }"""
if old not in t:
    raise SystemExit("notifyRecordingStopped not found")
p.write_text(t.replace(old, new, 1), encoding="utf-8")
print("BaseCameraStrategy fixed")

# Harden MainActivity OPENED branch: don't restart preview while recording-related states
p2 = Path(r"app\src\main\java\com\android\mycamera\ui\activity\MainActivity.java")
t2 = p2.read_text(encoding="utf-8")
old2 = """                case OPENED:
                    statusText.setText("Camera ready");
                    mCameraManager.startPreview(cameraPreview, this);
                    updateFlashButton();
                    updateZoomUi();
                    updateManualExposureUi();
                    updateSettingsDisplay();
                    updateSwitchCameraButtonIcon();
                    break;"""
new2 = """                case OPENED:
                    statusText.setText("Camera ready");
                    // Only start preview on true open. Avoid restarting after stop-recording.
                    if (!isRecording) {
                        mCameraManager.startPreview(cameraPreview, this);
                    }
                    updateFlashButton();
                    updateZoomUi();
                    updateManualExposureUi();
                    updateSettingsDisplay();
                    updateSwitchCameraButtonIcon();
                    break;"""
if old2 not in t2:
    raise SystemExit("OPENED branch not found")
p2.write_text(t2.replace(old2, new2, 1), encoding="utf-8")
print("MainActivity fixed")
