from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATAVIEW = ROOT / "dataview"
SRC = DATAVIEW / "src"

journey_map = r'''import { useEffect, useMemo, useState } from 'react'
import L from 'leaflet'
import { CircleMarker, MapContainer, Polyline, TileLayer, useMap } from 'react-leaflet'
import 'leaflet/dist/leaflet.css'
import { supabase } from './supabase'

type JourneyPoint = {
  sequence_number: number
  recorded_at: string
  latitude: number
  longitude: number
  accuracy_m: number | null
  speed_mps: number | null
  speed_available: boolean | null
}

type LatLngTuple = [number, number]

function FitJourney({ positions }: { positions: LatLngTuple[] }) {
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

export default function JourneyMap({
  journeyId,
  headcode,
}: {
  journeyId: string | null
  headcode: string | null
}) {
  const [points, setPoints] = useState<JourneyPoint[]>([])
  const [loading, setLoading] = useState(false)
  const [message, setMessage] = useState('')

  useEffect(() => {
    let cancelled = false

    const loadPoints = async () => {
      if (!journeyId) {
        setPoints([])
        setMessage('')
        return
      }

      setLoading(true)
      setMessage('')

      const allPoints: JourneyPoint[] = []
      const pageSize = 1000
      let from = 0

      while (!cancelled) {
        const { data, error } = await supabase
          .from('journey_points')
          .select('sequence_number,recorded_at,latitude,longitude,accuracy_m,speed_mps,speed_available')
          .eq('journey_id', journeyId)
          .order('sequence_number', { ascending: true })
          .range(from, from + pageSize - 1)

        if (error) {
          if (!cancelled) {
            setMessage(error.message)
            setPoints([])
            setLoading(false)
          }
          return
        }

        const page = (data ?? []) as JourneyPoint[]
        allPoints.push(...page)

        if (page.length < pageSize) break
        from += pageSize
      }

      if (!cancelled) {
        setPoints(allPoints)
        setLoading(false)
      }
    }

    void loadPoints()
    return () => {
      cancelled = true
    }
  }, [journeyId])

  const positions = useMemo<LatLngTuple[]>(
    () =>
      points
        .filter(
          (point) =>
            Number.isFinite(point.latitude) &&
            Number.isFinite(point.longitude) &&
            point.latitude >= -90 &&
            point.latitude <= 90 &&
            point.longitude >= -180 &&
            point.longitude <= 180,
        )
        .map((point) => [point.latitude, point.longitude]),
    [points],
  )

  const first = positions[0]
  const last = positions[positions.length - 1]

  return (
    <section className="panel journey-map-panel" id="map">
      <div className="panel-heading">
        <div>
          <p className="eyebrow">Recorded route</p>
          <h2>Journey map{headcode ? ` · ${headcode}` : ''}</h2>
        </div>
        <div className="map-meta">
          {loading ? 'Loading GPS trace…' : `${positions.length.toLocaleString('en-GB')} GPS points`}
        </div>
      </div>

      {!journeyId ? (
        <div className="empty-panel">Select a journey to view its recorded route.</div>
      ) : message ? (
        <div className="error-banner map-error">{message}</div>
      ) : loading ? (
        <div className="map-loading">Loading recorded journey…</div>
      ) : !positions.length ? (
        <div className="empty-panel">No GPS points are available for this journey.</div>
      ) : (
        <div className="map-wrap">
          <MapContainer
            className="journey-map"
            center={first}
            zoom={12}
            scrollWheelZoom
            preferCanvas
          >
            <TileLayer
              attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
              url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            />
            <Polyline positions={positions} pathOptions={{ weight: 5, opacity: 0.9 }} />
            <CircleMarker center={first} radius={7} pathOptions={{ weight: 3, fillOpacity: 1 }} />
            {positions.length > 1 && (
              <CircleMarker center={last} radius={7} pathOptions={{ weight: 3, fillOpacity: 1 }} />
            )}
            <FitJourney positions={positions} />
          </MapContainer>
        </div>
      )}
    </section>
  )
}
'''

journey_map_path = SRC / "JourneyMap.tsx"
journey_map_path.write_text(journey_map, encoding="utf-8")

app_path = SRC / "App.tsx"
app = app_path.read_text(encoding="utf-8")

import_anchor = "import { supabase } from './supabase'\n"
import_line = "import JourneyMap from './JourneyMap'\n"
if import_line not in app:
    if import_anchor not in app:
        raise SystemExit("App import anchor not found")
    app = app.replace(import_anchor, import_anchor + import_line, 1)

map_anchor = '''        </section>\n\n        <section className="detail-grid" id="performance">'''
map_markup = '''        </section>\n\n        <JourneyMap\n          journeyId={selectedId}\n          headcode={selected?.entered_headcode ?? selectedMatch?.headcode ?? null}\n        />\n\n        <section className="detail-grid" id="performance">'''
if "<JourneyMap" not in app:
    if map_anchor not in app:
        raise SystemExit("Journey map insertion anchor not found")
    app = app.replace(map_anchor, map_markup, 1)

app_path.write_text(app, encoding="utf-8")

styles_path = SRC / "styles.css"
styles = styles_path.read_text(encoding="utf-8")
map_styles = r'''

/* SectionIQ journey map */
.journey-map-panel{margin-top:18px}.map-meta{color:var(--muted);font-size:.78rem}.map-wrap{height:min(62vh,620px);min-height:420px;background:#081522}.journey-map{width:100%;height:100%}.journey-map-panel .leaflet-container{z-index:0;background:#081522;font-family:inherit}.journey-map-panel .leaflet-control-attribution{font-size:10px}.map-loading{height:420px;display:grid;place-items:center;color:var(--muted);background:#081522}.map-error{margin:18px}.journey-map-panel .leaflet-bar a{color:#111827}@media(max-width:680px){.map-wrap,.map-loading{min-height:340px;height:50vh}.map-meta{align-self:flex-start}}
'''
if "/* SectionIQ journey map */" not in styles:
    styles = styles.rstrip() + map_styles + "\n"
styles_path.write_text(styles, encoding="utf-8")

package_path = DATAVIEW / "package.json"
package = json.loads(package_path.read_text(encoding="utf-8"))
package.setdefault("dependencies", {})["leaflet"] = "^1.9.4"
package["dependencies"]["react-leaflet"] = "^4.2.1"
package.setdefault("devDependencies", {})["@types/leaflet"] = "^1.9.12"
package_path.write_text(json.dumps(package, indent=2) + "\n", encoding="utf-8")

print("Restored JourneyMap.tsx and wired the selected journey GPS trace into DataView.")
