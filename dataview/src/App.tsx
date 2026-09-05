import { FormEvent, useEffect, useMemo, useState } from 'react'
import type { Session } from '@supabase/supabase-js'
import { supabase } from './supabase'
import JourneyMap from './JourneyMap'

type Journey = {
  id: string
  device_id: string
  started_at: string
  ended_at: string | null
  point_count: number
  status: string
  uploaded_at: string | null
  entered_headcode: string | null
}

type ServiceMatch = {
  journey_id: string
  headcode: string | null
  operator_code: string | null
  origin_name: string | null
  destination_name: string | null
  match_method: string | null
  match_confidence: number | null
  train_uid: string | null
}

type Observation = {
  id: string
  observed_at: string
  event_kind: string | null
  entry_status: string | null
  free_text: string | null
  evidence_source: string | null
}

const fmtDateTime = (value: string | null) =>
  value
    ? new Intl.DateTimeFormat('en-GB', {
        day: '2-digit',
        month: 'short',
        year: 'numeric',
        hour: '2-digit',
        minute: '2-digit',
      }).format(new Date(value))
    : '—'

const fmtDuration = (start: string, end: string | null) => {
  if (!end) return '—'
  const seconds = Math.max(0, Math.round((Date.parse(end) - Date.parse(start)) / 1000))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const secs = seconds % 60
  return hours ? `${hours}h ${minutes}m` : `${minutes}m ${secs}s`
}

function Logo({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`brand ${compact ? 'brand-compact' : ''}`}>
      <img src="./sectioniq-logo.png" alt="SectionIQ" />
      <div>
        <strong>SectionIQ</strong>
        {!compact && <span>DataView</span>}
      </div>
    </div>
  )
}

function Login() {
  const [email, setEmail] = useState('benwordsworth@aol.com')
  const [password, setPassword] = useState('')
  const [message, setMessage] = useState('')
  const [busy, setBusy] = useState(false)

  const signIn = async (event: FormEvent) => {
    event.preventDefault()
    setBusy(true)
    setMessage('')
    const { error } = await supabase.auth.signInWithPassword({ email, password })
    setBusy(false)
    if (error) setMessage(error.message)
  }

  const forgotPassword = async () => {
    if (!email) return setMessage('Enter your email address first.')
    setBusy(true)
    const { error } = await supabase.auth.resetPasswordForEmail(email, {
      redirectTo: window.location.origin + window.location.pathname,
    })
    setBusy(false)
    setMessage(error ? error.message : 'Password reset email sent.')
  }

  return (
    <main className="auth-page">
      <section className="auth-card">
        <Logo />
        <p className="auth-intro">Operational performance evidence and journey analysis.</p>
        <form onSubmit={signIn}>
          <label>Email<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" required /></label>
          <label>Password<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required /></label>
          <button className="primary-button" disabled={busy} type="submit">{busy ? 'Signing in…' : 'Sign in'}</button>
        </form>
        <button className="text-button" type="button" onClick={forgotPassword} disabled={busy}>Forgot password?</button>
        {message && <p className="form-message">{message}</p>}
        <p className="access-note">Access is restricted to authorised SectionIQ viewers.</p>
      </section>
      <footer>SectionIQ — built by Bodge Job Apps</footer>
    </main>
  )
}

function App() {
  const [session, setSession] = useState<Session | null>(null)
  const [checking, setChecking] = useState(true)
  const [authorised, setAuthorised] = useState(false)
  const [role, setRole] = useState<string | null>(null)
  const [journeys, setJourneys] = useState<Journey[]>([])
  const [matches, setMatches] = useState<Record<string, ServiceMatch>>({})
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [observations, setObservations] = useState<Observation[]>([])
  const [query, setQuery] = useState('')
  const [statusFilter, setStatusFilter] = useState('all')
  const [message, setMessage] = useState('')
  const [loading, setLoading] = useState(false)

  useEffect(() => {
    supabase.auth.getSession().then(({ data }) => {
      setSession(data.session)
      setChecking(false)
    })
    const { data } = supabase.auth.onAuthStateChange((_event, nextSession) => setSession(nextSession))
    return () => data.subscription.unsubscribe()
  }, [])

  useEffect(() => {
    const checkAccess = async () => {
      if (!session?.user) {
        setAuthorised(false)
        setRole(null)
        return
      }
      setChecking(true)
      const { data, error } = await supabase.from('admin_users').select('role').eq('user_id', session.user.id).maybeSingle()
      setAuthorised(Boolean(data) && !error)
      setRole(data?.role ?? null)
      setChecking(false)
    }
    void checkAccess()
  }, [session])

  const loadJourneys = async () => {
    setLoading(true)
    setMessage('')
    const { data, error } = await supabase
      .from('journeys')
      .select('id,device_id,started_at,ended_at,point_count,status,uploaded_at,entered_headcode')
      .order('started_at', { ascending: false })
      .limit(100)

    if (error) {
      setMessage(error.message)
      setLoading(false)
      return
    }

    const next = (data ?? []) as Journey[]
    setJourneys(next)
    if (!selectedId && next[0]) setSelectedId(next[0].id)

    const ids = next.map((journey) => journey.id)
    if (ids.length) {
      const { data: matchData } = await supabase
        .from('journey_service_matches')
        .select('journey_id,headcode,operator_code,origin_name,destination_name,match_method,match_confidence,train_uid')
        .in('journey_id', ids)
      const map: Record<string, ServiceMatch> = {}
      ;((matchData ?? []) as ServiceMatch[]).forEach((item) => { map[item.journey_id] = item })
      setMatches(map)
    } else setMatches({})
    setLoading(false)
  }

  useEffect(() => { if (authorised) void loadJourneys() }, [authorised])

  useEffect(() => {
    const loadObservations = async () => {
      if (!selectedId || !authorised) return
      const { data } = await supabase
        .from('journey_observations')
        .select('id,observed_at,event_kind,entry_status,free_text,evidence_source')
        .eq('journey_id', selectedId)
        .order('observed_at')
      setObservations((data ?? []) as Observation[])
    }
    void loadObservations()
  }, [selectedId, authorised])

  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase()
    return journeys.filter((journey) => {
      const match = matches[journey.id]
      const textOk = !needle ||
        journey.entered_headcode?.toLowerCase().includes(needle) ||
        match?.origin_name?.toLowerCase().includes(needle) ||
        match?.destination_name?.toLowerCase().includes(needle) ||
        match?.operator_code?.toLowerCase().includes(needle)
      const statusOk = statusFilter === 'all' || journey.status === statusFilter
      return Boolean(textOk && statusOk)
    })
  }, [journeys, matches, query, statusFilter])

  if (checking) return <main className="loading-page"><Logo /><p>Checking SectionIQ access…</p></main>
  if (!session) return <Login />

  if (!authorised) {
    return (
      <main className="auth-page">
        <section className="auth-card">
          <Logo />
          <h1>Access not authorised</h1>
          <p>{session.user.email} is signed in, but is not an authorised SectionIQ viewer.</p>
          <button className="primary-button" onClick={() => supabase.auth.signOut()}>Sign out</button>
        </section>
        <footer>SectionIQ — built by Bodge Job Apps</footer>
      </main>
    )
  }

  const completeCount = journeys.filter((j) => j.status === 'complete').length
  const matchedCount = Object.keys(matches).length
  const selected = journeys.find((j) => j.id === selectedId) ?? null
  const selectedMatch = selected ? matches[selected.id] : undefined

  return (
    <div className="app-shell">
      <header className="topbar">
        <Logo compact />
        <nav><a className="active" href="#overview">Overview</a><a href="#journeys">Journeys</a><a href="#performance">Performance</a><a href="#pathfinder">Pathfinder</a></nav>
        <div className="user-menu"><span>{session.user.email}</span><small>{role}</small><button onClick={() => supabase.auth.signOut()}>Sign out</button></div>
      </header>

      <main className="content">
        <section className="page-heading" id="overview">
          <div><p className="eyebrow">Operational evidence</p><h1>SectionIQ DataView</h1><p>Review recorded journeys, service matches and manager observations.</p></div>
          <button className="secondary-button" onClick={loadJourneys} disabled={loading}>{loading ? 'Refreshing…' : 'Refresh data'}</button>
        </section>

        <section className="metric-grid">
          <article><span>Journeys loaded</span><strong>{journeys.length}</strong></article>
          <article><span>Complete</span><strong>{completeCount}</strong></article>
          <article><span>Service matched</span><strong>{matchedCount}</strong></article>
          <article><span>Selected events</span><strong>{observations.length}</strong></article>
        </section>

        {message && <div className="error-banner">{message}</div>}

        <section className="panel" id="journeys">
          <div className="panel-heading">
            <div><p className="eyebrow">Journey evidence</p><h2>Recorded journeys</h2></div>
            <div className="filters">
              <input type="search" placeholder="Headcode, route or operator" value={query} onChange={(e) => setQuery(e.target.value)} />
              <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value)}><option value="all">All statuses</option><option value="complete">Complete</option><option value="recording">Recording</option></select>
            </div>
          </div>
          <div className="table-wrap"><table><thead><tr><th>Started</th><th>Headcode</th><th>Matched service</th><th>Duration</th><th>Points</th><th>Status</th></tr></thead><tbody>
            {filtered.map((journey) => {
              const match = matches[journey.id]
              return <tr key={journey.id} className={journey.id === selectedId ? 'selected-row' : ''} onClick={() => setSelectedId(journey.id)}>
                <td>{fmtDateTime(journey.started_at)}</td><td><strong>{journey.entered_headcode ?? match?.headcode ?? '—'}</strong></td>
                <td>{match?.origin_name && match?.destination_name ? `${match.origin_name} → ${match.destination_name}` : 'Not matched'}</td>
                <td>{fmtDuration(journey.started_at, journey.ended_at)}</td><td>{journey.point_count}</td><td><span className={`status status-${journey.status}`}>{journey.status}</span></td>
              </tr>
            })}
            {!filtered.length && <tr><td colSpan={6} className="empty-cell">No journeys match the current filters.</td></tr>}
          </tbody></table></div>
        </section>

        <JourneyMap
          journeyId={selectedId}
          headcode={selected?.entered_headcode ?? selectedMatch?.headcode ?? null}
        />

        <section className="detail-grid" id="performance">
          <article className="panel">
            <div className="panel-heading"><div><p className="eyebrow">Selected journey</p><h2>{selected?.entered_headcode ?? selectedMatch?.headcode ?? 'Journey detail'}</h2></div>{selectedMatch && <span className="confidence">{Math.round((selectedMatch.match_confidence ?? 0) * 100)}% match</span>}</div>
            {!selected ? <div className="empty-panel">Select a journey above.</div> : <>
              <div className="journey-summary"><div><span>Started</span><strong>{fmtDateTime(selected.started_at)}</strong></div><div><span>Duration</span><strong>{fmtDuration(selected.started_at, selected.ended_at)}</strong></div><div><span>GPS points</span><strong>{selected.point_count}</strong></div></div>
              <div className={`service-card ${selectedMatch ? '' : 'muted-card'}`}><span>Matched working</span><strong>{selectedMatch ? `${selectedMatch.origin_name ?? '—'} → ${selectedMatch.destination_name ?? '—'}` : 'No confirmed service match'}</strong><small>{selectedMatch ? `${selectedMatch.operator_code ?? '—'} · ${selectedMatch.train_uid ?? 'UID unavailable'} · ${selectedMatch.match_method ?? 'method unavailable'}` : 'Preserved for automatic or manual matching.'}</small></div>
              <div className="comparison"><div><span>WTT</span><strong>Comparison layer</strong></div><div><span>Public</span><strong>Comparison layer</strong></div><div><span>Observed</span><strong>GPS + manager evidence</strong></div><div><span>Train ahead</span><strong>Planned context</strong></div></div>
            </>}
          </article>

          <aside className="panel observations-panel">
            <div className="panel-heading"><div><p className="eyebrow">Manager evidence</p><h2>Observations</h2></div></div>
            <div className="observation-list">{observations.map((observation) => <article key={observation.id}><div><strong>{observation.event_kind?.replace(/_/g, ' ') ?? 'Observation'}</strong><time>{fmtDateTime(observation.observed_at)}</time></div><p>{observation.free_text || 'No additional note.'}</p><small>{observation.evidence_source ?? observation.entry_status ?? 'SectionIQ observation'}</small></article>)}{!observations.length && <div className="empty-panel">No manager observations recorded.</div>}</div>
          </aside>
        </section>

        <section className="panel roadmap-panel" id="pathfinder"><div><p className="eyebrow">Next analysis layer</p><h2>WTT / public / observed comparison</h2><p>Matched journeys will feed section-by-section timetable comparison, planned train-ahead context and Performance on a Page.</p></div></section>
      </main>
      <footer className="app-footer">SectionIQ — built by Bodge Job Apps</footer>
    </div>
  )
}

export default App
