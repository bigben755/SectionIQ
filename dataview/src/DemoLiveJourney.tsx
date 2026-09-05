import { useEffect, useMemo, useState } from 'react'
import L from 'leaflet'
import { CircleMarker, MapContainer, Polyline, TileLayer, Tooltip, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import { supabase } from './supabase'

type Props = {
  email: string
  role: string | null
  onExit: () => void
  onSignOut: () => void
  onShowCapability: () => void
}

type JourneyPoint = {
  sequence_number: number
  recorded_at: string
  latitude: number
  longitude: number
  accuracy_m: number | null
  speed_mps: number | null
}

type ServiceCall = {
  id: number
  call_sequence: number
  location_code: string | null
  location_name: string | null
  scheduled_arrival: string | null
  scheduled_departure: string | null
  observed_arrival: string | null
  observed_departure: string | null
  observed_latitude: number | null
  observed_longitude: number | null
  observed_accuracy_m: number | null
  detection_confidence: number | null
  notes: string | null
}

type PerformanceEvent = {
  id: string
  event_type: string
  location_name: string | null
  summary: string | null
  duration_seconds: number | null
  estimated_impact_seconds: number | null
  confidence: number | null
  evidence: Record<string, unknown> | null
}

const JOURNEY_ID = 'a3213d02-2230-40c6-9e92-efbc6988fca3'
const MATCH_ID = '09c718fc-88f3-4568-8f46-6e448cd1aef3'

type LatLngTuple = [number, number]

function FitActual({ positions }: { positions: LatLngTuple[] }) {
  const map = useMap()
  useEffect(() => {
    if (!positions.length) return
    if (positions.length === 1) {
      map.setView(positions[0], 15)
      return
    }
    map.fitBounds(L.latLngBounds(positions), { padding: [28, 28] })
  }, [map, positions])
  return null
}

const timeFormatter = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Europe/London',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hour12: false,
})

const fmtTime = (value: string | null) => value ? timeFormatter.format(new Date(value)) : '—'

const fmtDurationSeconds = (seconds: number | null) => {
  if (seconds == null) return '—'
  const whole = Math.max(0, Math.round(seconds))
  const minutes = Math.floor(whole / 60)
  const remainder = whole % 60
  return minutes ? `${minutes}m ${remainder}s` : `${remainder}s`
}

const secondsBetween = (start: string | null, end: string | null) => {
  if (!start || !end) return null
  return Math.round((Date.parse(end) - Date.parse(start)) / 1000)
}

const callDelay = (call: ServiceCall) => {
  if (call.observed_departure && call.scheduled_departure) {
    return secondsBetween(call.scheduled_departure, call.observed_departure)
  }
  if (call.observed_arrival && call.scheduled_arrival) {
    return secondsBetween(call.scheduled_arrival, call.observed_arrival)
  }
  return null
}

const fmtVariance = (seconds: number | null) => {
  if (seconds == null) return '—'
  const abs = Math.abs(seconds)
  const mins = Math.floor(abs / 60)
  const secs = abs % 60
  if (abs < 30) return `${seconds >= 0 ? '+' : '−'}${secs}s`
  return `${seconds >= 0 ? '+' : '−'}${mins}m ${secs}s`
}

const varianceClass = (seconds: number | null) => {
  if (seconds == null || seconds < 30) return 'good'
  if (seconds < 180) return 'warn'
  return 'bad'
}

export default function DemoLiveJourney({ email, role, onExit, onSignOut, onShowCapability }: Props) {
  const [points, setPoints] = useState<JourneyPoint[]>([])
  const [calls, setCalls] = useState<ServiceCall[]>([])
  const [events, setEvents] = useState<PerformanceEvent[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false

    const load = async () => {
      setLoading(true)
      setError('')

      const [callsResult, eventsResult] = await Promise.all([
        supabase
          .from('journey_service_calls')
          .select('id,call_sequence,location_code,location_name,scheduled_arrival,scheduled_departure,observed_arrival,observed_departure,observed_latitude,observed_longitude,observed_accuracy_m,detection_confidence,notes')
          .eq('match_id', MATCH_ID)
          .order('call_sequence', { ascending: true }),
        supabase
          .from('journey_performance_events')
          .select('id,event_type,location_name,summary,duration_seconds,estimated_impact_seconds,confidence,evidence')
          .eq('journey_id', JOURNEY_ID)
          .order('started_at', { ascending: true }),
      ])

      if (callsResult.error || eventsResult.error) {
        if (!cancelled) {
          setError(callsResult.error?.message ?? eventsResult.error?.message ?? 'Could not load 1N53 evidence.')
          setLoading(false)
        }
        return
      }

      const allPoints: JourneyPoint[] = []
      const pageSize = 1000
      let from = 0

      while (!cancelled) {
        const { data, error: pointsError } = await supabase
          .from('journey_points')
          .select('sequence_number,recorded_at,latitude,longitude,accuracy_m,speed_mps')
          .eq('journey_id', JOURNEY_ID)
          .order('sequence_number', { ascending: true })
          .range(from, from + pageSize - 1)

        if (pointsError) {
          setError(pointsError.message)
          setLoading(false)
          return
        }

        const page = (data ?? []) as JourneyPoint[]
        allPoints.push(...page)
        if (page.length < pageSize) break
        from += pageSize
      }

      if (!cancelled) {
        setCalls((callsResult.data ?? []) as ServiceCall[])
        setEvents((eventsResult.data ?? []) as PerformanceEvent[])
        setPoints(allPoints)
        setLoading(false)
      }
    }

    void load()
    return () => { cancelled = true }
  }, [])

  const positions = useMemo<LatLngTuple[]>(() => points
    .filter((p) => Number.isFinite(p.latitude) && Number.isFinite(p.longitude))
    .map((p) => [p.latitude, p.longitude]), [points])

  const callMarkers = useMemo(() => calls.filter((call) =>
    call.observed_latitude != null && call.observed_longitude != null
  ), [calls])

  const chorley = calls.find((call) => call.location_code === 'CRL')
  const preston = calls.find((call) => call.location_code === 'PRE')
  const chorleyDwell = chorley ? secondsBetween(chorley.observed_arrival, chorley.observed_departure) : null
  const prestonVariance = preston ? callDelay(preston) : null
  const recoveryEvents = events.filter((event) => event.event_type === 'section_recovery')
  const totalRecovery = recoveryEvents.reduce((sum, event) => sum + Math.abs(event.estimated_impact_seconds ?? 0), 0)

  return (
    <div className="app-shell demo-shell actual-demo-shell">
      <header className="topbar">
        <div className="brand brand-compact"><img src="./sectioniq-logo.png" alt="SectionIQ" /><div><strong>SectionIQ</strong></div></div>
        <nav>
          <button className="demo-nav-button" onClick={onExit}>← Live DataView</button>
          <button className="demo-nav-button" onClick={onShowCapability}>2A11 capability demo</button>
          <span className="demo-mode-pill actual">ACTUAL LIVE TEST</span>
        </nav>
        <div className="user-menu"><span>{email}</span><small>{role}</small><button onClick={onSignOut}>Sign out</button></div>
      </header>

      <main className="content">
        <section className="demo-banner actual-demo-banner">
          <div>
            <strong>Real SectionIQ live-test journey</strong>
            <p>This view uses the genuine GPS trace and detected station calls recorded during the 1N53 field test on 1 September 2026. No synthetic GPS or dwell timings are used on this page.</p>
          </div>
          <span>Operational test evidence</span>
        </section>

        <section className="page-heading">
          <div>
            <p className="eyebrow">Northern · 1N53 · 1 September 2026</p>
            <h1>Manchester Piccadilly → Preston</h1>
            <p>SectionIQ captured the middle portion of the Manchester Airport → Blackpool North service. The recorded test segment ran from Manchester Piccadilly to Preston.</p>
          </div>
          <div className="heading-actions">
            <button className="secondary-button" onClick={onShowCapability}>Show capability demo</button>
            <button className="secondary-button" onClick={onExit}>Return to live data</button>
          </div>
        </section>

        <section className="metric-grid demo-metrics actual-demo-metrics">
          <article><span>Actual GPS points</span><strong>{loading ? '…' : points.length.toLocaleString('en-GB')}</strong></article>
          <article><span>Chorley observed dwell</span><strong>{loading ? '…' : fmtDurationSeconds(chorleyDwell)}</strong></article>
          <article><span>Recovery after delay</span><strong>{loading ? '…' : `~${fmtDurationSeconds(totalRecovery)}`}</strong></article>
          <article><span>Preston arrival</span><strong>{loading ? '…' : fmtVariance(prestonVariance)}</strong></article>
        </section>

        <section className="panel demo-source-panel">
          <div className="panel-heading"><div><p className="eyebrow">Evidence provenance</p><h2>Why this journey matters</h2></div></div>
          <div className="demo-provenance-grid">
            <div><span className="evidence-chip real">REAL</span><strong>SectionIQ GPS</strong><p>1,548 recorded location points from the field test, uploaded by the Android collector.</p></div>
            <div><span className="evidence-chip real">REAL</span><strong>Detected station calls</strong><p>High-confidence stationary islands identify calls from Deansgate through to Preston, with confidence retained per call.</p></div>
            <div><span className="evidence-chip real">REAL</span><strong>Matched service</strong><p>1N53 Northern matched at 0.99 confidence using GPS calling pattern evidence and contemporary public reference data.</p></div>
            <div><span className="evidence-chip reference">LIMITATION</span><strong>Legacy test match</strong><p>This journey predates the current WTT schedule-ID matching pipeline, so it has no Network Rail schedule UUID attached. Timings shown are the matched test record retained on 1 September.</p></div>
          </div>
        </section>

        {error && <div className="error-banner">{error}</div>}

        <section className="panel journey-map-panel demo-map-panel">
          <div className="panel-heading">
            <div><p className="eyebrow">Actual recorded route</p><h2>1N53 GPS trace</h2></div>
            <div className="map-meta">{loading ? 'Loading field evidence…' : `${positions.length.toLocaleString('en-GB')} GPS points`}</div>
          </div>
          {loading ? (
            <div className="map-loading">Loading actual 1N53 journey…</div>
          ) : positions.length ? (
            <div className="map-wrap">
              <MapContainer className="journey-map" center={positions[0]} zoom={10} scrollWheelZoom preferCanvas>
                <TileLayer attribution='&copy; OpenStreetMap contributors' url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png" />
                <Polyline positions={positions} pathOptions={{ color: '#22d3ee', weight: 5, opacity: .9 }} />
                {callMarkers.map((call) => {
                  const delay = callDelay(call)
                  const colour = delay != null && delay >= 180 ? '#ef4444' : delay != null && delay >= 60 ? '#f59e0b' : '#22c55e'
                  return (
                    <CircleMarker key={call.id} center={[call.observed_latitude!, call.observed_longitude!]} radius={6} pathOptions={{ color: colour, fillColor: colour, fillOpacity: .9, weight: 2 }}>
                      <Tooltip>{call.location_name} · {fmtVariance(delay)}</Tooltip>
                    </CircleMarker>
                  )
                })}
                <FitActual positions={positions} />
              </MapContainer>
            </div>
          ) : (
            <div className="empty-panel">No GPS points are available.</div>
          )}
        </section>

        <section className="panel demo-comparison-panel">
          <div className="panel-heading"><div><p className="eyebrow">Actual station evidence</p><h2>Detected calls and dwell</h2></div><div className="map-meta">Times shown in British local time</div></div>
          <div className="table-wrap"><table><thead><tr><th>Location</th><th>Booked arr</th><th>Observed arr</th><th>Booked dep</th><th>Observed dep</th><th>Dwell</th><th>Variance</th></tr></thead><tbody>
            {calls.map((call) => {
              const dwell = secondsBetween(call.observed_arrival, call.observed_departure)
              const variance = callDelay(call)
              return <tr key={call.id}>
                <td><strong>{call.location_name ?? call.location_code ?? 'Unknown'}</strong><small className="table-sub">{call.location_code ?? '—'} · confidence {call.detection_confidence != null ? `${Math.round(call.detection_confidence * 100)}%` : '—'}</small></td>
                <td>{fmtTime(call.scheduled_arrival)}</td>
                <td>{fmtTime(call.observed_arrival)}</td>
                <td>{fmtTime(call.scheduled_departure)}</td>
                <td>{fmtTime(call.observed_departure)}</td>
                <td>{fmtDurationSeconds(dwell)}</td>
                <td><span className={`delay-badge ${varianceClass(variance)}`}>{fmtVariance(variance)}</span></td>
              </tr>
            })}
          </tbody></table></div>
        </section>

        <section className="detail-grid demo-detail-grid actual-evidence-grid">
          <article className="panel">
            <div className="panel-heading"><div><p className="eyebrow">Delay understanding</p><h2>What the actual journey shows</h2></div></div>
            <div className="demo-timeline">
              <article><time>17:42</time><div><span className="evidence-chip real">REAL</span><strong>Chorley dwell exception</strong><p>Observed arrival 17:42:17, departure 17:44:57. Dwell 2m40s against a 60s matched booked dwell, adding about 100 seconds of lateness.</p></div></article>
              <article><time>17:48</time><div><span className="evidence-chip real">REAL</span><strong>Buckshaw Parkway → Leyland recovery</strong><p>SectionIQ calculated approximately 2m32s recovery against the matched timetable over this section.</p></div></article>
              <article><time>17:53</time><div><span className="evidence-chip real">REAL</span><strong>Leyland → Preston recovery</strong><p>A further 48 seconds was recovered between Leyland departure and Preston arrival.</p></div></article>
              <article><time>17:58</time><div><span className="evidence-chip real">REAL</span><strong>Preston result</strong><p>Arrival was observed at 17:58:11, approximately 11 seconds after the matched 17:58 arrival time.</p></div></article>
            </div>
          </article>

          <aside className="panel">
            <div className="panel-heading"><div><p className="eyebrow">Recorded performance events</p><h2>Machine-generated evidence</h2></div></div>
            <div className="train-ahead-list">
              {events.map((event) => <article key={event.id}>
                <strong>{event.location_name ?? event.event_type.replace(/_/g, ' ')}</strong>
                <span>{event.summary ?? 'Recorded SectionIQ performance event'}</span>
                <small>Confidence {event.confidence != null ? `${Math.round(event.confidence * 100)}%` : '—'}{event.estimated_impact_seconds != null ? ` · impact ${fmtVariance(event.estimated_impact_seconds)}` : ''}</small>
              </article>)}
            </div>
          </aside>
        </section>

        <section className="panel demo-poap actual-poap">
          <div className="panel-heading"><div><p className="eyebrow">Performance on a page</p><h2>1N53 field-test conclusion</h2></div></div>
          <div className="poap-grid">
            <div><span>Largest identified loss</span><strong>Chorley dwell</strong><small>+100s estimated impact</small></div>
            <div><span>Strongest recovery</span><strong>Buckshaw → Leyland</strong><small>−2m32 against matched timing</small></div>
            <div><span>Final observed position</span><strong>Preston +11s</strong><small>Almost all identified lateness recovered</small></div>
            <div><span>Evidence quality</span><strong>High</strong><small>GPS call confidence up to 99%</small></div>
          </div>
          <div className="ai-method-note"><strong>SectionIQ interpretation:</strong> this field test demonstrates why delay gained and delay recovered must be analysed separately. A pronounced dwell exception at Chorley was followed by substantial recovery before Preston. The data supports the mechanism — extended dwell — but does not by itself establish the underlying cause of the Chorley stop.</div>
        </section>
      </main>

      <footer className="app-footer">SectionIQ — built by Bodge Job Apps</footer>
    </div>
  )
}
