"""Shared fixtures for the ARTEMIS end-to-end suite.

Every test hands ARTEMIS a natural-language goal plus a JSON shape it must fill
in from what it sees on the head unit. The model does the driving; the asserts
here stay on the returned JSON, so a failure names a launcher fact, never a
model whim.

    pytest (this file) ──HTTP──► ARTEMIS host ──adb──► head unit (emulated or real)

Configuration comes from the environment only, so the suite carries no site
specifics:

    ARTEMIS_URL             base URL of the ARTEMIS console/API (required)
    ARTEMIS_DEVICE_SERIAL   adb serial to drive; unset = first device the host lists
    ARTEMIS_PROFILE         flash (default) or pro
    ARTEMIS_LAUNCHER_PACKAGE  installed launcher id; debug builds carry a .debug suffix
    ARTEMIS_TASK_TIMEOUT    seconds per task, default 600
"""

from __future__ import annotations

import asyncio
import json
import os
import re
from dataclasses import dataclass
from typing import Any

import pytest
from artemis_client import ArtemisClient

LAUNCHER_PACKAGE = os.environ.get("ARTEMIS_LAUNCHER_PACKAGE", "com.ripostelabs.carlauncher")
DEFAULT_TASK_TIMEOUT_S = 600.0
PROFILE = os.environ.get("ARTEMIS_PROFILE", "flash")

# The panel every goal is written against; a case that hardcodes coordinates
# must state them in this space.
PANEL_WIDTH = 1920
PANEL_HEIGHT = 720


@dataclass(frozen=True)
class Outcome:
    """One finished task: the parsed JSON the goal asked for plus the raw result."""

    data: dict[str, Any]
    task_id: str
    status: str


def _first_json_object(text: str) -> dict[str, Any] | None:
    # The outputter usually returns clean JSON, but a chatty model may wrap it
    # in prose or a code fence. Take the first balanced object it contains.
    fence = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    candidates = [fence.group(1)] if fence else []
    candidates.append(text)
    for candidate in candidates:
        start = candidate.find("{")
        if start < 0:
            continue
        depth = 0
        for i, ch in enumerate(candidate[start:], start):
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                if depth == 0:
                    try:
                        return json.loads(candidate[start : i + 1])
                    except json.JSONDecodeError:
                        break
    return None


def _as_dict(output: Any) -> dict[str, Any]:
    if isinstance(output, dict):
        return output
    if isinstance(output, str):
        parsed = _first_json_object(output)
        if parsed is not None:
            return parsed
    raise AssertionError(f"task output is not the JSON object the goal asked for: {output!r}")


@pytest.fixture(scope="session")
def artemis() -> ArtemisClient:
    url = os.environ.get("ARTEMIS_URL")
    if not url:
        pytest.skip("ARTEMIS_URL is not set; nothing to drive")
    client = ArtemisClient(
        url,
        token=os.environ.get("ARTEMIS_TOKEN"),
        device_serial=os.environ.get("ARTEMIS_DEVICE_SERIAL") or None,
        default_profile=PROFILE,
    )
    try:
        asyncio.run(client.health())
    except Exception as exc:  # noqa: BLE001 - any transport failure means "no host"
        pytest.skip(f"ARTEMIS host at {url} is not answering: {exc}")
    return client


@pytest.fixture(scope="session")
def device_serial(artemis: ArtemisClient) -> str:
    wanted = artemis.device_serial
    devices = asyncio.run(artemis.list_devices())
    serials = [d.serial for d in devices]
    if wanted:
        assert wanted in serials, f"device {wanted} is not attached to the ARTEMIS host (has {serials})"
        return wanted
    assert serials, "the ARTEMIS host lists no Android device"
    return serials[0]


@pytest.fixture
def run_goal(artemis: ArtemisClient, device_serial: str):
    """Run one goal and return its structured output.

    `shape` is the JSON object the model must fill in. Keep keys few and
    boolean or short-string valued: a model answers "is X visible" reliably,
    a paragraph it does not.
    """

    timeout = float(os.environ.get("ARTEMIS_TASK_TIMEOUT", DEFAULT_TASK_TIMEOUT_S))

    def _run(goal: str, shape: dict[str, Any], *, locked: bool = True) -> Outcome:
        expected = (
            "Reply with exactly one JSON object and nothing else, with these keys: "
            + json.dumps(shape)
            + ". Booleans must reflect what is actually on screen at the end."
        )

        async def go():
            handle = await artemis.submit(
                goal,
                device_serial=device_serial,
                expected_output=expected,
                enable_outputter=True,
                locked_app_package=LAUNCHER_PACKAGE if locked else None,
            )
            return await artemis.wait_for_task(handle.task_id, timeout=timeout)

        result = asyncio.run(go())
        # The API rarely carries the failure reason; the host's trace does.
        assert result.succeeded, (
            f"ARTEMIS task {result.task_id} ended '{result.status}': "
            f"{result.error or result.output or 'see `artemis trace` on the host'}"
        )
        return Outcome(data=_as_dict(result.output), task_id=result.task_id, status=result.status)

    return _run
