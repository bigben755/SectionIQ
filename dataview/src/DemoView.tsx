import { useEffect, useState } from 'react'
import L from 'leaflet'
import { CircleMarker, MapContainer, Polyline, TileLayer, Tooltip, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import DemoLiveJourney from './DemoLiveJourney'

type Props = {
  email: string
  role: string | null
  onExit: () => void
  onSignOut: () => void
}

type DemoCall = {
  crs: string
  name: string
  lat: number
  lon: number
  wtt: string
  publicTime: string
  publicExpected: string
  demoObserved: string
  delaySeconds: number
}

const calls: DemoCall[] = [
  { crs:'LIV', name:'Liverpool Lime Street', lat:53.40730825272, lon:-2.97772602818, wtt:'dep 07:00', publicTime:'07:00', publicExpected:'07:00', demoObserved:'07:00:08', delaySeconds:8 },
  { crs:'EDG', name:'Edge Hill', lat:53.40261614879, lon:-2.94648295952, wtt:'07:03½ / 07:04', publicTime:'07:04', publicExpected:'07:04', demoObserved:'07:04:02', delaySeconds:2 },
  { crs:'WAV', name:'Wavertree Technology Park', lat:53.4051917769, lon:-2.92290891019, wtt:'07:05½ / 07:06', publicTime:'07:06', publicExpected:'07:06', demoObserved:'07:06:05', delaySeconds:5 },
  { crs:'BGE', name:'Broad Green', lat:53.40650328883, lon:-2.89348391276, wtt:'07:08½ / 07:09½', publicTime:'07:09', publicExpected:'07:09', demoObserved:'07:09:11', delaySeconds:11 },
  { crs:'ROB', name:'Roby', lat:53.41004137491, lon:-2.85593353755, wtt:'07:12 / 07:12½', publicTime:'07:12', publicExpected:'07:12', demoObserved:'07:12:08', delaySeconds:8 },
  { crs:'HUY', name:'Huyton', lat:53.40968404177, lon:-2.84298882748, wtt:'07:14 / 07:15', publicTime:'07:15', publicExpected:'07:15', demoObserved:'07:15:03', delaySeconds:3 },
  { crs:'WHN', name:'Whiston', lat:53.41386889749, lon:-2.79643183204, wtt:'07:17½ / 07:18½', publicTime:'07:18', publicExpected:'07:18', demoObserved:'07:18:06', delaySeconds:6 },
  { crs:'RNH', name:'Rainhill', lat:53.41712164479, lon:-2.76640023607, wtt:'07:20½ / 07:21', publicTime:'07:21', publicExpected:'07:21', demoObserved:'07:21:02', delaySeconds:2 },
  { crs:'LEG', name:'Lea Green', lat:53.42680954725, lon:-2.72497749591, wtt:'07:23½ / 07:24½', publicTime:'07:24', publicExpected:'07:24', demoObserved:'07:24:09', delaySeconds:9 },
  { crs:'SHJ', name:'St Helens Junction', lat:53.43372556882, lon:-2.70025944775, wtt:'07:26½ / 07:27½', publicTime:'07:27', publicExpected:'07:27', demoObserved:'07:27:07', delaySeconds:7 },
  { crs:'ERL', name:'Earlestown', lat:53.45113672322, lon:-2.63766325712, wtt:'07:31½ / 07:32½', publicTime:'07:32', publicExpected:'07:32', demoObserved:'07:32:03', delaySeconds:3 },
  { crs:'NLW', name:'Newton-le-Willows', lat:53.45306051839, lon:-2.6135980844, wtt:'07:34½ / 07:35½', publicTime:'07:35', publicExpected:'07:35', demoObserved:'07:35:10', delaySeconds:10 },
  { crs:'PAT', name:'Patricroft', lat:53.48477735199, lon:-2.3582446404, wtt:'07:44½ / 07:45', publicTime:'07:45', publicExpected:'07:45', demoObserved:'07:45:06', delaySeconds:6 },
  { crs:'ECC', name:'Eccles', lat:53.48535851872, lon:-2.3345142803, wtt:'07:47 / 07:48', publicTime:'07:48', publicExpected:'07:48', demoObserved:'07:48:11', delaySeconds:11 },
  { crs:'DGT', name:'Deansgate', lat:53.47418376225, lon:-2.25105094652, wtt:'07:54½ / 07:56½', publicTime:'07:56', publicExpected:'07:56', demoObserved:'07:56:14', delaySeconds:14 },
  { crs:'MCO', name:'Manchester Oxford Road', lat:53.47403168107, lon:-2.24199540982, wtt:'07:58 / 08:00', publicTime:'08:00', publicExpected:'08:08', demoObserved:'08:07:48', delaySeconds:468 },
  { crs:'MAN', name:'Manchester Piccadilly', lat:53.47736139295, lon:-2.23090989998, wtt:'08:02 / 08:04', publicTime:'08:04', publicExpected:'08:12', demoObserved:'08:11:54', delaySeconds:474 },
  { crs:'MAU', name:'Mauldeth Road', lat:53.43306110246, lon:-2.20925122444, wtt:'08:09 / 08:09½', publicTime:'08:09', publicExpected:'08:17', demoObserved:'08:16:51', delaySeconds:471 },
  { crs:'BNA', name:'Burnage', lat:53.42116681578, lon:-2.21567811439, wtt:'08:11 / 08:12', publicTime:'08:12', publicExpected:'08:20', demoObserved:'08:19:56', delaySeconds:476 },
  { crs:'EDY', name:'East Didsbury', lat:53.40930831313, lon:-2.22199631348, wtt:'08:13½ / 08:14', publicTime:'08:14', publicExpected:'08:22', demoObserved:'08:21:49', delaySeconds:469 },
  { crs:'GTY', name:'Gatley', lat:53.39290459011, lon:-2.23123396582, wtt:'08:16 / 08:17', publicTime:'08:17', publicExpected:'08:25', demoObserved:'08:24:45', delaySeconds:465 },
  { crs:'HDG', name:'Heald Green', lat:53.36941569079, lon:-2.23666734018, wtt:'08:19½ / 08:20', publicTime:'08:20', publicExpected:'08:28', demoObserved:'08:27:51', delaySeconds:471 },
  { crs:'MIA', name:'Manchester Airport', lat:53.36504152943, lon:-2.27297952138, wtt:'arr 08:25', publicTime:'08:25', publicExpected:'08:33', demoObserved:'08:32:43', delaySeconds:463 },
]

const fmtDelay = (seconds: number) => seconds < 30 ? 'On time' : `+${Math.floor(seconds/60)}m ${seconds%60}s`
const route = calls.map((c) => [c.lat,c.lon] as [number,number])

function FitDemo() {
  const map = useMap()
  useEffect(() => { map.fitBounds(L.latLngBounds(route), { padding:[28,28] }) }, [map])
  return null
}

export default function DemoView({ email, role, onExit, onSignOut }: Props) {
  const [dataset, setDataset] = useState<'capability' | '1N53'>('capability')

  if (dataset === '1N53') {
    return <DemoLiveJourney email={email} role={role} onExit={onExit} onSignOut={onSignOut} onShowCapability={() => setDataset('capability')} />
  }

  const segments = calls.slice(0,-1).map((call,index) => ({
    from:[call.lat,call.lon] as [number,number],
    to:[calls[index+1].lat,calls[index+1].lon] as [number,number],
    delay:calls[index+1].delaySeconds,
  }))

  return (
    <div className="app-shell demo-shell">
      <header className="topbar">
        <div className="brand brand-compact"><img src="./sectioniq-logo.png" alt="SectionIQ" /><div><strong>SectionIQ</strong></div></div>
        <nav><button className="demo-nav-button active" onClick={onExit}>← Live DataView</button><button className="demo-nav-button actual-demo-link" onClick={() => setDataset('1N53')}>1N53 actual live test</button><span className="demo-mode-pill">DEMO MODE</span></nav>
        <div className="user-menu"><span>{email}</span><small>{role}</small><button onClick={onSignOut}>Sign out</button></div>
      </header>

      <main className="content">
        <section className="demo-banner">
          <div><strong>Demonstration dataset</strong><p>Real timetable and public disruption reference, with synthetic SectionIQ GPS, manager and passenger evidence layered on top.</p></div>
          <span>Not operational evidence</span>
        </section>

        <section className="page-heading">
          <div><p className="eyebrow">Northern · 2A11 · 2 September 2026</p><h1>Liverpool Lime Street → Manchester Airport</h1><p>Scheduled 07:00–08:25. Publicly reported +8 minutes at Manchester Oxford Road due to a train fault; formed of 3 coaches instead of 6.</p></div>
          <button className="secondary-button" onClick={onExit}>Return to live data</button>
        </section>

        <section className="metric-grid demo-metrics">
          <article><span>Scheduled journey</span><strong>85m</strong></article>
          <article><span>Public delay</span><strong>+8m</strong></article>
          <article><span>Demo GPS points</span><strong>2,846</strong></article>
          <article><span>Formation</span><strong>3 cars</strong></article>
        </section>

        <section className="panel demo-source-panel">
          <div className="panel-heading"><div><p className="eyebrow">Evidence provenance</p><h2>What is real and what is synthetic</h2></div></div>
          <div className="demo-provenance-grid">
            <div><span className="evidence-chip real">REAL</span><strong>Network Rail WTT</strong><p>2A11 timing points, half-minute WTT detail, station sequence and platforms.</p></div>
            <div><span className="evidence-chip real">REAL</span><strong>Northern public disruption data</strong><p>07:00 service, +8 minutes at Oxford Road, train fault and 3-car formation.</p></div>
            <div><span className="evidence-chip synthetic">SYNTHETIC</span><strong>SectionIQ observed layer</strong><p>Second-level GPS-derived timings, manager notes, passenger distribution and dwell evidence.</p></div>
            <div><span className="evidence-chip reference">REFERENCE</span><strong>Train-ahead context</strong><p>Illustrative WTT proximity examples from the loaded timetable snapshot; not asserted as the cause of this event.</p></div>
          </div>
        </section>

        <section className="panel journey-map-panel demo-map-panel">
          <div className="panel-heading"><div><p className="eyebrow">Performance map</p><h2>Delay development along the route</h2></div><div className="map-legend"><span className="lg green">On time</span><span className="lg amber">1–5 min</span><span className="lg red">5+ min</span></div></div>
          <div className="map-wrap">
            <MapContainer className="journey-map" center={route[0]} zoom={10} scrollWheelZoom preferCanvas>
              <TileLayer attribution='&copy; OpenStreetMap contributors' url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
              {segments.map((s,i) => <Polyline key={i} positions={[s.from,s.to]} pathOptions={{ color:s.delay>300?'#ef4444':s.delay>60?'#f59e0b':'#22c55e', weight:6, opacity:.92 }} />)}
              {calls.map((call) => <CircleMarker key={call.crs} center={[call.lat,call.lon]} radius={4} pathOptions={{ color:'#eaf0f6', weight:1, fillOpacity:.9 }}><Tooltip>{call.name} · {fmtDelay(call.delaySeconds)}</Tooltip></CircleMarker>)}
              <FitDemo />
            </MapContainer>
          </div>
        </section>

        <section className="panel demo-comparison-panel">
          <div className="panel-heading"><div><p className="eyebrow">WTT / public / observed</p><h2>Timing comparison</h2></div></div>
          <div className="table-wrap"><table><thead><tr><th>Location</th><th>WTT</th><th>Public</th><th>Public feed</th><th>SectionIQ demo observed</th><th>Variance</th></tr></thead><tbody>
            {calls.map((call) => <tr key={call.crs}><td><strong>{call.name}</strong><small className="table-sub">{call.crs}</small></td><td>{call.wtt}</td><td>{call.publicTime}</td><td>{call.publicExpected}</td><td>{call.demoObserved}</td><td><span className={`delay-badge ${call.delaySeconds>300?'bad':call.delaySeconds>60?'warn':'good'}`}>{fmtDelay(call.delaySeconds)}</span></td></tr>)}
          </tbody></table></div>
        </section>

        <section className="detail-grid demo-detail-grid">
          <article className="panel">
            <div className="panel-heading"><div><p className="eyebrow">Evidence timeline</p><h2>What SectionIQ could show</h2></div></div>
            <div className="demo-timeline">
              <article><time>07:35</time><div><span className="evidence-chip synthetic">SYNTHETIC</span><strong>Newton-le-Willows dwell</strong><p>Rear third high loading, middle medium, front low. Dwell 46s versus 60s booked.</p></div></article>
              <article><time>07:54</time><div><span className="evidence-chip synthetic">SYNTHETIC</span><strong>Approach to Castlefield</strong><p>Speed falls below expected running profile. No cause inferred from GPS alone.</p></div></article>
              <article><time>08:00</time><div><span className="evidence-chip real">REAL</span><strong>Oxford Road disruption</strong><p>Public feed records the service as 8 minutes late due to a fault on a train.</p></div></article>
              <article><time>08:01</time><div><span className="evidence-chip synthetic">SYNTHETIC</span><strong>Manager observation</strong><p>Extended station stop recorded; no independent cause observed. Official cause retained separately.</p></div></article>
            </div>
          </article>

          <aside className="panel">
            <div className="panel-heading"><div><p className="eyebrow">Train-ahead context</p><h2>Possible interaction evidence</h2></div></div>
            <div className="train-ahead-list">
              <article><strong>Water St Jn → Deansgate</strong><span>1Y98 Northern · booked about 3m30 ahead</span><small>Reference WTT context only</small></article>
              <article><strong>Oxford Road → Piccadilly</strong><span>1Y98 Northern · booked about 4m ahead</span><small>No causal attribution</small></article>
              <article><strong>Piccadilly → Ardwick Jn</strong><span>1V65 CrossCountry · booked about 1m ahead</span><small>Possible interaction flag if observed evidence corroborates</small></article>
            </div>
          </aside>
        </section>

        <section className="panel demo-poap">
          <div className="panel-heading"><div><p className="eyebrow">Performance on a Page</p><h2>Demo summary</h2></div></div>
          <div className="poap-grid"><div><span>Primary delay gain</span><strong>Deansgate → Oxford Road</strong><small>~7m 34s synthetic observed gain</small></div><div><span>Official cause</span><strong>Train fault</strong><small>Imported from public disruption information</small></div><div><span>Passenger evidence</span><strong>Rear-heavy at NLW</strong><small>Synthetic manager observation</small></div><div><span>Evidence discipline</span><strong>No inferred cause from GPS</strong><small>Outcome, mechanism and cause kept separate</small></div></div>
        </section>
      </main>
      <footer className="app-footer">SectionIQ — built by Bodge Job Apps</footer>
    </div>
  )
}
