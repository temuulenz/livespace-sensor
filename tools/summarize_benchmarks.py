#!/usr/bin/env python3
"""Summarize complete 30-sample runs. Keep interrupted runs in the raw evidence."""
import collections,json,math,statistics
from pathlib import Path
rows=[json.loads(l) for l in Path('docs/evidence/benchmark-samples.jsonl').read_text().splitlines() if l]
groups=collections.defaultdict(list)
for row in rows:groups[row['run']].append(row)
report=[]
for run,samples in groups.items():
    if len(samples)!=30 or {r['sample'] for r in samples}!=set(range(1,31)):
        print(f'Excluded incomplete run {run}: {len(samples)} samples');continue
    out={'run':run,'threads':samples[0]['threads'],'intervalMs':samples[0]['intervalMs'],'samples':len(samples),'initMs':samples[0]['initMs'],'failed':max(r['failed'] for r in samples)}
    for key in ['preMs','inferenceMs','postMs','totalMs']:
        values=sorted(r[key] for r in samples)
        out[key]={'median':statistics.median(values),'p95':values[math.ceil(len(values)*0.95)-1]}
    out['pssMiB']={'min':min(r['pssKiB'] for r in samples)/1024,'max':max(r['pssKiB'] for r in samples)/1024}
    out['batteryC']={'min':min(r['batteryC'] for r in samples),'max':max(r['batteryC'] for r in samples)}
    out['actualHz']=29/((samples[-1]['capturedElapsedMs']-samples[0]['capturedElapsedMs'])/1000)
    out['positivePersonSamples']=sum(r['raw']>0 for r in samples)
    report.append(out)
Path('docs/evidence/benchmark-summary.json').write_text(json.dumps(report,indent=2)+'\n')
header='| Threads | Interval | n | Init ms | Pre median/p95 ms | Invoke median/p95 ms | Post median/p95 ms | Total median/p95 ms | PSS MiB | Battery °C | Hz | Failures |\n|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n'
def pair(row,key):return f'{row[key]["median"]:.1f}/{row[key]["p95"]:.1f}'
lines=[]
for r in report:
    lines.append(f'| {r["threads"]} | {r["intervalMs"]/1000:g} s | {r["samples"]} | {r["initMs"]:.1f} | {pair(r,"preMs")} | {pair(r,"inferenceMs")} | {pair(r,"postMs")} | {pair(r,"totalMs")} | {r["pssMiB"]["min"]:.1f}–{r["pssMiB"]["max"]:.1f} | {r["batteryC"]["min"]:.1f}–{r["batteryC"]["max"]:.1f} | {r["actualHz"]:.3f} | {r["failed"]} |')
Path('docs/evidence/BENCHMARKS.md').write_text(header+'\n'.join(lines)+'\n')
print(header+'\n'.join(lines))
