from pathlib import Path

root = Path(__file__).resolve().parents[1]
app_path = root / 'dataview' / 'src' / 'App.tsx'
styles_path = root / 'dataview' / 'src' / 'styles.css'
gradle_path = root / 'app' / 'build.gradle.kts'
main_path = root / 'app' / 'src' / 'main' / 'java' / 'com' / 'bodgejob' / 'sectioniq' / 'MainActivity.kt'

app = app_path.read_text(encoding='utf-8')
if "import HeadcodeAnalysis from './HeadcodeAnalysis'" not in app:
    app = app.replace("import DemoView from './DemoView'\n", "import DemoView from './DemoView'\nimport HeadcodeAnalysis from './HeadcodeAnalysis'\n", 1)
if "const [headcodeMode, setHeadcodeMode]" not in app:
    app = app.replace("  const [demoMode, setDemoMode] = useState(false)\n", "  const [demoMode, setDemoMode] = useState(false)\n  const [headcodeMode, setHeadcodeMode] = useState(false)\n", 1)
anchor = "  if (demoMode) {\n    return <DemoView email={session.user.email ?? ''} role={role} onExit={() => setDemoMode(false)} onSignOut={() => supabase.auth.signOut()} />\n  }\n\n"
if "if (headcodeMode)" not in app:
    replacement = anchor + "  if (headcodeMode) {\n    return <div className=\"app-shell\"><header className=\"topbar\"><Logo compact /><nav><button className=\"demo-nav-button active\">Headcodes</button></nav><div className=\"user-menu\"><span>{session.user.email}</span><small>{role}</small><button onClick={() => supabase.auth.signOut()}>Sign out</button></div></header><HeadcodeAnalysis onBack={() => setHeadcodeMode(false)} /><footer className=\"app-footer\">SectionIQ — built by Bodge Job Apps</footer></div>\n  }\n\n"
    if anchor not in app:
        raise SystemExit('demo anchor not found')
    app = app.replace(anchor, replacement, 1)
old_nav = '<nav><a className="active" href="#overview">Overview</a><a href="#journeys">Journeys</a><a href="#performance">Performance</a><a href="#pathfinder">Pathfinder</a><button className="demo-nav-button" onClick={() => setDemoMode(true)}>Demo</button></nav>'
new_nav = '<nav><a className="active" href="#overview">Overview</a><a href="#journeys">Journeys</a><button className="demo-nav-button" onClick={() => setHeadcodeMode(true)}>Headcodes</button><a href="#performance">Performance</a><a href="#pathfinder">Pathfinder</a><button className="demo-nav-button" onClick={() => setDemoMode(true)}>Demo</button></nav>'
if old_nav in app:
    app = app.replace(old_nav, new_nav, 1)
app_path.write_text(app, encoding='utf-8')

styles = styles_path.read_text(encoding='utf-8')
marker = '/* SectionIQ headcode analysis */'
if marker not in styles:
    styles += r'''

/* SectionIQ headcode analysis */
.headcode-controls{margin-bottom:20px}.analysis-controls{display:grid;grid-template-columns:minmax(180px,240px) minmax(180px,260px) auto;align-items:end;gap:14px;padding:18px}.analysis-route-title{margin:26px 0 18px}.analysis-route-title>div{display:flex;align-items:center;gap:10px}.analysis-route-title>div span{padding:5px 8px;border-radius:999px;background:rgba(34,211,238,.12);color:#a5f3fc;font-size:.72rem;font-weight:800}.analysis-route-title>div strong{font-size:1.4rem}.analysis-route-title h2{margin:8px 0 5px}.analysis-route-title p{margin:0;color:var(--muted);font-size:.84rem}.ai-trend-panel{margin-top:18px}.ai-badge{padding:6px 9px;border-radius:999px;background:rgba(168,85,247,.15);color:#ddd6fe;font-size:.72rem;font-weight:900;letter-spacing:.06em;text-transform:uppercase}.trend-body{padding:18px}.trend-lead{padding:18px;border:1px solid var(--line);border-radius:12px;background:#0a1726}.trend-lead.bad{border-color:rgba(239,68,68,.42)}.trend-lead.warn{border-color:rgba(245,158,11,.42)}.trend-lead.good{border-color:rgba(34,197,94,.42)}.trend-lead span,.trend-grid span{display:block;color:var(--muted);font-size:.75rem}.trend-lead strong{display:block;font-size:1.25rem;margin:7px 0}.trend-lead p,.trend-grid p{margin:0;color:#cbd7e4;line-height:1.5}.trend-grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px;margin-top:12px}.trend-grid article{padding:16px;border:1px solid var(--line);border-radius:12px;background:#0a1726}.trend-grid strong{display:block;margin:7px 0}.ai-method-note{margin:14px 0 0;padding:12px 14px;border-left:3px solid #a855f7;background:rgba(168,85,247,.07);color:#cbd7e4;font-size:.82rem;line-height:1.5}.analysis-two-column{display:grid;grid-template-columns:1fr 1fr;gap:18px;margin-top:18px}.journey-comparison-panel{margin-top:18px}.headcode-analysis-page .delay-badge.good{background:rgba(34,197,94,.12);color:#bbf7d0}.headcode-analysis-page .delay-badge.warn{background:rgba(245,158,11,.14);color:#fde68a}.headcode-analysis-page .delay-badge.bad{background:rgba(239,68,68,.14);color:#fecaca}@media(max-width:980px){.analysis-two-column{grid-template-columns:1fr}.trend-grid{grid-template-columns:1fr}.analysis-controls{grid-template-columns:1fr 1fr}.analysis-controls button{grid-column:1/-1}}@media(max-width:680px){.analysis-controls{grid-template-columns:1fr}}
'''
styles_path.write_text(styles, encoding='utf-8')

gradle = gradle_path.read_text(encoding='utf-8')
gradle = gradle.replace('versionCode = 1', 'versionCode = 2', 1)
gradle = gradle.replace('versionName = "1.0"', 'versionName = "1.1-closed-test"', 1)
gradle_path.write_text(gradle, encoding='utf-8')

main = main_path.read_text(encoding='utf-8')
if 'var locationDisclosureAccepted by remember' not in main:
    permission_anchor = '''    var hasFineLocationPermission by remember {\n\n        mutableStateOf(\n\n            ContextCompat.checkSelfPermission('''
    disclosure = '''    var locationDisclosureAccepted by remember {\n        mutableStateOf(\n            sectionPreferences.getBoolean(\n                "location_disclosure_accepted",\n                false\n            )\n        )\n    }\n\n\n'''
    if permission_anchor not in main:
        raise SystemExit('location permission anchor not found')
    main = main.replace(permission_anchor, disclosure + permission_anchor, 1)

old_launch = '''    LaunchedEffect(\n        Unit\n    ) {\n\n        if (\n            !hasFineLocationPermission\n        ) {\n\n            locationPermissionLauncher\n                .launch(\n                    arrayOf(\n                        Manifest.permission\n                            .ACCESS_COARSE_LOCATION,\n\n                        Manifest.permission\n                            .ACCESS_FINE_LOCATION\n                    )\n                )\n        }\n    }\n'''
new_launch = '''    LaunchedEffect(\n        locationDisclosureAccepted\n    ) {\n\n        if (\n            locationDisclosureAccepted &&\n            !hasFineLocationPermission\n        ) {\n\n            locationPermissionLauncher\n                .launch(\n                    arrayOf(\n                        Manifest.permission\n                            .ACCESS_COARSE_LOCATION,\n\n                        Manifest.permission\n                            .ACCESS_FINE_LOCATION\n                    )\n                )\n        }\n    }\n'''
if old_launch in main:
    main = main.replace(old_launch, new_launch, 1)

# Insert a prominent disclosure immediately before the main screen content column if its stable text is present.
content_anchor = '''    Column(\n        modifier =\n            Modifier\n                .fillMaxSize()'''
if 'SectionIQ records precise location while you actively record a journey' not in main:
    disclosure_ui = '''    if (!locationDisclosureAccepted) {\n        Surface(\n            modifier = Modifier.fillMaxSize(),\n            color = Color(0xFFF4F7FA)\n        ) {\n            Column(\n                modifier = Modifier\n                    .fillMaxSize()\n                    .padding(24.dp),\n                verticalArrangement = Arrangement.Center\n            ) {\n                Text(\n                    text = "Location use in SectionIQ",\n                    fontSize = 28.sp,\n                    fontWeight = FontWeight.Bold,\n                    color = Color(0xFF10243A)\n                )\n                Spacer(Modifier.height(16.dp))\n                Text(\n                    text = "SectionIQ records precise location while you actively record a railway journey. Recording continues through a foreground service if you minimise or lock the phone, so the full journey can be measured. Location points are uploaded securely to SectionIQ to analyse running time, station dwell and operational performance. Recording starts only when you press START and stops when you press STOP.",\n                    fontSize = 16.sp,\n                    color = Color(0xFF334E68)\n                )\n                Spacer(Modifier.height(20.dp))\n                Button(\n                    modifier = Modifier.fillMaxWidth(),\n                    onClick = {\n                        sectionPreferences.edit()\n                            .putBoolean("location_disclosure_accepted", true)\n                            .apply()\n                        locationDisclosureAccepted = true\n                    }\n                ) {\n                    Text("Continue")\n                }\n            }\n        }\n        return\n    }\n\n\n'''
    if content_anchor not in main:
        raise SystemExit('main content anchor not found')
    main = main.replace(content_anchor, disclosure_ui + content_anchor, 1)
main_path.write_text(main, encoding='utf-8')

print('Prepared DataView headcode analysis and Android closed-test release settings.')
