#!/usr/bin/env python3
"""Read TFLite schema v3 tables without host TensorFlow. Schema field IDs from TF 2.14 schema.fbs."""
import collections, hashlib, json, struct
from pathlib import Path
b = Path('app/src/main/assets/detect.tflite').read_bytes()
def val(p, fmt='I'): return struct.unpack_from('<'+fmt,b,p)[0]
def field(t,i):
    v=t-val(t,'i'); o=4+2*i
    return t+val(v+o,'H') if o<val(v,'H') and val(v+o,'H') else 0
def indirect(p): return p+val(p) if p else 0
def vector(t,i,fmt='i'):
    p=indirect(field(t,i))
    return [val(p+4+j*struct.calcsize(fmt),fmt) for j in range(val(p))] if p else []
def tables(t,i):
    p=indirect(field(t,i))
    return [indirect(p+4+j*4) for j in range(val(p))] if p else []
def string(t,i):
    p=indirect(field(t,i));return b[p+4:p+4+val(p)].decode() if p else None
def scalar(t,i,fmt='i',default=0):
    p=field(t,i);return val(p,fmt) if p else default
root=val(0); graph=tables(root,2)[0]; tensors=tables(graph,0)
def tensor(i):
    t=tensors[i];q=indirect(field(t,4))
    return dict(index=i,name=string(t,3),shape=vector(t,0),type=scalar(t,1,'b'),scales=vector(q,2,'f') if q else [],zeroPoints=vector(q,3,'q') if q else [])
codes=[dict(builtin=max(scalar(t,0,'b'),scalar(t,3)),custom=string(t,1),version=scalar(t,2,default=1)) for t in tables(root,1)]
ops=collections.Counter(scalar(t,0,'I') for t in tables(graph,3))
report=dict(sha256=hashlib.sha256(b).hexdigest(),schemaVersion=scalar(root,0),description=string(root,3),inputs=[tensor(i) for i in vector(graph,1)],outputs=[tensor(i) for i in vector(graph,2)],operators=[dict(**c,count=ops[i]) for i,c in enumerate(codes)],tensorTypes=dict(collections.Counter(scalar(t,1,'b') for t in tensors)),metadataEntries=len(tables(root,6)))
print(json.dumps(report,indent=2))
