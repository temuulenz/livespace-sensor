#!/usr/bin/env python3
"""Portrait Note 4 Start/Stop/Home checks using derived debug logs, no screenshot or media."""
import json,re,subprocess,time,uuid
from pathlib import Path
from phase3_capture_checks import adb,app_rows,camera_clients

def main():
    run=uuid.uuid4().hex; evidence=[];marker=-1
    def record(s):evidence.append(s);print(s,flush=True)
    try:
        adb('shell','am','force-stop','com.livespace.sensor');adb('shell','input','keyevent','224')
        adb('shell','am','start','-n','com.livespace.sensor/.MainActivity','--ei','benchmarkSamples','200','--es','benchmarkRun',run,'--ez','microphoneEnabled','true')
        for cycle in range(3):
            if cycle:adb('shell','input','tap','400','1730')
            deadline=time.monotonic()+25;rows=[]
            while time.monotonic()<deadline:
                rows=[r for r in app_rows(run) if r['capturedElapsedMs']>marker and r['noiseRms'] is not None]
                if len(rows)>=2:break
                time.sleep(1)
            assert len(rows)>=2, 'Simultaneous AI/audio not observed'
            marker=max(r['capturedElapsedMs'] for r in app_rows(run))
            record(f'Cycle {cycle+1}: simultaneous AI/audio valid; sample rate {rows[-1]["noiseSampleRate"]}, dBFS {rows[-1]["noiseDbfs"]:.2f}')
            adb('shell','input','tap','1000','1730');time.sleep(1)
            assert re.search(r'Active Camera Clients:\s*\[\]',camera_clients()),'Camera still open after Stop'
            logs=adb('logcat','-d','-v','raw','-s','LiveSpaceAudio:I')
            lines=[line for line in logs.splitlines() if line.startswith('START ') or line=='RELEASED']
            assert lines and lines[-1]=='RELEASED','Audio owner did not release after Stop'
            time.sleep(1)
            assert not [r for r in app_rows(run) if r['capturedElapsedMs']>marker],'Inference continued after Stop'
            record(f'Cycle {cycle+1}: explicit Stop released camera and microphone; no later inference result')
        # Start once more, then Home. Return must not auto-restart microphone/camera.
        adb('shell','input','tap','400','1730');time.sleep(4)
        adb('shell','input','keyevent','3');time.sleep(1)
        adb('shell','am','start','-n','com.livespace.sensor/.MainActivity');time.sleep(1)
        assert re.search(r'Active Camera Clients:\s*\[\]',camera_clients()),'Camera reopened on return'
        logs=adb('logcat','-d','-v','raw','-s','LiveSpaceAudio:I')
        lines=[line for line in logs.splitlines() if line.startswith('START ') or line=='RELEASED']
        assert lines[-1]=='RELEASED','Microphone not released on Home'
        record('Home/return: camera remains released and final audio owner marker is RELEASED')
        Path('docs/evidence/phase3/lifecycle-audio-log.txt').write_text(logs)
    finally:
        adb('shell','am','force-stop','com.livespace.sensor')
        Path('docs/evidence/phase3/lifecycle-checks.txt').write_text('\n'.join(evidence)+'\n')
if __name__=='__main__':main()
