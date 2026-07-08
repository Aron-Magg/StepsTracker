# Architecture

The app records 15-minute UTC intervals in Room and sends them as idempotent batches. Health Connect is always preferred; `TYPE_STEP_COUNTER` is registered only when Health Connect is unavailable or permission has not been granted. The two sources are never active at the same time.

The backend calculates distance and calories, so the client is not authoritative for these values. Estimated stride length is `height × 0.413` for a `FEMALE` profile and `height × 0.415` otherwise. Estimated energy expenditure is `km × weight_kg × 0.75`. These are non-medical approximations.

Persisted timestamps use UTC. Daily queries convert intervals to the IANA time zone stored in the profile, correctly accounting for daylight-saving changes.

## MVP limitations

The sensor fallback collects steps while the app is active. Health Connect remains the reliable path for historical and background collection. Continuous sensor collection would require a foreground service and its mandatory persistent Android notification.

