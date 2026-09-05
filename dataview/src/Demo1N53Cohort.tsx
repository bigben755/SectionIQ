import { useMemo, useState } from 'react'

type CohortRow = {
  date: string
  source: 'actual' | 'modelled'
  chorleyDwell: number
  delayGained: number
  delayRecovered: number
  prestonVariance: number
  event: string
}

const SCHEDULE_DAYS = '1111010'

const seeded = (text: string, salt: number) => {
  let hash = 2166136261 ^ salt
  for (let i = 0; i < text.length; i += 1) {
    hash ^= text.charCodeAt(i)
    hash = Math.imul(hash, 16777619)
  }
  return ((hash >>> 0) % 10000) / 10000
}

const isServiceDay = (date: Date) => {
  const jsDay = date.getUTCDay()
  const mondayFirst = (jsDay + 6) % 7
  return SCHEDULE_DAYS.charAt(mondayFirst) === '1'
}

const isoDate = (date: Date) => date.toISOString().slice(0, 10)

const buildRows = (): CohortRow[] => {
  const rows: CohortRow[] = []
  const start = new Date(Date.UTC(2026, 5, 1))
  const end = new Date(Date.UTC(2026, 7, 31))
  let serviceIndex = 0

  for (let date = new Date(start); date <= end; date.setUTCDate(date.getUTCDate() + 1)) {
    if (!isServiceDay(date)) continue

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

    const routineDwell = 43 + Math.round(r2 * 28)
    const lateSummerDrift = key >= '2026-08-10' ? 8 + Math.round(r3 * 10) : 0
    const chorleyDwell = routineDwell + eventPenalty + lateSummerDrift
    const chorleyExcess = Math.max(0, chorleyDwell - 60)
    const runningLoss = 30 + Math.round(r3 * 95) + (event.indexOf('signal') >= 0 ? 45 : 0)
    const delayGained = Math.max(0, runningLoss + chorleyExcess + Math.round((r4 - 0.35) * 50))
    const delayRecovered = Math.min(delayGained + 35, 45 + Math.round(r4 * 125))
    const startingVariance = -15 + Math.round(r1 * 70)
    const prestonVariance = Math.max(-60, Math.min(360, startingVariance + delayGained - delayRecovered))

    rows.push({ date: key, source: 'modelled', chorleyDwell, delayGained, delayRecovered, prestonVariance, event })
    serviceIndex += 1
  }

  rows.push({
    date: '2026-09-01',
    source: 'actual',
    chorleyDwell: 160,
    delayGained: 207,
    delayRecovered: 200,
    prestonVariance: 11,
    event: 'Actual SectionIQ field test',
  })

  return rows.sort((a, b) => a.date.localeCompare(b.date))
}

const ALL_ROWS = buildRows()

const median = (values: number[]) => {
  if (!values.length) return 0
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2
}

const fmtSeconds = (seconds: number) => {
  const rounded = Math.abs(Math.round(seconds))
  if (rounded < 60) return `${rounded}s`
  return `${Math.floor(rounded / 60)}m ${rounded % 60}s`
}

const fmtVariance = (seconds: number) => {
  if (Math.abs(seconds) < 15) return 'On time'
  return seconds > 0 ? `+${fmtSeconds(seconds)}` : `−${fmtSeconds(seconds)}`
}

const displayDate = (value: string) =>
  new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }).format(
    new Date(`${value}T12:00:00Z`),
  )

export default function Demo1N53Cohort() {
  const [showDetail, setShowDetail] = useState(false)

  const modelled = useMemo(() => ALL_ROWS.filter((row) => row.source === 'modelled'), [])
  const actual = ALL_ROWS.find((row) => row.source === 'actual')!
  const normalDwell = Math.round(median(modelled.map((row) => row.chorleyDwell)))
  const normalPreston = Math.round(median(modelled.map((row) => row.prestonVariance)))
  const extendedRate = Math.round((modelled.filter((row) => row.chorleyDwell > 90).length / modelled.length) * 100)
  const extraChorley = actual.chorleyDwell - normalDwell
  const netAfterRecovery = actual.delayGained - actual.delayRecovered

  return (
    <section className="panel cohort-panel">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">1N53 comparison</p>
          <h2>What does the data tell us?</h2>
        </div>
        <span className="cohort-count">67 journeys compared</span>
      </div>

      <div className="cohort-provenance">
        <strong>Demo comparison:</strong> the 1 September journey is genuine SectionIQ field evidence. The other 66 journeys are modelled around the real 1N53 timetable pattern so the comparison and trend tools can be demonstrated before a large field dataset exists.
      </div>

      <div className="cohort-metrics">
        <article>
          <span>Normal Chorley dwell</span>
          <strong>{fmtSeconds(normalDwell)}</strong>
          <small>Typical modelled comparison</small>
        </article>
        <article>
          <span>1 Sep Chorley dwell</span>
          <strong style={{ color: '#fca5a5' }}>2m 40s</strong>
          <small>{fmtSeconds(extraChorley)} longer than typical</small>
        </article>
        <article>
          <span>Delay recovered afterwards</span>
          <strong style={{ color: '#a7f3d0' }}>3m 20s</strong>
          <small>Most of the identified loss recovered</small>
        </article>
        <article>
          <span>Preston arrival</span>
          <strong style={{ color: '#a7f3d0' }}>+11s</strong>
          <small>Effectively back on time</small>
        </article>
      </div>

      <div className="ai-cohort-panel">
        <div className="ai-cohort-heading">
          <span className="ai-badge">SectionIQ conclusion</span>
          <strong>One clear exception — followed by strong recovery</strong>
        </div>

        <div style={{ display: 'grid', gap: 12 }}>
          <article style={{ padding: 16, border: '1px solid rgba(239,68,68,.32)', borderRadius: 12, background: 'rgba(239,68,68,.055)' }}>
            <span style={{ display: 'block', color: 'var(--muted)', fontSize: '.75rem' }}>WHERE WAS TIME LOST?</span>
            <strong style={{ display: 'block', margin: '6px 0', fontSize: '1.15rem' }}>Chorley station dwell</strong>
            <p style={{ margin: 0, lineHeight: 1.55 }}>The 1 September stop lasted <strong>2m40</strong>. A typical comparison stop is about <strong>{fmtSeconds(normalDwell)}</strong>. This is the clearest performance exception on the journey.</p>
          </article>

          <article style={{ padding: 16, border: '1px solid rgba(52,211,153,.32)', borderRadius: 12, background: 'rgba(52,211,153,.055)' }}>
            <span style={{ display: 'block', color: 'var(--muted)', fontSize: '.75rem' }}>WHAT HAPPENED NEXT?</span>
            <strong style={{ display: 'block', margin: '6px 0', fontSize: '1.15rem' }}>The train recovered the lost time</strong>
            <p style={{ margin: 0, lineHeight: 1.55 }}>About <strong>3m20</strong> was recovered after the identified losses. The remaining net identified loss was only <strong>{fmtSeconds(netAfterRecovery)}</strong>, with Preston observed at <strong>+11s</strong>.</p>
          </article>

          <article style={{ padding: 16, border: '1px solid rgba(34,211,238,.28)', borderRadius: 12, background: 'rgba(34,211,238,.045)' }}>
            <span style={{ display: 'block', color: 'var(--muted)', fontSize: '.75rem' }}>WHAT SHOULD A MANAGER CONCLUDE?</span>
            <strong style={{ display: 'block', margin: '6px 0', fontSize: '1.15rem' }}>Investigate the Chorley stop — not the whole journey</strong>
            <p style={{ margin: 0, lineHeight: 1.55 }}>The evidence points to an unusually long dwell as the main event worth understanding. The GPS evidence proves the extended stop, but it does <strong>not</strong> establish why it happened. The next step is to review manager observations or other corroborating evidence for that call.</p>
          </article>
        </div>
      </div>

      <div style={{ margin: '0 18px 18px', padding: 16, border: '1px solid var(--line)', borderRadius: 12, background: '#0a1726' }}>
        <strong style={{ display: 'block', marginBottom: 8 }}>At a glance across the comparison field</strong>
        <p style={{ margin: 0, color: '#c7d3df', lineHeight: 1.6 }}>
          Typical Preston arrival is {fmtVariance(normalPreston)}. Around {extendedRate}% of the modelled comparison journeys contain a Chorley dwell above 90 seconds. The 1 September journey is therefore a clear dwell outlier, but not a poor final-arrival result because the service subsequently recovered strongly.
        </p>
      </div>

      <div style={{ margin: '0 18px 18px' }}>
        <button className="secondary-button" onClick={() => setShowDetail((value) => !value)}>
          {showDetail ? 'Hide detailed comparison' : 'Show detailed comparison'}
        </button>
      </div>

      {showDetail && (
        <div className="table-wrap cohort-table-wrap">
          <table>
            <thead>
              <tr><th>Date</th><th>Evidence</th><th>Chorley dwell</th><th>Delay gained</th><th>Recovered</th><th>Preston</th><th>Context</th></tr>
            </thead>
            <tbody>
              {[...ALL_ROWS].reverse().map((row) => (
                <tr key={row.date} className={row.source === 'actual' ? 'actual-cohort-row' : ''}>
                  <td><strong>{displayDate(row.date)}</strong></td>
                  <td>{row.source === 'actual' ? <span className="evidence-chip real">ACTUAL</span> : <span className="evidence-chip synthetic">MODELLED</span>}</td>
                  <td>{fmtSeconds(row.chorleyDwell)}</td>
                  <td>+{fmtSeconds(row.delayGained)}</td>
                  <td>−{fmtSeconds(row.delayRecovered)}</td>
                  <td>{fmtVariance(row.prestonVariance)}</td>
                  <td>{row.event}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}
