#!/usr/bin/env python3
"""Debug-only Note4 contract probe against a local protocol-v1 WS mock; emits aggregate evidence only."""
import base64,hashlib,json,re,secrets,socket,subprocess,threading
from pathlib import Path

ROOT_KEYS={'schemaVersion','type','messageId','deviceId','locationId','timestamp','occupancy','noise','device'}
def adb(*args):return subprocess.check_output(['adb',*args],text=True,stderr=subprocess.STDOUT,timeout=20)
def recv_exact(conn,n):
    data=b''
    while len(data)<n:
        block=conn.recv(n-len(data))
        if not block:raise EOFError('socket closed')
        data+=block
    return data
def send_text(conn,text):
    body=text.encode();size=len(body)
    if size<126:head=bytes([0x81,size])
    elif size<65536:head=bytes([0x81,126])+size.to_bytes(2,'big')
    else:raise ValueError('oversized mock frame')
    conn.sendall(head+body)
def recv_frame(conn):
    first,second=recv_exact(conn,2);length=second&127
    if length==126:length=int.from_bytes(recv_exact(conn,2),'big')
    elif length==127:length=int.from_bytes(recv_exact(conn,8),'big')
    if length>16384:raise ValueError('oversized client frame')
    mask=recv_exact(conn,4) if second&128 else b'';payload=recv_exact(conn,length)
    if mask:payload=bytes(value^mask[i%4] for i,value in enumerate(payload))
    return first&15,payload
def main():
    token=secrets.token_urlsafe(32);result={};server=socket.socket();server.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
    server.bind(('127.0.0.1',18080));server.listen(1);server.settimeout(30)
    def handle():
        try:
            conn,_=server.accept();conn.settimeout(15)
            with conn:
                request=b''
                while b'\r\n\r\n' not in request and len(request)<8192:request+=conn.recv(4096)
                lines=request.decode('ascii').split('\r\n');headers={line.split(':',1)[0].lower():line.split(':',1)[1].strip() for line in lines[1:] if ':' in line}
                result['authorizationCorrect']=headers.get('authorization')=='Bearer '+token
                result['pathCorrect']=lines[0].split()[1]=='/ws/sensors'
                if not result['authorizationCorrect']:conn.sendall(b'HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n');return
                accept=base64.b64encode(hashlib.sha1((headers['sec-websocket-key']+'258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest())
                conn.sendall(b'HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: '+accept+b'\r\n\r\n')
                ready={'type':'sensor.ready','schemaVersion':1,'deviceId':'LS-NOTE4-001','locationId':'I','dataSource':'real','maxPayloadBytes':16384}
                send_text(conn,json.dumps(ready,separators=(',',':')))
                while True:
                    opcode,payload=recv_frame(conn)
                    if opcode==9:conn.sendall(bytes([0x8A,len(payload)])+payload);continue
                    if opcode==8:result['clientClosed']=True;return
                    if opcode!=1:result['textOnly']=False;return
                    message=json.loads(payload.decode('utf-8'))
                    result['rootFieldsExact']=set(message)==ROOT_KEYS
                    result['schemaVersionNumeric']=type(message.get('schemaVersion')) is int and message['schemaVersion']==1
                    result['deviceLocationCorrect']=message.get('deviceId')=='LS-NOTE4-001' and message.get('locationId')=='I'
                    result['telemetryTypeCorrect']=message.get('type')=='sensor.telemetry'
                    result['messageIdIsUuid']=bool(re.fullmatch(r'[0-9a-f-]{36}',message.get('messageId','')))
                    result['timestampUtc']=bool(re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z',message.get('timestamp','')))
                    result['nestedFieldsExact']=(set(message.get('occupancy',{}))=={'observedPeople','confidence'} and
                        set(message.get('noise',{}))=={'rms','dbfs','estimatedDbSpl','calibrated'} and
                        set(message.get('device',{}))=={'batteryPercent','batteryTemperatureC','charging','inferenceMs','uptimeSeconds'})
                    result['rawMediaAbsent']=not any(word in json.dumps(message).lower() for word in ('image','video','audio','pcm','frame'))
                    ack={'type':'sensor.ack','messageId':message['messageId'],'status':'accepted','serverTimestamp':'2026-11-16T00:00:01.000Z'}
                    send_text(conn,json.dumps(ack,separators=(',',':')))
                    result['telemetryReceived']=True;return
        except Exception as error:result['mockError']=type(error).__name__
        finally:server.close()
    thread=threading.Thread(target=handle,daemon=True);thread.start()
    try:
        adb('reverse','tcp:18080','tcp:18080');adb('shell','input','keyevent','224')
        adb('shell','am','start','-n','com.livespace.sensor/.ConnectionProbeActivity','--es','endpoint','ws://127.0.0.1:18080/ws/sensors','--es','testToken',token)
        thread.join(30)
        logs=adb('logcat','-d','-v','raw','-s','LiveSpaceNetworkProbe:I')
        result['keystoreRoundtrip']='KEYSTORE_ROUNDTRIP_OK' in logs
        result['deviceAcknowledged']='TELEMETRY_ACK_OK' in logs
        result['testTokenRedacted']=token not in logs
        Path('docs/evidence/phase4/device-network-probe.json').write_text(json.dumps(result,indent=2)+'\n')
        Path('docs/evidence/phase4/device-network-probe.txt').write_text(logs)
        print(json.dumps(result))
        assert all(result.get(key) for key in ['authorizationCorrect','pathCorrect','rootFieldsExact','schemaVersionNumeric','deviceLocationCorrect','telemetryTypeCorrect','messageIdIsUuid','timestampUtc','nestedFieldsExact','rawMediaAbsent','telemetryReceived','keystoreRoundtrip','deviceAcknowledged','testTokenRedacted'])
    finally:
        try:adb('reverse','--remove','tcp:18080')
        finally:server.close()
if __name__=='__main__':main()
