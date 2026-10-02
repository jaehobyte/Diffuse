"""The one exception every stage raises; the handler turns it into the §6 error body."""

from __future__ import annotations


class ApiError(Exception):
    def __init__(self, status: int, code: str, image_index: int | None = None) -> None:
        super().__init__(code)
        self.status = status
        self.code = code
        #: Only when one specific input caused the failure.
        self.image_index = image_index


class Cancelled(Exception):
    """The handler gave up (deadline, disconnect); the worker stops before its next step."""
