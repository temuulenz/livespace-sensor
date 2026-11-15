# Run from repository root only after the user is ready to leave the phone untouched.
import subprocess,time,json,pathlib,re
out=pathlib.Path(f'docs/evidence/phase7/screen-off-{int(time.time())}.jsonl')
def adb(*args):
 r=subprocess.run(['adb',*args],capture_output=True,text=True,timeout=20)
 if r.returncode: raise RuntimeError(r.stderr.strip())
 return r.stdout
adb('shell','input','keyevent','3')
adb('shell','input','keyevent','223')
start=time.monotonic()
with out.open('w') as f:
 for i in range(11):
  delay=start+i*30-time.monotonic()
  if delay>0: time.sleep(delay)
  power=adb('shell','dumpsys','power');battery=adb('shell','dumpsys','battery')
  logs=adb('logcat','-d','-v','brief','-s','LiveSpaceIntegration:I','LiveSpaceTransport:I')
  sample=next((l for l in reversed(logs.splitlines()) if 'SAMPLE ' in l),'')
  state=re.search(r'mWakefulness=(\w+)',power)
  temp=int(re.search(r'temperature:\s*(\d+)',battery).group(1))/10
  row={'hostEpoch':time.time(),'elapsedSeconds':round(time.monotonic()-start,3),'wakefulness':state.group(1) if state else None,'temperatureC':temp,'latestSample':sample}
  f.write(json.dumps(row)+'\n');f.flush();print(json.dumps(row),flush=True)
  if row['wakefulness']=='Awake' or 'running=false' in sample:
   raise RuntimeError('INCOMPLETE: display woke or sensing stopped; retain this run separately')
  if temp>=42:
   adb('shell','am','start','-n','com.livespace.sensor/.ProductionIntegrationProbeActivity','--ez','stop','true')
   raise RuntimeError('THERMAL_STOP')
print('FIVE_MINUTE_OBSERVATION_ENDED',flush=True)
adb('shell','input','keyevent','224')
adb('shell','am','start','-n','com.livespace.sensor/.MainActivity')
