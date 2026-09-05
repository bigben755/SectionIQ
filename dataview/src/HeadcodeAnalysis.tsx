import { useEffect, useMemo, useState } from 'react'
import { supabase } from './supabase'

type JourneySummary = {
  journey_id: string
  started_at: string
  service_date: string
  headcode: string
  operator_code: string | null
  origin_name: string | null
  destination_name: string | null
  route_fingerprint: string | null
  call_count: number
  initial_delay_seconds: number | null
  final_delay_seconds: number | null
  delay_gained_seconds: number
  delay_recovered_seconds: number
  duration_seconds: number
}

type DwellSummary = {
  location_code: string
  location_name: string
  sample_count: number
  median_dwell_seconds: number | null
  p90_dwell_seconds: number | null
  median_booked_dwell_seconds: number | null
  median_excess_seconds: number | null
  max_dwell_seconds: number | null
  extended_dwell_count: number
}

type SectionSummary = {
  from_code: string
  from_name: string
  to_code: string
  to_name: string
  sample_count: number
  median_running_seconds: number | null
  median_booked_seconds: number | null
  median_excess_seconds: number | null
  p90_excess_seconds: number | null
  loss_count: number
  recovery_count: number
}

const fmtSeconds = (value: number | null | undefined, signed = false) => {
  if (value == null || Number.isNaN(Number(value))) return '—'
  const raw = Math.round(Number(value))
  const sign = raw > 0 && signed ? '+' : raw < 0 ? '−' : ''
  const abs = Math.abs(raw)
  const minutes = Math.floor(abs / 60)
  const seconds = abs % 60
  return minutes ? `${sign}${minutes}m ${String(seconds).padStart(2, '0')}s` : `${sign}${seconds}s`
}

const median = (values: number[]) => {
  if (!values.length) return null
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

const mean = (values: number[]) => values.length ? values.reduce((a, b) => a + b, 0) / values.length : null

function insightTone(delta: number) {
  if (delta >= 30) return 'bad'
  if (delta >= 10) return 'warn'
  if (delta <= -15) return 'good'
  return 'neutral'
}

export default function HeadcodeAnalysis({ onBack }: { onBack: () => void }) {
  const [headcode, setHeadcode] = useState('2A20')
  const [days, setDays] = useState(28)
  const [journeys, setJourneys] = useState<JourneySummary[]>([])
  const [dwells, setDwells] = useState<DwellSummary[]>([])
  const [sections, setSections] = useState<SectionSummary[]>([])
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')

  const runAnalysis = async () => {
    const code = headcode.trim().toUpperCase()
    if (!code) return
    setLoading(true)
    setMessage('')
    const [journeyResult, dwellResult, sectionResult] = await Promise.all([
      supabase.rpc('sectioniq_headcode_journeys', { p_headcode: code, p_days: days }),
      supabase.rpc('sectioniq_headcode_dwell', { p_headcode: code, p_days: days }),
      supabase.rpc('sectioniq_headcode_sections', { p_headcode: code, p_days: days }),
    ])
    const firstError = journeyResult.error ?? dwellResult.error ?? sectionResult.error
    if (firstError) {
      setMessage(firstError.message)
      setJourneys([])
      setDwells([])
      setSections([])
    } else {
      setJourneys((journeyResult.data ?? []) as JourneySummary[])
      setDwells((dwellResult.data ?? []) as DwellSummary[])
      setSections((sectionResult.data ?? []) as SectionSummary[])
    }
    setLoading(false)
  }

  useEffect(() => { void runAnalysis() }, [])

  const routeGroups = useMemo(() => {
    const groups = new Map<string, JourneySummary[]>()
    journeys.forEach((journey) => {
      const key = journey.route_fingerprint ?? 'unresolved'
      groups.set(key, [...(groups.get(key) ?? []), journey])
    })
    return [...groups.entries()].sort((a, b) => b[1].length - a[1].length)
  }, [journeys])

  const primaryRoute = routeGroups[0]?.[1] ?? journeys
  const finalDelays = primaryRoute.map((j) => Number(j.final_delay_seconds)).filter(Number.isFinite)
  const gained = primaryRoute.map((j) => Number(j.delay_gained_seconds)).filter(Number.isFinite)
  const recovered = primaryRoute.map((j) => Number(j.delay_recovered_seconds)).filter(Number.isFinite)
  const medianFinal = median(finalDelays)
  const medianGain = median(gained)
  const medianRecovery = median(recovered)

  const worstSection = useMemo(
    () => [...sections].filter((s) => s.median_excess_seconds != null).sort((a, b) => Number(b.median_excess_seconds) - Number(a.median_excess_seconds))[0],
    [sections],
  )
  const worstDwell = useMemo(
    () => [...dwells].filter((d) => d.median_excess_seconds != null).sort((a, b) => Number(b.median_excess_seconds) - Number(a.median_excess_seconds))[0],
    [dwells],
  )

  const trend = useMemo(() => {
    const comparable = [...primaryRoute].sort((a, b) => Date.parse(a.started_at) - Date.parse(b.started_at))
    if (comparable.length < 4) return null
    const split = Math.floor(comparable.length / 2)
    const earlier = comparable.slice(0, split).map(j => Number(j.final_delay_seconds)).filter(Number.isFinite)
    const recent = comparable.slice(split).map(j => Number(j.final_delay_seconds)).filter(Number.isFinite)
    const earlierMean = mean(earlier)
    const recentMean = mean(recent)
    if (earlierMean == null || recentMean == null) return null
    return { earlierMean, recentMean, change: recentMean - earlierMean }
  }, [primaryRoute])

  const origin = primaryRoute[0]?.origin_name ?? 'Route unresolved'
  const destination = primaryRoute[0]?.destination_name ?? ''
  const operator = primaryRoute[0]?.operator_code ?? '—'

  return (
    <main className="content headcode-analysis-page">
      <section className="page-heading">
        <div>
          <p className="eyebrow">Multi-journey intelligence</p>
          <h1>Headcode Analysis</h1>
          <p>Collate comparable journeys, benchmark dwell and running performance, and surface evidence-led trends.</p>
        </div>
        <button className="secondary-button" onClick={onBack}>Back to journeys</button>
      </section>

      <section className="panel headcode-controls">
        <div className="analysis-controls">
          <label>Headcode<input value={headcode} maxLength={4} onChange={(e) => setHeadcode(e.target.value.toUpperCase())} /></label>
          <label>Analysis period<select value={days} onChange={(e) => setDays(Number(e.target.value))}><option value={7}>Last 7 days</option><option value={28}>Last 28 days</option><option value={90}>Last 90 days</option><option value={180}>Last 180 days</option></select></label>
          <button className="primary-button" disabled={loading} onClick={runAnalysis}>{loading ? 'Analysing…' : 'Analyse headcode'}</button>
        </div>
      </section>

      {message && <div className="error-banner">{message}</div>}

      <section className="analysis-route-title">
        <div><span>{operator}</span><strong>{headcode.toUpperCase()}</strong></div>
        <h2>{origin}{destination ? ` → ${destination}` : ''}</h2>
        {routeGroups.length > 1 && <p>{routeGroups.length} route fingerprints detected. Statistics below prioritise the most common comparable route ({primaryRoute.length} journeys).</p>}
      </section>

      <section className="metric-grid headcode-metrics">
        <article><span>Comparable journeys</span><strong>{primaryRoute.length}</strong></article>
        <article><span>Median final delay</span><strong>{fmtSeconds(medianFinal, true)}</strong></article>
        <article><span>Median delay gained</span><strong>{fmtSeconds(medianGain)}</strong></article>
        <article><span>Median recovery</span><strong>{fmtSeconds(medianRecovery)}</strong></article>
      </section>

      <section className="panel ai-trend-panel">
        <div className="panel-heading"><div><p className="eyebrow">Evidence-led AI</p><h2>AI Trend Analysis</h2></div><span className="ai-badge">Explainable</span></div>
        {primaryRoute.length < 3 ? (
          <div className="empty-panel">Trend analysis will activate as comparable journeys accumulate. At least three journeys are recommended; four or more enables period-change analysis.</div>
        ) : (
          <div className="trend-body">
            <article className={`trend-lead ${trend ? insightTone(trend.change) : 'neutral'}`}>
              <span>Headline</span>
              <strong>{trend ? (trend.change >= 30 ? 'Performance deterioration detected' : trend.change <= -30 ? 'Performance improvement detected' : 'Arrival performance broadly stable') : 'Baseline established'}</strong>
              <p>{trend ? `Recent journeys average ${fmtSeconds(trend.change, true)} different at destination compared with the earlier half of this ${days}-day sample.` : `SectionIQ has enough journeys to establish an initial ${headcode.toUpperCase()} baseline.`}</p>
            </article>
            <div className="trend-grid">
              <article><span>Largest recurring running opportunity</span><strong>{worstSection ? `${worstSection.from_name} → ${worstSection.to_name}` : 'Insufficient section evidence'}</strong><p>{worstSection ? `Median excess running time ${fmtSeconds(worstSection.median_excess_seconds, true)} across ${worstSection.sample_count} comparable runs.` : 'More matched service calls are required.'}</p></article>
              <article><span>Largest recurring dwell opportunity</span><strong>{worstDwell?.location_name ?? 'Insufficient dwell evidence'}</strong><p>{worstDwell ? `Median excess dwell ${fmtSeconds(worstDwell.median_excess_seconds, true)}; ${worstDwell.extended_dwell_count} extended dwell exceptions.` : 'More observed station calls are required.'}</p></article>
              <article><span>Decision prompt</span><strong>{worstSection || worstDwell ? 'Investigate the repeated pattern before individual outliers' : 'Continue evidence collection'}</strong><p>SectionIQ separates recurring performance from exceptional events such as accessibility assistance, passenger loading or train-ahead interaction.</p></article>
            </div>
            <p className="ai-method-note"><strong>Method:</strong> statistical comparison first, operational explanation second. SectionIQ does not infer causation from correlation; every trend must remain traceable to the underlying journeys and observations.</p>
          </div>
        )}
      </section>

      <section className="analysis-two-column">
        <section className="panel">
          <div className="panel-heading"><div><p className="eyebrow">Station working</p><h2>Dwell comparison</h2></div></div>
          <div className="table-wrap"><table><thead><tr><th>Station</th><th>Samples</th><th>Median</th><th>Booked</th><th>Excess</th><th>P90</th><th>Exceptions</th></tr></thead><tbody>
            {dwells.map(d => <tr key={d.location_code}><td><strong>{d.location_name}</strong><small className="table-sub">{d.location_code}</small></td><td>{d.sample_count}</td><td>{fmtSeconds(d.median_dwell_seconds)}</td><td>{fmtSeconds(d.median_booked_dwell_seconds)}</td><td><span className={`delay-badge ${Number(d.median_excess_seconds) > 30 ? 'bad' : Number(d.median_excess_seconds) > 10 ? 'warn' : 'good'}`}>{fmtSeconds(d.median_excess_seconds, true)}</span></td><td>{fmtSeconds(d.p90_dwell_seconds)}</td><td>{d.extended_dwell_count}</td></tr>)}
            {!dwells.length && <tr><td colSpan={7} className="empty-cell">No matched dwell evidence yet.</td></tr>}
          </tbody></table></div>
        </section>

        <section className="panel">
          <div className="panel-heading"><div><p className="eyebrow">Route performance</p><h2>Section comparison</h2></div></div>
          <div className="table-wrap"><table><thead><tr><th>Section</th><th>Samples</th><th>Observed</th><th>Booked</th><th>Median excess</th><th>P90 excess</th></tr></thead><tbody>
            {sections.map(s => <tr key={`${s.from_code}-${s.to_code}`}><td><strong>{s.from_name} → {s.to_name}</strong></td><td>{s.sample_count}</td><td>{fmtSeconds(s.median_running_seconds)}</td><td>{fmtSeconds(s.median_booked_seconds)}</td><td><span className={`delay-badge ${Number(s.median_excess_seconds) > 30 ? 'bad' : Number(s.median_excess_seconds) > 10 ? 'warn' : 'good'}`}>{fmtSeconds(s.median_excess_seconds, true)}</span></td><td>{fmtSeconds(s.p90_excess_seconds, true)}</td></tr>)}
            {!sections.length && <tr><td colSpan={6} className="empty-cell">No matched section evidence yet.</td></tr>}
          </tbody></table></div>
        </section>
      </section>

      <section className="panel journey-comparison-panel">
        <div className="panel-heading"><div><p className="eyebrow">Comparable evidence</p><h2>Journey comparison</h2></div></div>
        <div className="table-wrap"><table><thead><tr><th>Date</th><th>Route</th><th>Calls</th><th>Start delay</th><th>Final delay</th><th>Gained</th><th>Recovered</th></tr></thead><tbody>
          {primaryRoute.map(j => <tr key={j.journey_id}><td>{new Intl.DateTimeFormat('en-GB',{day:'2-digit',month:'short',year:'numeric'}).format(new Date(j.started_at))}</td><td>{j.origin_name ?? '—'} → {j.destination_name ?? '—'}</td><td>{j.call_count}</td><td>{fmtSeconds(j.initial_delay_seconds,true)}</td><td>{fmtSeconds(j.final_delay_seconds,true)}</td><td>{fmtSeconds(j.delay_gained_seconds)}</td><td>{fmtSeconds(j.delay_recovered_seconds)}</td></tr>)}
          {!primaryRoute.length && <tr><td colSpan={7} className="empty-cell">No comparable matched journeys found for this headcode and period.</td></tr>}
        </tbody></table></div>
      </section>
    </main>
  )
}
