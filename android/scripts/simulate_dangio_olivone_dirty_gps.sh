#!/usr/bin/env bash
set -euo pipefail

route_file="$(mktemp)"
trap 'rm -f "$route_file"' EXIT
curl -fsS 'https://routing.openstreetmap.de/routed-foot/route/v1/driving/8.9535201,46.4945131;8.9384493,46.5303957?overview=full&geometries=geojson' -o "$route_file"

ruby -rjson -e '
  route = JSON.parse(File.read(ARGV[0])).dig("routes", 0, "geometry", "coordinates")
  clean = [route.first]
  route.each_cons(2) do |(from_lon, from_lat), (to_lon, to_lat)|
    dy = (to_lat - from_lat) * 111_320.0
    dx = (to_lon - from_lon) * 111_320.0 * Math.cos(to_lat * Math::PI / 180.0)
    parts = [(Math.sqrt(dx * dx + dy * dy) / 10.0).ceil, 1].max
    1.upto(parts) { |part| clean << [from_lon + (to_lon - from_lon) * part / parts, from_lat + (to_lat - from_lat) * part / parts] }
  end
  speed = 3.0
  total_seconds = clean.each_cons(2).sum do |(a_lon, a_lat), (b_lon, b_lat)|
    dy = (b_lat - a_lat) * 111_320.0
    dx = (b_lon - a_lon) * 111_320.0 * Math.cos(b_lat * Math::PI / 180.0)
    Math.sqrt(dx * dx + dy * dy) / speed
  end
  recorded_at = (Time.now.to_f * 1000 - total_seconds * 1000).round
  previous_clean = nil
  clean.each_with_index do |(lon, lat), index|
    if previous_clean
      dy = (lat - previous_clean[1]) * 111_320.0
      dx = (lon - previous_clean[0]) * 111_320.0 * Math.cos(lat * Math::PI / 180.0)
      recorded_at += (Math.sqrt(dx * dx + dy * dy) / speed * 1000).round
    end
    previous_clean = [lon, lat]
    # Repeatable sub-accuracy jitter of roughly 1-3 metres.
    noisy_lat = lat + Math.sin(index * 1.7) * 0.000018
    noisy_lon = lon + Math.cos(index * 1.3) * 0.000025
    kind = "normal"; accuracy = 6.0; sample_time = recorded_at
    if index.positive? && index % 37 == 0
      kind = "inaccurate"; accuracy = 80.0
    elsif index.positive? && index % 53 == 0
      kind = "speed_spike"; noisy_lat += 0.003
    elsif index.positive? && index % 67 == 0
      kind = "stale_timestamp"; sample_time -= 20_000
    end
    puts format("%.7f %.7f %d %.1f %s", noisy_lat, noisy_lon, sample_time, accuracy, kind)
  end
' "$route_file" | while read -r lat lon recorded_at accuracy kind; do
  adb shell am broadcast -a com.stepstracker.android.DEBUG_RUN_LOCATION \
    -n com.stepstracker.android/.tracking.run.DebugRunLocationReceiver \
    --es lat "$lat" --es lon "$lon" --es recordedAt "$recorded_at" \
    --es accuracy "$accuracy" --es kind "$kind" </dev/null >/dev/null
  sleep 0.08
done

echo 'Dirty GPS route from Dangio to Olivone replayed.'
