#!/usr/bin/env bash
# Run the ARTEMIS end-to-end suite against a head unit.
#
#   ARTEMIS_URL=http://<artemis-host>:8000 ARTEMIS_DEVICE_SERIAL=emulator-5554 ./run.sh [pytest args]
#
# Builds its own virtualenv on first use (artemis-client from the google/artemis
# repository plus pytest). The ARTEMIS host holds adb, the models and the API
# keys; this side only needs Python 3.10+ and a network path to it.
set -euo pipefail
cd "$(dirname "$0")"

readonly VENV=.venv
readonly ARTEMIS_CLIENT_SPEC="artemis-client @ git+https://github.com/google/artemis.git#subdirectory=packages/artemis-client"

if [ ! -x "$VENV/bin/pytest" ]; then
  echo "creating $VENV ..."
  python3 -m venv "$VENV"
  "$VENV/bin/pip" install -q --upgrade pip
  "$VENV/bin/pip" install -q "$ARTEMIS_CLIENT_SPEC" pytest
fi

[ -n "${ARTEMIS_URL:-}" ] || { echo "ARTEMIS_URL is not set" >&2; exit 2; }
exec "$VENV/bin/pytest" -m e2e -v -rs "$@"
