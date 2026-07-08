DELETE FROM step_intervals sensor
USING step_intervals health
WHERE sensor.user_id=health.user_id
  AND sensor.device_id=health.device_id
  AND sensor.interval_start=health.interval_start
  AND sensor.source='STEP_COUNTER'
  AND health.source='HEALTH_CONNECT';

ALTER TABLE step_intervals
    DROP CONSTRAINT step_intervals_user_id_device_id_source_interval_start_key;

ALTER TABLE step_intervals
    ADD CONSTRAINT step_intervals_one_source_per_interval
    UNIQUE (user_id, device_id, interval_start);

