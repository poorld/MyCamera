from pathlib import Path
t = Path(r"D:\hongda\app\MyCamera\tmp_record_verify.log").read_text(encoding="utf-8", errors="ignore")
keys = [
    "stable", "Skip restore", "Skip video", "startRecording", "onRecording",
    "SurfaceTexture", "GraphicBufferSource", "disconnect", "connect: api",
    "CameraXStrategy", "MainActivity", "handleCapture", "triple", "fallback",
    "Bound stable", "VideoEncoder", "Recorder", "ImageReader", "mycamera"
]
lines = t.splitlines()
# Prefer app process lines if present
app_lines = [ln for ln in lines if " 4539 " in ln or " 21900 " in ln or "CameraXStrategy" in ln or "MainActivity" in ln]
print("total", len(lines), "appish", len(app_lines))
sel = [ln for ln in lines if any(k.lower() in ln.lower() for k in keys)]
# filter noisy
sel = [ln for ln in sel if "BufferQueueProducer: [com.android.mycamera" not in ln]
print("matched", len(sel))
print("\n".join(sel[:150]))
