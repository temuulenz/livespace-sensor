#!/usr/bin/env python3
"""Exercise the app-private SQLite outbox across force-stop using a loopback WS mock."""
import base64,hashlib,json,re,socket,subprocess,threading,time

PKG='com.livespace.sensor';HOST='127.0.0.1';PORT=18081
def adb(*args,timeout=25): return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT,timeout=timeout)
def readn(c,n):
    b=b''
    while len(b)<n:
        x=c.recv(n-len(b))
        if not x: raise EOFError()
        b+=x
    return b
def frame(c):
    a,b=readn(c,2);n=b&127
    if n==126:n=int.from_bytes(readn(c,2),'big')
    elif n==127:n=int.from_bytes(readn(c,8),'big')
    mask=readn(c,4) if b&128 else b'';p=readn(c,n)
    if mask:p=bytes(v^mask[i%4] for i,v in enumerate(p))
    return a&15,p
def send(c,obj):
    p=json.dumps(obj,separators=(',',':')).encode();n=len(p)
    c.sendall((bytes([0x81,n]) if n<126 else bytes([0x81,126])+n.to_bytes(2,'big'))+p)
def main():
    adb('shell','am','start','-n',f'{PKG}/.QueueProbeActivity','--es','mode','seed')
    time.sleep(3);logs=adb('logcat','-d','-s','LiveSpaceQueueProbe:I')
    seeded=re.findall(r'SEEDED ([0-9a-f-]{36}) payloadSha256=([0-9a-f]{64})',logs)
    if not seeded:raise RuntimeError('SQLite seed step failed; inspect LiveSpaceQueueProbe log')
    target,expected=seeded[-1]
    adb('shell','am','force-stop',PKG);adb('reverse',f'tcp:{PORT}',f'tcp:{PORT}')
    server=socket.socket();server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1);server.bind((HOST,PORT));server.listen(1);server.settimeout(25)
    got=[];failure=[]
    def serve():
        try:
            c,_=server.accept();c.settimeout(20)
            with c:
                req=b''
                while b'\r\n\r\n' not in req:req+=c.recv(4096)
                lines=req.decode().split('\r\n');h={x.split(':',1)[0].lower():x.split(':',1)[1].strip() for x in lines[1:] if ':' in x}
                accept=base64.b64encode(hashlib.sha1((h['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
                c.sendall(b'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+b'\r\n\r\n')
                send(c,{'type':'sensor.ready','schemaVersion':1,'deviceId':'PHASE6-PROBE','locationId':'PHASE6-PROBE','dataSource':'real','maxPayloadBytes':16384})
                while not any(x['messageId']==target for x in got):
                    op,p=frame(c)
                    if op==8:return
                    if op!=1:continue
                    obj=json.loads(p);got.append(obj)
                    if obj['messageId']==target:
                        got[-1]['payloadSha256']=hashlib.sha256(p).hexdigest()
                    send(c,{'type':'sensor.ack','messageId':obj['messageId'],'status':'accepted','serverTimestamp':'2026-11-16T00:00:00.000Z'})
        except Exception as e:failure.append(type(e).__name__)
        finally:server.close()
    t=threading.Thread(target=serve,daemon=True);t.start()
    token='phase6-local-mock-token-only'
    adb('shell','am','start','-n',f'{PKG}/.QueueProbeActivity','--es','mode','replay','--es','endpoint',f'ws://127.0.0.1:{PORT}/ws/sensors','--es','testToken',token,'--es','targetId',target)
    t.join(25);adb('reverse','--remove',f'tcp:{PORT}')
    replay=next((x for x in got if x['messageId']==target),None)
    result={'seedId':target,'targetReplayed':replay is not None,'payloadChecksumMatches':bool(replay and replay.get('payloadSha256')==expected),
            'rawMediaAbsent':bool(replay and not any(k in json.dumps(replay).lower() for k in ('image','video','audio','pcm','frame'))),
            'messagesReceived':len(got),'serverErrors':failure}
    adb('shell','am','start','-n',f'{PKG}/.QueueProbeActivity','--es','mode','cleanup');time.sleep(2)
    cleanup=adb('logcat','-d','-s','LiveSpaceQueueProbe:I')
    result['testRowsCleaned']='TEST_ROWS_CLEANED' in cleanup
    print(json.dumps(result,indent=2))
    if not result['targetReplayed'] or not result['payloadChecksumMatches'] or failure:raise SystemExit(1)
if __name__=='__main__':main()
