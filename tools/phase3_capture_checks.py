#!/usr/bin/env python3
"""Physical camera/audio lifecycle and AppOps-rejection checks. Stores text/aggregate metrics only."""
import json,re,subprocess,time,uuid
from pathlib import Path

def adb(*args):return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT,timeout=20)
def app_rows(run):
    data=adb('logcat','-d','-v','raw','-s','LiveSpaceBenchmark:I')
    return [json.loads(line[7:]) for line in data.splitlines() if line.startswith('SAMPLE ') and json.loads(line[7:]).get('run')==run]
def wait_samples(run,count):
    deadline=time.monotonic()+30
    while time.monotonic()<deadline:
        rows=app_rows(run)
        if len(rows)>=count:return rows
        time.sleep(1)
    raise RuntimeError(f'Only {len(app_rows(run))} inference samples, expected {count}')
def camera_clients():return adb('shell','dumpsys media.camera | head -n 10')
def launch(run):
    adb('shell','input','keyevent','224')
    adb('shell','am','start','-n','com.livespace.sensor/.MainActivity','--ei','benchmarkSamples','200','--es','benchmarkRun',run,'--ei','threads','2','--el','intervalMs','3000','--ez','microphoneEnabled','true')
def main():
    evidence=[]; folder=Path('docs/evidence/phase3');folder.mkdir(exist_ok=True)
    mode=adb('shell','appops','get','com.livespace.sensor','RECORD_AUDIO')
    match=re.search(r'RECORD_AUDIO:\s*(\w+)',mode);original=match[1] if match else 'default'
    def record(line):evidence.append(line);print(line,flush=True)
    try:
        adb('shell','am','force-stop','com.livespace.sensor')
        adb('shell','appops','set','com.livespace.sensor','RECORD_AUDIO','deny')
        record('Controlled capture rejection: '+adb('shell','appops','get','com.livespace.sensor','RECORD_AUDIO').strip())
        run=uuid.uuid4().hex;launch(run);rows=wait_samples(run,3)
        record(f'AppOps denied: {len(rows)} successful AI inferences; microphone={rows[-1]["microphoneStatus"]}; noiseRms={rows[-1]["noiseRms"]}')
        assert rows[-1]['noiseRms'] is None and 'error' in rows[-1]['microphoneStatus'].lower()
        (folder/'capture-denied.json').write_text(json.dumps(rows,indent=2)+'\n')
        adb('shell','input','keyevent','3')
        time.sleep(1)
        record('Denied-session Home camera release: '+str(bool(re.search(r'Active Camera Clients:\s*\[\]',camera_clients()))))
        adb('shell','am','force-stop','com.livespace.sensor')
        adb('shell','appops','set','com.livespace.sensor','RECORD_AUDIO',original)
        run=uuid.uuid4().hex;launch(run);rows=wait_samples(run,3)
        assert rows[-1]['noiseRms'] is not None and 'running' in rows[-1]['microphoneStatus'].lower()
        record(f'Restored capture: AI and microphone both running, {rows[-1]["noiseSampleRate"]} Hz, dBFS={rows[-1]["noiseDbfs"]:.2f}')
        acoustic_logs=adb('logcat','-d','-v','raw','-s','LiveSpaceAcoustics:I')
        windows=[json.loads(line[6:]) for line in acoustic_logs.splitlines() if line.startswith('AUDIO ') and json.loads(line[6:]).get('run')==run]
        (folder/'noise-windows.jsonl').write_text(''.join(json.dumps(row)+'\n' for row in windows))
        record(f'Real one-second noise windows retained as aggregate evidence: {len(windows)} (no PCM)')
        # Home stops the normal sensor session; don't relaunch benchmark extras.
        adb('shell','input','keyevent','3');time.sleep(1)
        record('Simultaneous-session Home camera release: '+str(bool(re.search(r'Active Camera Clients:\s*\[\]',camera_clients()))))
        logs=adb('logcat','-d','-v','raw','-s','LiveSpaceAudio:I')
        record('Audio owner release marker present: '+str('RELEASED' in logs))
        (folder/'audio-lifecycle-log.txt').write_text(logs)
        (folder/'capture-restored.json').write_text(json.dumps(rows,indent=2)+'\n')
    finally:
        adb('shell','appops','set','com.livespace.sensor','RECORD_AUDIO',original)
        adb('shell','am','force-stop','com.livespace.sensor')
        (folder/'capture-checks.txt').write_text('\n'.join(evidence)+'\n')
if __name__=='__main__':main()
