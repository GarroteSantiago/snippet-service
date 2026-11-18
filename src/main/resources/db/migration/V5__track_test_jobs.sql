-- Último job TEST publicado para cada test (US-16): un resultado de un job anterior se ignora.
ALTER TABLE test_case ADD COLUMN last_job_id uuid;
