"""What every engine is, and the guarantees the server enforces on whatever an engine returns.

An engine gets a copy of the ROI (H×W×4 uint8, straight alpha) and the allowed mask (H×W bool)
and returns either `None` (nothing to change) or an `EngineOutput`. It is not trusted with the
wire guarantees of §8.1: `enforce` applies them centrally after every engine, so a buggy engine
cannot change a pixel outside the allowance, touch alpha, or report `corrected` with nothing in it.
"""

from __future__ import annotations

import threading
from dataclasses import dataclass, field
from typing import Protocol

import numpy as np


class EngineError(RuntimeError):
    """The engine could not produce a result (a bug or a runtime failure, not "no change")."""


@dataclass
class EngineOutput:
    candidate: np.ndarray  # H×W×4 uint8
    support: np.ndarray  # H×W bool
    timings: dict[str, float] = field(default_factory=dict)


class Engine(Protocol):
    kind: str

    @property
    def version(self) -> str: ...

    def load(self) -> None:
        """Load models and verify digests. Raises on any problem; never substitutes a fake."""

    def warm_up(self) -> None:
        """Run one real inference so the first request does not pay for lazy initialisation."""

    def run(
        self, image: np.ndarray, allowed: np.ndarray, stop: threading.Event
    ) -> EngineOutput | None:
        """Detect and correct. [stop] is set when the result will be discarded."""


@dataclass
class Guaranteed:
    """An engine result after `enforce`. `candidate`/`support` are None for no_change."""

    candidate: np.ndarray | None
    support: np.ndarray | None

    @property
    def corrected(self) -> bool:
        return self.candidate is not None


def enforce(image: np.ndarray, allowed: np.ndarray, output: EngineOutput | None) -> Guaranteed:
    """§8.1 server guarantees, applied to any engine output.

    * support ⊆ allowed ∩ (alpha > 0)
    * candidate RGBA == input wherever support == 0
    * candidate alpha == input alpha everywhere
    * `corrected` only with a non-empty support and at least one changed pixel
    """
    if output is None:
        return Guaranteed(None, None)
    height, width = image.shape[:2]
    candidate = output.candidate
    support = output.support
    if (
        not isinstance(candidate, np.ndarray)
        or candidate.shape != (height, width, 4)
        or candidate.dtype != np.uint8
    ):
        raise EngineError("engine candidate has the wrong shape or dtype")
    if not isinstance(support, np.ndarray) or support.shape != (height, width):
        raise EngineError("engine support has the wrong shape")

    support = (support.astype(bool)) & allowed & (image[:, :, 3] > 0)
    result = np.array(candidate, dtype=np.uint8, copy=True)
    result[~support] = image[~support]
    result[:, :, 3] = image[:, :, 3]
    if not support.any() or not np.any(result != image):
        return Guaranteed(None, None)
    return Guaranteed(result, support)
