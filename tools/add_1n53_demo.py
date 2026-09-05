from pathlib import Path

root = Path(__file__).resolve().parents[1]
demo_path = root / 'dataview' / 'src' / 'DemoView.tsx'
styles_path = root / 'dataview' / 'src' / 'styles.css'

demo = demo_path.read_text(encoding='utf-8')

demo = demo.replace("import { useEffect } from 'react'", "import { useEffect, useState } from 'react'", 1)

if "import DemoLiveJourney from './DemoLiveJourney'" not in demo:
    demo = demo.replace("import 'leaflet/dist/leaflet.css'\n", "import 'leaflet/dist/leaflet.css'\nimport DemoLiveJourney from './DemoLiveJourney'\n", 1)

fn_anchor = "export default function DemoView({ email, role, onExit, onSignOut }: Props) {\n"
if "const [dataset, setDataset]" not in demo:
    replacement = fn_anchor + "  const [dataset, setDataset] = useState<'capability' | '1N53'>('capability')\n\n  if (dataset === '1N53') {\n    return <DemoLiveJourney email={email} role={role} onExit={onExit} onSignOut={onSignOut} onShowCapability={() => setDataset('capability')} />\n  }\n\n"
    if fn_anchor not in demo:
        raise SystemExit('DemoView function anchor not found')
    demo = demo.replace(fn_anchor, replacement, 1)

old_nav = '<nav><button className="demo-nav-button active" onClick={onExit}>← Live DataView</button><span className="demo-mode-pill">DEMO MODE</span></nav>'
new_nav = '<nav><button className="demo-nav-button active" onClick={onExit}>← Live DataView</button><button className="demo-nav-button actual-demo-link" onClick={() => setDataset(\'1N53\')}>1N53 actual live test</button><span className="demo-mode-pill">DEMO MODE</span></nav>'
if old_nav in demo:
    demo = demo.replace(old_nav, new_nav, 1)
elif '1N53 actual live test' not in demo:
    raise SystemExit('DemoView nav anchor not found')

demo_path.write_text(demo, encoding='utf-8')

styles = styles_path.read_text(encoding='utf-8')
marker = '/* SectionIQ 1N53 actual demo */'
if marker not in styles:
    styles += r'''

/* SectionIQ 1N53 actual demo */
.actual-demo-link{border:1px solid rgba(52,211,153,.35)!important;color:#a7f3d0!important}.demo-mode-pill.actual{background:rgba(52,211,153,.13);color:#a7f3d0}.actual-demo-banner{border-color:rgba(52,211,153,.38);background:linear-gradient(135deg,rgba(52,211,153,.09),rgba(13,27,45,.9))}.actual-demo-banner strong{color:#a7f3d0}.actual-demo-banner>span{background:rgba(52,211,153,.13);color:#a7f3d0}.actual-demo-metrics article:nth-child(2) strong{color:#fde68a}.actual-demo-metrics article:nth-child(3) strong{color:#a7f3d0}.actual-demo-metrics article:nth-child(4) strong{color:#a7f3d0}.actual-evidence-grid{margin-top:18px}.actual-poap{margin-top:18px}.actual-poap .ai-method-note strong{color:#ddd6fe}
'''
styles_path.write_text(styles, encoding='utf-8')

print('Integrated actual 1N53 live-test evidence into the DataView demo selector.')
