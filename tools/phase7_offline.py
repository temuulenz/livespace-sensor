# Run from repository root only after manual tests and with the real sensor connected.
import subprocess,time,json,pathlib,re
folder=pathlib.Path(f'docs/evidence/phase7/offline-{int(time.time())}');folder.mkdir(parents=True,exist_ok=False)
def adb(*args):
 p=subprocess.run(['adb',*args],capture_output=True,text=True,timeout=25)
 if p.returncode or 'Error: Unknown option' in p.stdout or 'Error: Activity' in p.stdout: raise RuntimeError((p.stderr+p.stdout).strip())
 return p.stdout
def set_wifi(enabled):
 import xml.etree.ElementTree as ET
 adb('shell','input','keyevent','224')
 adb('shell','am','start','-a','android.settings.WIFI_SETTINGS')
 adb('shell','uiautomator','dump','/sdcard/phase7-wifi.xml')
 tree=ET.fromstring(adb('exec-out','cat','/sdcard/phase7-wifi.xml'))
 switches=[n for n in tree.iter('node') if n.get('class')=='android.widget.Switch']
 if len(switches)!=1: raise RuntimeError('Cannot identify the Wi-Fi switch safely')
 switch=switches[0]
 if (switch.get('checked')=='true')!=enabled:
  x1,y1,x2,y2=map(int,re.findall(r'\d+',switch.get('bounds')))
  adb('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2))
 expected='Wi-Fi is enabled' if enabled else 'Wi-Fi is disabled'
 for _ in range(10):
  if expected in adb('shell','dumpsys','wifi'):
   print('WIFI_SETTING_VERIFIED enabled='+str(enabled),flush=True);return
  time.sleep(1)
 raise RuntimeError('Wi-Fi switch did not reach the requested state')
start=time.monotonic()
def snapshot(label):
 logs=adb('logcat','-d','-v','brief','-s','LiveSpaceIntegration:I','LiveSpaceTransport:I')
 sample=next((s for s in reversed(logs.splitlines()) if 'SAMPLE ' in s),'')
 power=adb('shell','dumpsys','battery');temp=int(re.search(r'temperature:\s*(\d+)',power).group(1))/10
 row={'label':label,'hostEpoch':time.time(),'elapsedSeconds':round(time.monotonic()-start,3),'temperatureC':temp,'latestSample':sample}
 with (folder/'offline-monitor.jsonl').open('a') as f:f.write(json.dumps(row)+'\n')
 print(json.dumps(row),flush=True)
 if temp>=42: raise RuntimeError('THERMAL_LIMIT')
 return logs
initial=snapshot('before_wifi_off')
latest=next((s for s in reversed(initial.splitlines()) if 'SAMPLE ' in s),'')
if 'running=true' not in latest or 'network=CONNECTED' not in latest:
 raise RuntimeError('Start the real sensor and connect before running the outage')
if 'Wi-Fi is enabled' not in adb('shell','dumpsys','wifi'):
 raise RuntimeError('Expected Wi-Fi enabled; no settings changed')
try:
 set_wifi(False);start=time.monotonic()
 for i in range(5):
  pause=start+i*15-time.monotonic()
  if pause>0:time.sleep(pause)
  snapshot('wifi_off')
  if i==1:
   connectivity=adb('shell','dumpsys','connectivity')
   no_network='Active default network: none' in connectivity or 'Active default network: -1' in connectivity
   with (folder/'offline-network.json').open('w') as f:json.dump({'hostEpoch':time.time(),'noActiveDefaultNetwork':no_network,'defaultNetworkLine':next((s.strip() for s in connectivity.splitlines() if 'Active default network:' in s),None)},f)
   if not no_network: raise RuntimeError('No verified network outage; do not count this attempt')
 adb('shell','am','start','-f','0x18000000','-n','com.livespace.sensor/.ProductionIntegrationProbeActivity','--ez','audit','true')
 time.sleep(3)
 logs=snapshot('queue_before_wifi_restore');(folder/'offline-before-reconnect.txt').write_text(logs)
finally:
 set_wifi(True)
 print('WIFI_RESTORED',flush=True)
for i in range(12):
 time.sleep(5)
 logs=snapshot('reconnected')
 if i>=3:
  latest=next((s for s in reversed(logs.splitlines()) if 'SAMPLE ' in s),'')
  if 'network=CONNECTED' in latest and 'pending=0 ' in latest:break
adb('shell','am','start','-f','0x18000000','-n','com.livespace.sensor/.ProductionIntegrationProbeActivity','--ez','audit','true')
time.sleep(3)
logs=adb('logcat','-d','-v','brief','-s','LiveSpaceIntegration:I','LiveSpaceTransport:I')
(folder/'offline-after-reconnect.txt').write_text(logs)
rows={m[0]:m[1] for m in re.findall(r'QUEUE_ROW id=(\S+) sha256=(\S+)',(folder/'offline-before-reconnect.txt').read_text().rsplit('QUEUE_AUDIT',1)[-1])}
sends=dict(re.findall(r'SEND id=(\S+) sha256=(\S+)',logs))
acks=set(re.findall(r'ACK_(?:ACCEPTED|DUPLICATE) id=(\S+)',logs))
result={'queuedBeforeReconnect':len(rows),'rows':[{'messageId':key,'sha256':value,'identicalSendChecksum':sends.get(key)==value,'acknowledged':key in acks} for key,value in rows.items()]}
(folder/'offline-replay-comparison.json').write_text(json.dumps(result,indent=2));print(json.dumps(result),flush=True)
adb('shell','am','start','-n','com.livespace.sensor/.MainActivity')
if not rows or not all(x['identicalSendChecksum'] and x['acknowledged'] for x in result['rows']):
 raise RuntimeError('Replay verification incomplete; inspect retained run evidence')
