import { useMemo, useState } from 'react'

type Period = '28' | '60' | 'all'

type CohortRow = {
  date: string
  source: 'actual' | 'modelled'
  chorleyDwell: number
  chorleyExcess: number
  delayGained: number
  delayRecovered: number
  prestonVariance: number
  event: string
}

const SCHEDULE_DAYS = '1111010' // Network Rail G89550 permanent schedule: Mon/Tue/Wed/Thu/Sat.

const seeded = (text: string, salt: number) => {
  let hash = 2166136261 ^ salt
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return ((hash >>> 0) % 10000) / 10000
}

const isG89550ServiceDay = (date: Date) => {
  const jsDay = date.getUTCDay() // Sun=0
  const pythonWeekday = (jsDay + 6) % 7 // Mon=0, matching SectionIQ importer
  return SCHEDULE_DAYS.charAt(pythonWeekday) === '1'
}

const isoDate = (date: Date) => date.toISOString().slice(0, 10)

const comparatorRows = (): CohortRow[] => {
  const rows: CohortRow[] = []
  const start = new Date(Date.UTC(2026, 5, 1))
  const end = new Date(Date.UTC(2026, 7, 31))
  let serviceIndex = 0

  for (let date = new Date(start); date <= end; date.setUTCDate(date.getUTCDate() + 1)) {
    if (!isG89550ServiceDay(date)) continue

    const key = isoDate(date)
    const r1 = seeded(key, 11)
    const r2 = seeded(key, 29)
    const r3 = seeded(key, 47)
    const r4 = seeded(key, 71)

    let event = 'Routine operation'
    let eventPenalty = 0
    if (serviceIndex % 19 === 7) {
      event = 'Accessibility assistance · demo'
      eventPenalty = 105 + Math.round(r1 * 70)
    } else if (serviceIndex % 13 === 5) {
      event = 'Heavy passenger exchange · demo'
      eventPenalty = 35 + Math.round(r1 * 55)
    } else if (serviceIndex % 17 === 9) {
      event = 'Possible signal check · demo'
      eventPenalty = 25 + Math.round(r1 * 60)
    } else if (serviceIndex % 23 === 12) {
      event = 'Extended dwell unexplained · demo'
      eventPenalty = 55 + Math.round(r1 * 75)
    }

    // Synthetic SectionIQ comparator layer. The timetable identity/date pattern is real;
    // these observed values are deliberately modelled and must never be read as TRUST data.
    const routineDwell = 43 + Math.round(r2 * 28)
    const lateSummerDrift = key >= '2026-08-10' ? 8 + Math.round(r3 * 10) : 0
    const chorleyDwell = routineDwell + eventPenalty + lateSummerDrift
    const chorleyExcess = Math.max(0, chorleyDwell - 60)
    const runningLoss = 30 + Math.round(r3 * 95) + (event.indexOf('signal') >= 0 ? 45 : 0)
    const delayGained = Math.max(0, runningLoss + chorleyExcess + Math.round((r4 - 0.35) * 50))
    const recoveryCapacity = 45 + Math.round(r4 * 125)
    const delayRecovered = Math.min(delayGained + 35, recoveryCapacity)
    const startingVariance = -15 + Math.round(r1 * 70)
    const prestonVariance = Math.max(-60, Math.min(360, startingVariance + delayGained - delayRecovered))

    rows.push({
      date: key,
      source: 'modelled',
      chorleyDwell,
      chorleyExcess,
      delayGained,
      delayRecovered,
      prestonVariance,
      event,
    })
    serviceIndex += 1
  }

  rows.push({
    date: '2026-09-01',
    source: 'actual',
    chorleyDwell: 160,
    chorleyExcess: 100,
    delayGained: 207,
    delayRecovered: 200,
    prestonVariance: 11,
    event: 'Actual SectionIQ field test',
  })

  return rows.sort((a, b) => a.date.localeCompare(b.date))
}

const ALL_ROWS = comparatorRows()

const median = (values: number[]) => {
  if (!values.length) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const mid = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[mid] : (sorted[mid - 1] + sorted[mid]) / 2
}

const percentile = (values: number[], p: number) => {
  if (!values.length) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(p * sorted.length) - 1))
  return sorted[index]
}

const fmtSeconds = (seconds: number) => {
  const sign = seconds < 0 ? '−' : ''
  const abs = Math.abs(Math.round(seconds))
  if (abs < 60) return `${sign}${abs}s`
  return `${sign}${Math.floor(abs / 60)}m ${abs % 60}s`
}

const fmtVariance = (seconds: number) => {
  if (Math.abs(seconds) < 15) return 'On time'
  return seconds > 0 ? `+${fmtSeconds(seconds)}` : fmtSeconds(seconds)
}

const displayDate = (value: string) =>
  new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: '2-digit' }).format(
    new Date(`${value}T12:00:00Z`),
  )

export default function Demo1N53Cohort() {
  const [period, setPeriod] = useState<Period>('all')
  const [eventOnly, setEventOnly] = useState(false)

  const visibleRows = useMemo(() => {
    const maxRows = period === '28' ? 28 : period === '60' ? 60 : ALL_ROWS.length
    const sliced = ALL_ROWS.slice(-maxRows)
    return eventOnly ? sliced.filter((row) => row.event !== 'Routine operation') : sliced
  }, [period, eventOnly])

  const modelled = ALL_ROWS.filter((row) => row.source === 'modelled')
  const actual = ALL_ROWS.find((row) => row.source === 'actual')!
  const cohortMedianDwell = median(modelled.map((row) => row.chorleyDwell))
  const cohortP90Dwell = percentile(modelled.map((row) => row.chorleyDwell), 0.9)
  const cohortMedianPreston = median(modelled.map((row) => row.prestonVariance))
  const outlierRate = Math.round((modelled.filter((row) => row.chorleyDwell > 90).length / modelled.length) * 100)

  const recent = modelled.slice(-14)
  const previous = modelled.slice(-28, -14)
  const recentDwell = median(recent.map((row) => row.chorleyDwell))
  const previousDwell = median(previous.map((row) => row.chorleyDwell))
  const dwellTrend = Math.round(recentDwell - previousDwell)
  const recentPreston = median(recent.map((row) => row.prestonVariance))
  const previousPreston = median(previous.map((row) => row.prestonVariance))
  const prestonTrend = Math.round(recentPreston - previousPreston)

  const chartRows = ALL_ROWS
  const chartWidth = 900
  const chartHeight = 180
  const paddingX = 28
  const paddingY = 24
  const minY = -60
  const maxY = 360
  const point = (row: CohortRow, index: number) => {
    const x = paddingX + (index / Math.max(1, chartRows.length - 1)) * (chartWidth - paddingX * 2)
    const y = paddingY + ((maxY - row.prestonVariance) / (maxY - minY)) * (chartHeight - paddingY * 2)
    return { x, y }
  }
  const polyline = chartRows.map((row, index) => {
    const p = point(row, index)
    return `${p.x.toFixed(1)},${p.y.toFixed(1)}`
  }).join(' ')
  const zeroY = paddingY + ((maxY - 0) / (maxY - minY)) * (chartHeight - paddingY * 2)
  const actualPoint = point(actual, chartRows.length - 1)

  return (
    <section className="panel cohort-panel">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Multi-day headcode comparison</p>
          <h2>1N53 · large comparison field</h2>
        </div>
        <span className="cohort-count">{ALL_ROWS.length} service-day records</span>
      </div>

      <div className="cohort-provenance">
        <strong>Evidence boundary:</strong> 1 September is genuine SectionIQ field evidence. The remaining {modelled.length} rows use the real Network Rail G89550 timetable identity and service-day pattern, with synthetic observed performance layered on top so the trend-analysis interface can be demonstrated at realistic scale. They are not represented as historical TRUST or GPS actuals.
      </div>

      <div className="cohort-metrics">
        <article><span>Comparator journeys</span><strong>{ALL_ROWS.length}</strong><small>Same 1N53 / G89550 timetable family</small></article>
        <article><span>Modelled Chorley median</span><strong>{fmtSeconds(cohortMedianDwell)}</strong><small>P90 {fmtSeconds(cohortP90Dwell)}</small></article>
        <article><span>Modelled Preston median</span><strong>{fmtVariance(cohortMedianPreston)}</strong><small>Arrival variance</small></article>
        <article><span>Extended dwell rate</span><strong>{outlierRate}%</strong><small>&gt;90 seconds in demo cohort</small></article>
      </div>

      <div className="cohort-chart-card">
        <div className="cohort-chart-heading">
          <div><strong>Preston arrival variance across service days</strong><small>Modelled comparators · actual 1 Sep highlighted</small></div>
          <div className="cohort-legend"><span><i className="model-dot" /> Modelled</span><span><i className="actual-dot" /> Actual SectionIQ</span></div>
        </div>
        <div className="cohort-chart-scroll">
          <svg viewBox={`0 0 ${chartWidth} ${chartHeight}`} role="img" aria-label="1N53 Preston arrival variance trend">
            <line x1={paddingX} x2={chartWidth - paddingX} y1={zeroY} y2={zeroY} className="cohort-zero" />
            <polyline points={polyline} className="cohort-line" />
            {chartRows.map((row, index) => {
              const p = point(row, index)
              return <circle key={row.date} cx={p.x} cy={p.y} r={row.source === 'actual' ? 6 : 2.8} className={row.source === 'actual' ? 'cohort-point actual' : 'cohort-point'} />
            })}
            <text x={paddingX + 4} y={zeroY - 6} className="cohort-axis-label">On time</text>
            <text x={actualPoint.x - 50} y={Math.max(18, actualPoint.y - 12)} className="cohort-actual-label">1 Sep actual +11s</text>
          </svg>
        </div>
      </div>

      <div className="ai-cohort-panel">
        <div className="ai-cohort-heading"><span className="ai-badge">AI trend analysis</span><strong>What SectionIQ should surface automatically</strong></div>
        <div className="ai-cohort-grid">
          <article>
            <span>Routine trend</span>
            <strong>{dwellTrend > 0 ? `Chorley dwell +${dwellTrend}s` : `Chorley dwell ${dwellTrend}s`}</strong>
            <p>Recent 14 modelled journeys versus the previous 14. The system separates a shift in routine behaviour from isolated exceptions.</p>
          </article>
          <article>
            <span>Arrival trend</span>
            <strong>{prestonTrend > 0 ? `Preston +${prestonTrend}s` : `Preston ${prestonTrend}s`}</strong>
            <p>Median arrival variance change in the modelled cohort. A real deployment would calculate this from matched actual journeys only.</p>
          </article>
          <article className="actual-insight">
            <span>1 Sep actual exception</span>
            <strong>Chorley 2m40 dwell</strong>
            <p>The actual field run is materially above the modelled routine median, but arrival at Preston was only +11s because substantial recovery followed.</p>
          </article>
        </div>
        <p className="ai-cohort-method"><strong>Decision-support rule:</strong> flag the change, quantify its size and frequency, show the evidence, then suggest investigation. Do not convert an association into a delay cause without corroborating evidence.</p>
      </div>

      <div className="cohort-toolbar">
        <div className="cohort-periods">
          <button className={period === '28' ? 'active' : ''} onClick={() => setPeriod('28')}>Last 28</button>
          <button className={period === '60' ? 'active' : ''} onClick={() => setPeriod('60')}>Last 60</button>
          <button className={period === 'all' ? 'active' : ''} onClick={() => setPeriod('all')}>All {ALL_ROWS.length}</button>
        </div>
        <label><input type="checkbox" checked={eventOnly} onChange={(e) => setEventOnly(e.target.checked)} /> Exceptions only</label>
      </div>

      <div className="table-wrap cohort-table-wrap">
        <table>
          <thead><tr><th>Date</th><th>Evidence</th><th>Chorley dwell</th><th>Excess dwell</th><th>Delay gained</th><th>Recovery</th><th>Preston</th><th>Context</th></tr></thead>
          <tbody>
            {[...visibleRows].reverse().map((row) => (
              <tr key={row.date} className={row.source === 'actual' ? 'actual-cohort-row' : ''}>
                <td><strong>{displayDate(row.date)}</strong></td>
                <td>{row.source === 'actual' ? <span className="evidence-chip real">ACTUAL</span> : <span className="evidence-chip synthetic">MODELLED</span>}</td>
                <td>{fmtSeconds(row.chorleyDwell)}</td>
                <td>{row.chorleyExcess ? `+${fmtSeconds(row.chorleyExcess)}` : '—'}</td>
                <td>+{fmtSeconds(row.delayGained)}</td>
                <td>−{fmtSeconds(row.delayRecovered)}</td>
                <td><span className={`delay-badge ${row.prestonVariance >= 180 ? 'bad' : row.prestonVariance >= 60 ? 'warn' : 'good'}`}>{fmtVariance(row.prestonVariance)}</span></td>
                <td>{row.event}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </section>
  )
}
