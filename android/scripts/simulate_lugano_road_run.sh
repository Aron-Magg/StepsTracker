#!/usr/bin/env bash
set -euo pipefail

# A closed road-following loop in Lugano (Besso), routed for pedestrians by OSRM.
route_url='https://routing.openstreetmap.de/routed-foot/route/v1/driving/8.9572,46.0105;8.9592,46.0105;8.9592,46.0120;8.9572,46.0120;8.9572,46.0105?overview=full&geometries=geojson'
route_file="$(mktemp)"
trap 'rm -f "$route_file"' EXIT
curl -fsS "$route_url" -o "$route_file"

# The three thirds are replayed at 6:40, 5:33 and 4:38 min/km. The receiver is
# present only in debug builds; every sample still crosses the real repository filters.
ruby -rjson -e '
  route = JSON.parse(File.read(ARGV[0])).dig("routes", 0, "geometry", "coordinates")
  coords = [route.first]
  route.each_cons(2) do |(from_lon, from_lat), (to_lon, to_lat)|
    dy = (to_lat - from_lat) * 111_320.0
    dx = (to_lon - from_lon) * 111_320.0 * Math.cos(to_lat * Math::PI / 180.0)
    parts = [(Math.sqrt(dx * dx + dy * dy) / 3.0).ceil, 1].max
    1.upto(parts) { |part| coords << [from_lon + (to_lon - from_lon) * part / parts, from_lat + (to_lat - from_lat) * part / parts] }
  end
  speeds = [2.5, 3.0, 3.6]
  samples = coords.each_with_index.map do |(lon, lat), index|
    if index.zero?
      delay_seconds = 0.0
    else
      previous_lon, previous_lat = coords[index - 1]
      dy = (lat - previous_lat) * 111_320.0
      dx = (lon - previous_lon) * 111_320.0 * Math.cos(lat * Math::PI / 180.0)
      speed = speeds[[index * 3 / coords.length, 2].min]
      delay_seconds = Math.sqrt(dx * dx + dy * dy) / speed
    end
    [lat, lon, delay_seconds]
  end
  recorded_at = (Time.now.to_f * 1000 - samples.sum { |sample| sample[2] } * 1000).round
  samples.each do |lat, lon, delay_seconds|
    recorded_at += (delay_seconds * 1000).round
    puts format("%.7f %.7f %d", lat, lon, recorded_at)
  end
' "$route_file" | while read -r lat lon recorded_at; do
  adb shell am broadcast -a com.stepstracker.android.DEBUG_RUN_LOCATION \
    -n com.stepstracker.android/.tracking.run.DebugRunLocationReceiver \
    --es lat "$lat" --es lon "$lon" --es recordedAt "$recorded_at" </dev/null >/dev/null
  sleep 0.05
done

echo 'Lugano road route replay complete.'
