-- A failed Kafka delivery is acknowledged only after this durable recovery record commits.
CREATE TABLE failed_kafka_events (
  id uuid PRIMARY KEY,
  topic varchar(249) NOT NULL,
  partition_number integer NOT NULL CHECK (partition_number>=0),
  offset_number bigint NOT NULL CHECK (offset_number>=0),
  payload text,
  error_category varchar(200) NOT NULL,
  quarantined_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  resolved_at timestamptz,
  UNIQUE(topic,partition_number,offset_number)
);
CREATE INDEX failed_kafka_unresolved ON failed_kafka_events(quarantined_at,id) WHERE resolved_at IS NULL;

CREATE TABLE event_replay_attempts (
  id uuid PRIMARY KEY,
  failed_event_id uuid NOT NULL REFERENCES failed_kafka_events(id),
  actor_id uuid NOT NULL REFERENCES app_users(id),
  reason varchar(500) NOT NULL,
  replay_payload text,
  outcome varchar(10) NOT NULL CHECK (outcome IN ('SUCCEEDED','FAILED')),
  error_category varchar(200),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TRIGGER immutable_replay_audit BEFORE UPDATE OR DELETE ON event_replay_attempts FOR EACH ROW EXECUTE FUNCTION reject_mutation();
CREATE FUNCTION protect_quarantine_evidence() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF TG_OP='DELETE' THEN
   RAISE EXCEPTION 'immutable event recovery evidence' USING ERRCODE='23514';
 END IF;
 IF (NEW.id,NEW.topic,NEW.partition_number,NEW.offset_number,NEW.payload,NEW.error_category,NEW.quarantined_at)
 IS DISTINCT FROM (OLD.id,OLD.topic,OLD.partition_number,OLD.offset_number,OLD.payload,OLD.error_category,OLD.quarantined_at) THEN
   RAISE EXCEPTION 'immutable event recovery evidence' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER immutable_quarantine_evidence BEFORE UPDATE OR DELETE ON failed_kafka_events FOR EACH ROW EXECUTE FUNCTION protect_quarantine_evidence();
