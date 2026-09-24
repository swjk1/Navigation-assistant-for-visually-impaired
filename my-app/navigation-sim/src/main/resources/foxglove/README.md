JSON Schemas for Foxglove's well-known message types, copied unmodified from
https://github.com/foxglove/foxglove-sdk/tree/main/schemas/jsonschema (MIT, see LICENSE).

They are embedded in every MCAP file and advertised on the live WebSocket so Foxglove
recognises the messages (it matches on the schema name, e.g. `foxglove.SceneUpdate`).
