import { useEffect, useMemo, useState } from 'react'
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
