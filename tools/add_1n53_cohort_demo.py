from pathlib import Path
import json

root = Path(__file__).resolve().parents[1]
demo_path = root / 'dataview' / 'src' / 'DemoLiveJourney.tsx'
styles_path = root / 'dataview' / 'src' / 'styles.css'
package_path = root / 'dataview' / 'package.json'

demo = demo_path.read_text(encoding='utf-8')
if "import Demo1N53Cohort from './Demo1N53Cohort'" not in demo:
    anchor = "import 'leaflet/dist/leaflet.css'\n"
    if anchor not in demo:
        raise SystemExit('leaflet import anchor not found')
    demo = demo.replace(anchor, anchor + "import Demo1N53Cohort from './Demo1N53Cohort'\n", 1)

if '<Demo1N53Cohort />' not in demo:
    anchor = '        <section className="panel demo-poap actual-poap">'
    if anchor not in demo:
        raise SystemExit('actual POAP anchor not found')
    demo = demo.replace(anchor, '        <Demo1N53Cohort />\n\n' + anchor, 1)

demo_path.write_text(demo, encoding='utf-8')

styles = styles_path.read_text(encoding='utf-8')
marker = '/* SectionIQ 1N53 large cohort demo */'
if marker not in styles:
    styles += r'''

/* SectionIQ 1N53 large cohort demo */
.cohort-panel{margin-top:18px}.cohort-count{padding:7px 10px;border-radius:999px;background:rgba(34,211,238,.1);color:#a5f3fc;font-size:.75rem;font-weight:800}.cohort-provenance{margin:0 18px 18px;padding:13px 15px;border-left:3px solid #f59e0b;background:rgba(245,158,11,.08);color:#d7e1ec;font-size:.82rem;line-height:1.55}.cohort-metrics{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;padding:0 18px 18px}.cohort-metrics article{padding:15px;border:1px solid var(--line);border-radius:12px;background:#0a1726}.cohort-metrics span,.cohort-metrics small{display:block;color:var(--muted);font-size:.73rem}.cohort-metrics strong{display:block;margin:6px 0;font-size:1.25rem}.cohort-chart-card{margin:0 18px 18px;padding:16px;border:1px solid var(--line);border-radius:12px;background:#081522}.cohort-chart-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:15px;margin-bottom:10px}.cohort-chart-heading strong,.cohort-chart-heading small{display:block}.cohort-chart-heading small{margin-top:4px;color:var(--muted);font-size:.72rem}.cohort-legend{display:flex;gap:12px;flex-wrap:wrap;color:var(--muted);font-size:.72rem}.cohort-legend span{display:flex;align-items:center;gap:6px}.cohort-legend i{width:8px;height:8px;border-radius:50%;display:inline-block}.model-dot{background:#22d3ee}.actual-dot{background:#34d399}.cohort-chart-scroll{overflow-x:auto}.cohort-chart-scroll svg{display:block;width:100%;min-width:720px;height:auto}.cohort-zero{stroke:rgba(226,232,240,.35);stroke-width:1;stroke-dasharray:5 5}.cohort-line{fill:none;stroke:#22d3ee;stroke-width:2;opacity:.78}.cohort-point{fill:#22d3ee}.cohort-point.actual{fill:#34d399;stroke:#ecfdf5;stroke-width:2}.cohort-axis-label,.cohort-actual-label{fill:#9fb0c2;font-size:11px}.cohort-actual-label{fill:#a7f3d0;font-weight:700}.ai-cohort-panel{margin:0 18px 18px;padding:16px;border:1px solid rgba(168,85,247,.3);border-radius:12px;background:rgba(168,85,247,.055)}.ai-cohort-heading{display:flex;align-items:center;gap:10px;margin-bottom:12px}.ai-cohort-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.ai-cohort-grid article{padding:14px;border:1px solid var(--line);border-radius:10px;background:#0a1726}.ai-cohort-grid span{display:block;color:var(--muted);font-size:.72rem}.ai-cohort-grid strong{display:block;margin:6px 0}.ai-cohort-grid p,.ai-cohort-method{margin:0;color:#c7d3df;font-size:.79rem;line-height:1.5}.ai-cohort-grid .actual-insight{border-color:rgba(52,211,153,.38)}.ai-cohort-method{margin-top:12px;padding-top:12px;border-top:1px solid rgba(168,85,247,.24)}.cohort-toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:0 18px 12px}.cohort-periods{display:flex;gap:6px}.cohort-periods button{border:1px solid var(--line);background:#0a1726;color:#c9d7e5;border-radius:8px;padding:7px 10px;font:inherit;font-size:.75rem;cursor:pointer}.cohort-periods button.active{border-color:#22d3ee;color:#a5f3fc;background:rgba(34,211,238,.09)}.cohort-toolbar label{display:flex;gap:7px;align-items:center;color:var(--muted);font-size:.76rem}.cohort-table-wrap{max-height:520px;overflow:auto;border-top:1px solid var(--line)}.cohort-table-wrap thead{position:sticky;top:0;z-index:1;background:#0c1a2a}.actual-cohort-row{background:rgba(52,211,153,.075)!important}.actual-cohort-row td:first-child strong{color:#a7f3d0}@media(max-width:980px){.cohort-metrics{grid-template-columns:repeat(2,1fr)}.ai-cohort-grid{grid-template-columns:1fr}.cohort-chart-heading,.cohort-toolbar{align-items:flex-start;flex-direction:column}}@media(max-width:600px){.cohort-metrics{grid-template-columns:1fr}.cohort-periods{flex-wrap:wrap}}
'''
styles_path.write_text(styles, encoding='utf-8')

package = json.loads(package_path.read_text(encoding='utf-8'))
version = package.get('version', '0.1.5')
parts = version.split('.')
try:
    parts[-1] = str(int(parts[-1]) + 1)
    package['version'] = '.'.join(parts)
except ValueError:
    package['version'] = '0.1.6'
package_path.write_text(json.dumps(package, indent=2) + '\n', encoding='utf-8')

print('Integrated 1N53 multi-day comparison cohort into live-test demo.')
