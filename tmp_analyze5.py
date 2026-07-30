from pathlib import Path
t = Path(r"D:\hongda\app\MyCamera\tmp_record_verify5.log").read_text(encoding="utf-8", errors="ignore")
print("lines", len(t.splitlines()))
keys = ["CameraXStrategy","MainActivity","stable","Skip","Bound","startRecording","onRecording","handleCapture",
        "SurfaceTexture","ImageReader","GraphicBuffer","disconnect","connect:","Recorder","Recording","VideoEncoder",
        "UseCaseAttach","bind","fallback","triple","Session onConfigured","CameraService"]
for ln in t.splitlines():
    low = ln.lower()
    if any(k.lower() in low for k in keys):
        # reduce noise from unrelated
        if "AeFlow" in ln or "AfAlgo" in ln or "af_mgr" in ln:
            continue
        print(ln)
