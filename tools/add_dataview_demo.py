from pathlib import Path

root = Path(__file__).resolve().parents[1]
app_path = root / 'dataview' / 'src' / 'App.tsx'
styles_path = root / 'dataview' / 'src' / 'styles.css'

app = app_path.read_text(encoding='utf-8')

if "import DemoView from './DemoView'" not in app:
    app = app.replace("import JourneyMap from './JourneyMap'\n", "import JourneyMap from './JourneyMap'\nimport DemoView from './DemoView'\n", 1)

if "const [demoMode, setDemoMode]" not in app:
    app = app.replace("  const [loading, setLoading] = useState(false)\n", "  const [loading, setLoading] = useState(false)\n  const [demoMode, setDemoMode] = useState(false)\n", 1)

anchor = "  const completeCount = journeys.filter((j) => j.status === 'complete').length\n"
if "if (demoMode)" not in app:
    demo_return = "  if (demoMode) {\n    return <DemoView email={session.user.email ?? ''} role={role} onExit={() => setDemoMode(false)} onSignOut={() => supabase.auth.signOut()} />\n  }\n\n"
    if anchor not in app:
        raise SystemExit('completeCount anchor not found')
    app = app.replace(anchor, demo_return + anchor, 1)

old_nav = '<nav><a className="active" href="#overview">Overview</a><a href="#journeys">Journeys</a><a href="#performance">Performance</a><a href="#pathfinder">Pathfinder</a></nav>'
new_nav = '<nav><a className="active" href="#overview">Overview</a><a href="#journeys">Journeys</a><a href="#performance">Performance</a><a href="#pathfinder">Pathfinder</a><button className="demo-nav-button" onClick={() => setDemoMode(true)}>Demo</button></nav>'
if old_nav in app:
    app = app.replace(old_nav, new_nav, 1)

old_heading_button = '<button className="secondary-button" onClick={loadJourneys} disabled={loading}>{loading ? \'Refreshing…\' : \'Refresh data\'}</button>'
new_heading_buttons = '<div className="heading-actions"><button className="secondary-button demo-open-button" onClick={() => setDemoMode(true)}>Open demo journey</button><button className="secondary-button" onClick={loadJourneys} disabled={loading}>{loading ? \'Refreshing…\' : \'Refresh data\'}</button></div>'
if old_heading_button in app:
    app = app.replace(old_heading_button, new_heading_buttons, 1)

app_path.write_text(app, encoding='utf-8')

styles = styles_path.read_text(encoding='utf-8')
marker = '/* SectionIQ demo mode */'
if marker not in styles:
    styles += r'''
/* SectionIQ demo mode */
.heading-actions{display:flex;gap:10px;flex-wrap:wrap}.demo-nav-button{padding:9px 12px;border:0;border-radius:8px;background:transparent;color:var(--muted);font-size:.9rem}.demo-nav-button:hover,.demo-nav-button.active{background:linear-gradient(135deg,rgba(34,211,238,.14),rgba(59,130,246,.16));color:var(--text)}.demo-open-button{border-color:rgba(34,211,238,.48);color:#bff7ff}.demo-mode-pill{display:inline-flex;align-items:center;margin-left:8px;padding:6px 9px;border-radius:999px;background:rgba(245,158,11,.12);color:#fcd34d;font-size:.7rem;font-weight:900;letter-spacing:.09em}.demo-banner{display:flex;justify-content:space-between;gap:20px;align-items:center;margin-bottom:22px;padding:16px 18px;border:1px solid rgba(245,158,11,.38);border-radius:14px;background:linear-gradient(135deg,rgba(245,158,11,.11),rgba(13,27,45,.9))}.demo-banner strong{display:block;color:#fde68a}.demo-banner p{margin:4px 0 0;color:#cbd7e4;line-height:1.45}.demo-banner>span{white-space:nowrap;padding:7px 10px;border-radius:999px;background:rgba(245,158,11,.14);color:#fcd34d;font-size:.72rem;font-weight:800}.demo-source-panel,.demo-comparison-panel,.demo-poap{margin-top:18px}.demo-provenance-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;background:var(--line)}.demo-provenance-grid>div{padding:18px;background:#0a1726}.demo-provenance-grid strong{display:block;margin:10px 0 6px}.demo-provenance-grid p{margin:0;color:var(--muted);font-size:.82rem;line-height:1.5}.evidence-chip{display:inline-flex;padding:4px 7px;border-radius:999px;font-size:.65rem;font-weight:900;letter-spacing:.08em}.evidence-chip.real{background:rgba(52,211,153,.13);color:#a7f3d0}.evidence-chip.synthetic{background:rgba(59,130,246,.16);color:#bfdbfe}.evidence-chip.reference{background:rgba(168,85,247,.14);color:#ddd6fe}.map-legend{display:flex;gap:8px;flex-wrap:wrap}.lg{display:inline-flex;align-items:center;gap:6px;font-size:.72rem;color:var(--muted)}.lg:before{content:'';width:18px;height:4px;border-radius:99px}.lg.green:before{background:#22c55e}.lg.amber:before{background:#f59e0b}.lg.red:before{background:#ef4444}.table-sub{display:block;color:var(--muted);margin-top:3px;font-size:.69rem}.delay-badge{display:inline-flex;padding:4px 7px;border-radius:999px;font-size:.72rem;font-weight:800}.delay-badge.good{background:rgba(34,197,94,.12);color:#bbf7d0}.delay-badge.warn{background:rgba(245,158,11,.14);color:#fde68a}.delay-badge.bad{background:rgba(239,68,68,.14);color:#fecaca}.demo-timeline{padding:4px 0}.demo-timeline article{display:grid;grid-template-columns:72px 1fr;gap:14px;padding:16px 18px;border-top:1px solid var(--line)}.demo-timeline article:first-child{border-top:0}.demo-timeline time{color:var(--cyan);font-weight:800}.demo-timeline strong{display:block;margin:7px 0 4px}.demo-timeline p{margin:0;color:#cbd7e4;line-height:1.5;font-size:.86rem}.train-ahead-list article{padding:17px 18px;border-top:1px solid var(--line)}.train-ahead-list article:first-child{border-top:0}.train-ahead-list strong,.train-ahead-list span,.train-ahead-list small{display:block}.train-ahead-list span{margin-top:5px;color:#cbd7e4}.train-ahead-list small{margin-top:5px;color:var(--muted)}.poap-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:1px;background:var(--line)}.poap-grid>div{padding:18px;background:#0a1726}.poap-grid span,.poap-grid small{display:block;color:var(--muted);font-size:.76rem}.poap-grid strong{display:block;margin:7px 0 4px}.demo-metrics article:nth-child(2) strong{color:#fecaca}@media(max-width:980px){.demo-provenance-grid,.poap-grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:680px){.heading-actions{width:100%}.heading-actions button{flex:1}.demo-banner{align-items:flex-start;flex-direction:column}.demo-banner>span{white-space:normal}.demo-provenance-grid,.poap-grid{grid-template-columns:1fr}.demo-timeline article{grid-template-columns:1fr}.map-legend{justify-content:flex-start}}
'''
styles_path.write_text(styles, encoding='utf-8')

print('Integrated DemoView into the authenticated DataView and added demo styling.')
