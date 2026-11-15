#!/usr/bin/env python3
"""Test camera+AI+audio initialization at each display rotation. Visual correctness remains human-confirmed."""
import subprocess,json,argparse
from pathlib import Path

def adb(*args):return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT,timeout=20)
def main():
    auto=adb('shell','settings','get','system','accelerometer_rotation').strip()
    rotation=adb('shell','settings','get','system','user_rotation').strip()
    parser=argparse.ArgumentParser();parser.add_argument('--rotations',default='0,1,2,3');args=parser.parse_args()
    previous=Path('docs/evidence/phase4/rotations.json')
    results=json.loads(previous.read_text()) if previous.exists() else []
    try:
        adb('shell','settings','put','system','accelerometer_rotation','0')
        for r in map(int,args.rotations.split(',')):
            assert r in range(4)
            adb('shell','settings','put','system','user_rotation',str(r))
            path=f'docs/evidence/phase4/rotation-{r}.jsonl'
            before=len(Path(path).read_text().splitlines()) if Path(path).exists() else 0
            subprocess.run(['python3','tools/benchmark_device.py','--samples','3','--configs','2:3000','--microphone','on','--output',path],check=True)
            rows=[json.loads(l) for l in Path(path).read_text().splitlines()[before:]]
            results=[result for result in results if result['displayDegrees']!=r*90]
            results.append({'displayDegrees':r*90,'samples':len(rows),'failed':max(x['failed'] for x in rows),'visualVerified':False})
    finally:
        for key,value in [('user_rotation',rotation),('accelerometer_rotation',auto)]:
            if value=='null':adb('shell','settings','delete','system',key)
            else:adb('shell','settings','put','system',key,value)
        Path('docs/evidence/phase4/rotations.json').write_text(json.dumps(results,indent=2)+'\n')
if __name__=='__main__':main()
