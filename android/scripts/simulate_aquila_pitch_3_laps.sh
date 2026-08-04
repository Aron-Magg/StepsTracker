#!/usr/bin/env bash
set -euo pipefail

# OSM way 252639107 is the football pitch named "Campo sportivo" near Aquila (Blenio).
osm_file="$(mktemp)"
trap 'rm -f "$osm_file"' EXIT
curl -fsS 'https://api.openstreetmap.org/api/0.6/way/252639107/full' -o "$osm_file"

ruby -rrexml/document -e '
  document = REXML::Document.new(File.read(ARGV[0]))
  nodes = {}
  document.elements.each("osm/node") do |node|
    nodes[node.attributes["id"]] = [node.attributes["lon"].to_f, node.attributes["lat"].to_f]
  end
  boundary = document.elements["osm/way"].elements.to_a("nd").map { |node| nodes.fetch(node.attributes["ref"]) }
  lap = [boundary.first]
  boundary.each_cons(2) do |(from_lon, from_lat), (to_lon, to_lat)|
    dy = (to_lat - from_lat) * 111_320.0
    dx = (to_lon - from_lon) * 111_320.0 * Math.cos(to_lat * Math::PI / 180.0)
    parts = [(Math.sqrt(dx * dx + dy * dy) / 3.0).ceil, 1].max
    1.upto(parts) { |part| lap << [from_lon + (to_lon - from_lon) * part / parts, from_lat + (to_lat - from_lat) * part / parts] }
  end
  speeds = [2.5, 3.0, 3.6] # 6:40, 5:33 and 4:38 min/km
  samples = []
  3.times do |lap_index|
    lap.each_with_index do |(lon, lat), point_index|
      next if lap_index.positive? && point_index.zero?
      previous = samples.last
      distance = if previous
        dy = (lat - previous[0]) * 111_320.0
        dx = (lon - previous[1]) * 111_320.0 * Math.cos(lat * Math::PI / 180.0)
        Math.sqrt(dx * dx + dy * dy)
      else
        0.0
      end
      samples << [lat, lon, distance, speeds[lap_index]]
    end
  end
  total_seconds = samples.sum { |sample| sample[2] / sample[3] }
  recorded_at = (Time.now.to_f * 1000 - total_seconds * 1000).round
  samples.each do |lat, lon, distance, speed|
    recorded_at += (distance / speed * 1000).round
    puts format("%.7f %.7f %d", lat, lon, recorded_at)
  end
' "$osm_file" | while read -r lat lon recorded_at; do
  adb shell am broadcast -a com.stepstracker.android.DEBUG_RUN_LOCATION \
    -n com.stepstracker.android/.tracking.run.DebugRunLocationReceiver \
    --es lat "$lat" --es lon "$lon" --es recordedAt "$recorded_at" </dev/null >/dev/null
  sleep 0.05
done

echo 'Three laps of the Aquila football pitch replayed.'
