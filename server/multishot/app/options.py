"""The optional `metadata` part: strict JSON, known fields only (specs/multishot_api.md §2.2)."""

from __future__ import annotations

import json
import math
from dataclasses import dataclass

from app.errors import ApiError

FIELDS = {"spacing", "subject_points"}


@dataclass(frozen=True)
class Options:
    spacing: float
    #: One per image, input order; None = text extraction of `person`.
    subject_points: tuple[tuple[float, float] | None, ...]


def _invalid() -> ApiError:
    return ApiError(400, "invalid_metadata")


def _reject_constant(_name: str):
    raise _invalid()


def _unique(pairs):
    keys = [k for k, _ in pairs]
    if len(keys) != len(set(keys)):
        raise _invalid()
    return dict(pairs)


def _unit(value) -> float:
    """A finite JSON number in 0..1; booleans are not numbers here."""
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise _invalid()
    number = float(value)
    if not math.isfinite(number) or not 0.0 <= number <= 1.0:
        raise _invalid()
    return number


def parse_options(raw: bytes | None, count: int) -> Options:
    if raw is None:
        return Options(1.0, (None,) * count)
    try:
        data = json.loads(raw.decode("utf-8"), parse_constant=_reject_constant, object_pairs_hook=_unique)
    except ApiError:
        raise
    except (UnicodeDecodeError, ValueError, RecursionError):
        raise _invalid() from None
    if not isinstance(data, dict) or set(data) - FIELDS:
        raise _invalid()
    spacing = _unit(data["spacing"]) if "spacing" in data else 1.0
    points: tuple[tuple[float, float] | None, ...] = (None,) * count
    if "subject_points" in data:
        raw_points = data["subject_points"]
        if not isinstance(raw_points, list) or len(raw_points) != count:
            raise _invalid()
        parsed: list[tuple[float, float] | None] = []
        for point in raw_points:
            if point is None:
                parsed.append(None)
            elif isinstance(point, list) and len(point) == 2:
                parsed.append((_unit(point[0]), _unit(point[1])))
            else:
                raise _invalid()
        points = tuple(parsed)
    return Options(spacing, points)
