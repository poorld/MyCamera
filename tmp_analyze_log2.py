from pathlib import Path
t = Path(r"D:\hongda\app\MyCamera\tmp_record_verify.log").read_text(encoding="utf-8", errors="ignore")
lines = t.splitlines()
# extract only pid 4539 lines
app = [ln for ln in lines if " 4539 " in ln]
print("app lines", len(app))
print("--- first 40 app ---")
print("\n".join(app[:40]))
print("--- keywords in app ---")
keys = ["CameraX", "Recording", "Recorder", "SurfaceTexture", "ImageReader", "GraphicBuffer", "disconnect", "connect", "MainActivity", "handleCapture", "startRecording", "stable", "Skip", "Bound", "VideoConfig", "UseCase"]
for ln in app:
    if any(k.lower() in ln.lower() for k in keys):
        print(ln)
print("--- camera service around time ---")
for ln in lines:
    if "CameraService" in ln or "Camera2Client" in ln or "CameraDevice" in ln:
        if "mycamera" in ln.lower() or "4539" in ln or "Camera 0" in ln:
            print(ln)
