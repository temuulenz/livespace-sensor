#!/usr/bin/env python3
"""Summarize complete Phase 3 inference/audio runs; no recordings or synthetic observations."""
from pathlib import Path
import json,collections,statistics,math
folder=Path('docs/evidence/phase3');summaries=[]
for path in sorted(folder.glob('ai-audio-*.jsonl')):
    groups=collections.defaultdict(list)
    for line in path.read_text().splitlines():
        row=json.loads(line);groups[row['run']].append(row)
    for run,rows in groups.items():
        if len(rows)!=30 or {row['sample'] for row in rows}!=set(range(1,31)):
            print('Incomplete run excluded:',path.name,run,len(rows));continue
        summary=dict(file=path.name,run=run,samples=len(rows),microphoneEnabled=rows[0]['microphoneEnabled'],failed=max(r['failed'] for r in rows))
        for key in ['preMs','inferenceMs','postMs','totalMs']:
            values=sorted(r[key] for r in rows)
            summary[key]=dict(median=statistics.median(values),p95=values[math.ceil(.95*len(values))-1])
        summary['temperatureC']=[min(r['batteryC'] for r in rows),max(r['batteryC'] for r in rows)]
        summary['debugPssMiB']=[min(r['pssKiB'] for r in rows)/1024,max(r['pssKiB'] for r in rows)/1024]
        summary['validAudioSamples']=sum(r.get('noiseRms') is not None for r in rows)
        valid=[r for r in rows if r.get('noiseRms') is not None]
        summary['noiseDbfsRange']=[min(r['noiseDbfs'] for r in valid),max(r['noiseDbfs'] for r in valid)] if valid else None
        summary['noiseRmsRange']=[min(r['noiseRms'] for r in valid),max(r['noiseRms'] for r in valid)] if valid else None
        summary['positivePersonSamples']=sum(r['raw']>0 for r in rows)
        summary['actualInferenceHz']=29/((rows[-1]['capturedElapsedMs']-rows[0]['capturedElapsedMs'])/1000)
        summaries.append(summary)
(folder/'summary.json').write_text(json.dumps(summaries,indent=2)+'\n')
header='| Run | Audio | n | AI median/p95 ms | Total median/p95 ms | Valid audio samples | Battery °C | Debug PSS MiB |\n|---|---|---:|---:|---:|---:|---:|---:|\n'
lines=[]
for s in summaries:
    lines.append(f'| {s["file"]} | {"on" if s["microphoneEnabled"] else "off"} | {s["samples"]} | {s["inferenceMs"]["median"]:.1f}/{s["inferenceMs"]["p95"]:.1f} | {s["totalMs"]["median"]:.1f}/{s["totalMs"]["p95"]:.1f} | {s["validAudioSamples"]} | {s["temperatureC"][0]:.1f}–{s["temperatureC"][1]:.1f} | {s["debugPssMiB"][0]:.1f}–{s["debugPssMiB"][1]:.1f} |')
(folder/'BENCHMARKS.md').write_text(header+'\n'.join(lines)+'\n')
print(header+'\n'.join(lines))
