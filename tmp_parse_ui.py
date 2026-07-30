import re
from pathlib import Path
xml = Path(r"D:\hongda\app\MyCamera\tmp_ui2.xml").read_text(encoding="utf-8")
for name in ["captureButton", "videoModeButton", "photoModeButton", "statusText"]:
    idx = xml.find(f'id/{name}"')
    if idx < 0:
        print(name, "NOT FOUND")
        continue
    frag = xml[idx:idx+500]
    bounds = re.search(r'bounds="(\[[^\"]+)"', frag)
    text = re.search(r'text="([^\"]*)"', frag)
    selected = re.search(r'selected="([^\"]*)"', frag)
    print(name, "bounds=", bounds.group(1) if bounds else None, "text=", text.group(1) if text else None, "selected=", selected.group(1) if selected else None)
