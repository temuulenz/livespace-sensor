#!/usr/bin/env python3
"""Supervised ADB camera inference benchmarks; writes structured metrics only, never media."""
import argparse,json,os,selectors,subprocess,time,uuid,re
from pathlib import Path

def adb(*args): return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT)
def temperature():
    text=adb('shell','dumpsys','battery')
    m=re.search(r'^\s*temperature:\s*(-?\d+)',text,re.M)
    return int(m[1])/10 if m else None

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--samples',type=int,default=30)
    parser.add_argument('--configs',default='1:3000,2:3000,4:3000,2:2000,2:5000')
    parser.add_argument('--output',default='docs/evidence/benchmark-samples.jsonl')
    parser.add_argument('--microphone',choices=['on','off','saved'],default='saved');args=parser.parse_args()
    out=Path(args.output);out.parent.mkdir(parents=True,exist_ok=True)
    with out.open('a') as output:
        for pair in args.configs.split(','):
            threads,interval=map(int,pair.split(':'));run=uuid.uuid4().hex
            temp=temperature()
            if temp is None or temp>=42: raise RuntimeError(f'Thermal guard before run: battery={temp}')
            print(f'START run={run} threads={threads} intervalMs={interval} batteryC={temp}',flush=True)
            adb('shell','am','force-stop','com.livespace.sensor')
            adb('shell','input','keyevent','224')
            log=subprocess.Popen(['adb','logcat','-v','raw','-s','LiveSpaceBenchmark:I','AndroidRuntime:E'],stdout=subprocess.PIPE)
            select=selectors.DefaultSelector();select.register(log.stdout,selectors.EVENT_READ)
            try:
                microphone_args=[] if args.microphone=='saved' else ['--ez','microphoneEnabled',str(args.microphone=='on').lower()]
                adb('shell','am','start','-n','com.livespace.sensor/.MainActivity','--ei','benchmarkSamples',str(args.samples),'--ei','threads',str(threads),'--el','intervalMs',str(interval),'--es','benchmarkRun',run,*microphone_args)
                deadline=time.monotonic()+args.samples*(interval/1000+1)+45;check=time.monotonic()+10;last_sample=time.monotonic();buffer=b'';seen=set()
                while len(seen)<args.samples:
                    if time.monotonic()>deadline: raise RuntimeError('Timed out waiting for successful inference samples')
                    if time.monotonic()-last_sample>max(20,interval/1000+10):
                        power=adb('shell','dumpsys','power')
                        if 'mWakefulness=Asleep' in power: raise RuntimeError('Device screen switched off; foreground-only sensor stopped correctly')
                    if time.monotonic()>check:
                        temp=temperature();print(f'THERMAL run={run} batteryC={temp} samples={len(seen)}',flush=True)
                        if temp is None or temp>=42: raise RuntimeError(f'Thermal guard during run: battery={temp}')
                        check=time.monotonic()+10
                    for key,_ in select.select(timeout=1):
                        chunk=os.read(key.fileobj.fileno(),65536)
                        if not chunk:raise RuntimeError('ADB log stream ended')
                        buffer+=chunk
                        while b'\n' in buffer:
                            line,buffer=buffer.split(b'\n',1);s=line.decode(errors='replace')
                            if s.startswith('SAMPLE '):
                                row=json.loads(s[7:])
                                if row.get('run')==run and row['sample'] in seen:
                                    raise RuntimeError('Activity/capture restarted during benchmark; rerun configuration')
                                if row.get('run')==run and row['sample'] not in seen:
                                    last_sample=time.monotonic();seen.add(row['sample']);output.write(json.dumps(row)+'\n');output.flush()
                                    if len(seen)%10==0:print(f'PROGRESS run={run} samples={len(seen)} inferenceMs={row["inferenceMs"]:.1f} pssKiB={row["pssKiB"]}',flush=True)
                print(f'DONE run={run} samples={len(seen)} batteryC={temperature()}',flush=True)
            finally:
                log.terminate();log.wait(timeout=5);select.close()
                try: adb('shell','am','force-stop','com.livespace.sensor')
                except subprocess.CalledProcessError as error: print(f'Cleanup ADB unavailable: {error.output}',flush=True)
if __name__=='__main__': main()
