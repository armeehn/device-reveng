# os/ota: Riposte OS payloads for update_engine

`mkpayload.py IMAGE_DIR KEY.pem OUT_DIR [--firmware DIR]` turns a build's image set into a
signed full A/B payload (`payload.bin` + `payload_properties.txt`) in the shape of the
vendor's own update13.zip: block 4096, minor 0, REPLACE/REPLACE_XZ, Virtual A/B group
`qti_dynamic_partitions`, snapshot_enabled. The unit's update_engine trusts the certs in
`/system/etc/security/otacerts.zip` of the running image.

Needs python3 with protobuf >= 7.35 (forge: `~/ota-proto/venv`). `update_metadata.proto` is
AOSP update_engine android14-release; `update_metadata_pb2.py` is generated from it with
`python -m grpc_tools.protoc -I. --python_out=. update_metadata.proto`.

Test: `PY=~/ota-proto/venv/bin/python os/test-ota-payload.sh`.
Findings, plan and the first bench test: `share/carlauncher/os-ota.md`.
