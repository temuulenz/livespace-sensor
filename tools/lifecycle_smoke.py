#!/usr/bin/env python3
"""ADB text-only lifecycle checks; never screenshot or save camera media."""
import json,re,subprocess,sys,time,xml.etree.ElementTree as ET
from pathlib import Path

def adb(*args):return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT,timeout=20)
def hierarchy():
    adb('shell','uiautomator','dump','/sdcard/livespace-phase2-window.xml')
    return ET.fromstring(adb('shell','cat','/sdcard/livespace-phase2-window.xml').strip())
def click(suffix):
    root=hierarchy()
    nodes=[n for n in root.iter('node') if n.get('resource-id','').endswith(suffix)]
    for attempt in range(3):
        if nodes:break
        bounds=list(map(int,re.findall(r'\d+',next(root.iter('node')).get('bounds'))))
        w,h=bounds[2],bounds[3]
        adb('shell','input','swipe',str(w//2),str(h*85//100),str(w//2),str(h//4),'400')
        root=hierarchy();nodes=[n for n in root.iter('node') if n.get('resource-id','').endswith(suffix)]
    if not nodes:raise RuntimeError(f'UI control not visible: {suffix}')
    points=list(map(int,re.findall(r'\d+',nodes[0].get('bounds'))))
    adb('shell','input','tap',str((points[0]+points[2])//2),str((points[1]+points[3])//2))
def text():return '\n'.join(n.get('text','') for n in hierarchy().iter('node'))
def wait_result():
    end=time.monotonic()+25
    while time.monotonic()<end:
        value=text()
        if re.search(r'Inferences: [1-9]\d*',value) and 'Camera running' in value:return value
    raise RuntimeError('No successful inference visible in dashboard')
def stopped():
    value=text()
    if 'Stopped' not in value or 'Closed • press Start to load' not in value:raise RuntimeError('Stopped/model-closed state missing')
    # Closing is asynchronous; inspect the active-client header, not historical client logs.
    end=time.monotonic()+10
    while time.monotonic()<end:
        clients=adb('shell','dumpsys media.camera | head -n 10')
        if re.search(r'Active Camera Clients:\s*\[\]',clients):return
    raise RuntimeError('Camera still has an active client')
def launch():adb('shell','am','start','-n','com.livespace.sensor/.MainActivity')

def main():
    if '--permissions-only' in sys.argv:
        path=Path('docs/evidence/lifecycle-smoke.txt')
        evidence=path.read_text().splitlines() if path.exists() else []
        try:
            adb('shell','pm','revoke','com.livespace.sensor','android.permission.CAMERA')
            launch();click(':id/start');click(':id/permission_deny_button')
            permissions=adb('shell','dumpsys','package','com.livespace.sensor')
            denied='android.permission.CAMERA, granted=false' in permissions
            evidence.append(f'Isolated denial check: permission denied={denied}')
            print(evidence[-1],flush=True)
            if not denied:raise RuntimeError('Denial not confirmed at OS permission state')
            if 'permission denied' not in text():raise RuntimeError('Denied permission UI state missing')
            evidence.append('Camera denial: OS permission denied and clear app UI state verified')
            click(':id/start');click(':id/permission_allow_button');wait_result();click(':id/stop');stopped()
            evidence.append('Camera grant/retry: successful inference followed by Stop/release verified')
            print(evidence[-1],flush=True)
        finally:
            path.write_text('\n'.join(evidence)+'\n')
        return
    evidence=[]
    def record(message):evidence.append(message);print(message,flush=True)
    auto=adb('shell','settings','get','system','accelerometer_rotation').strip()
    rotation=adb('shell','settings','get','system','user_rotation').strip()
    try:
        adb('shell','am','force-stop','com.livespace.sensor');adb('shell','input','keyevent','224');launch()
        for i in range(3):
            click(':id/start');wait_result();record(f'Cycle {i+1}: model ready, real inference visible')
            click(':id/stop');stopped();record(f'Cycle {i+1}: explicit Stop and camera-client release passed')
        click(':id/start');wait_result();adb('shell','input','keyevent','3');launch();stopped();record('Home/return: stopped, model closed, camera released')
        adb('shell','settings','put','system','accelerometer_rotation','0')
        adb('shell','settings','put','system','user_rotation','1')
        subprocess.run(['python3','tools/benchmark_device.py','--samples','3','--configs','2:3000','--output','docs/evidence/rotation-smoke.jsonl'],check=True)
        record('Landscape rotation: fresh model/camera initialized and 3 camera inferences succeeded; visual box alignment not verified')
        adb('shell','settings','put','system','user_rotation',rotation)
        adb('shell','settings','put','system','accelerometer_rotation',auto)
        adb('shell','pm','revoke','com.livespace.sensor','android.permission.CAMERA');launch();click(':id/start')
        click(':id/permission_deny_button')
        if 'permission denied' not in text():raise RuntimeError('Permission denial state not visible')
        record('Runtime Camera permission denial: clear UI state, no crash')
        click(':id/start');click(':id/permission_allow_button');wait_result();click(':id/stop');stopped()
        record('Runtime Camera permission grant/retry: inference succeeded, then cleanup passed')
    finally:
        for name,value in [('user_rotation',rotation),('accelerometer_rotation',auto)]:
            try:
                if value=='null':adb('shell','settings','delete','system',name)
                else:adb('shell','settings','put','system',name,value)
            except Exception as error:record(f'Restore {name} failed: {error}')
        Path('docs/evidence/lifecycle-smoke.txt').write_text('\n'.join(evidence)+'\n')
if __name__=='__main__':main()
