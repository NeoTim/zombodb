CREATE TABLE nested_time_mappings (
    data json
);

CREATE INDEX idxnested_time_mappings ON nested_time_mappings USING zombodb ((nested_time_mappings));

INSERT INTO nested_time_mappings VALUES (format('{"date":%s}', to_json(now()))::json);
INSERT INTO nested_time_mappings VALUES (format('{"date":%s}', to_json(now()::date))::json);
INSERT INTO nested_time_mappings VALUES (format('{"date":%s}', to_json(now()::timestamp without time zone))::json);
INSERT INTO nested_time_mappings VALUES (format('{"date":%s}', to_json(now()::timestamp with time zone))::json);
INSERT INTO nested_time_mappings VALUES (format('{"date":%s}', to_json(now()::time))::json);
INSERT INTO nested_time_mappings VALUES (format('{"date":%s}', to_json(now()::time without time zone))::json);
INSERT INTO nested_time_mappings VALUES (format('{"date":%s}', to_json(now()::time with time zone))::json);

DROP TABLE nested_time_mappings CASCADE;