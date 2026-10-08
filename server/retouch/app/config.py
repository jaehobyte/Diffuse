"""Runtime settings, read once from the environment.

Every limit that is part of wire contract v1 (specs/skin_retouch_pipeline.md §8.1) is a constant
here, not an environment variable: the app is built against those numbers. Only the operational
knobs (queue depth, deadline, enabled kinds, model location) are configurable.

Which kinds the app may offer is not an operational knob. A kind is reported in `supported_kinds`
only if it is in [QUALIFIED_KINDS], which changes in code, together with the SR1 gate evidence in
`work/retouch_evaluation.md` (specs/skin_retouch_validation.md: positives ≥ 80 %, every negative
preserved, no severe distortion, protection and strength/combination checks). A kind that has not
passed can still be loaded for evaluation through `RETOUCH_EVALUATION_KINDS`; it is callable by the
smoke/evaluation tools but is never reported to the app as supported.
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path

CONTRACT_VERSION = 1

#: Wire names, in the order `supported_kinds` is reported.
KINDS = ("blemish", "shine", "dark_circles", "shaving_shadow")

#: Kinds whose SR1 quality gate has passed. None has yet (work/retouch_evaluation.md §6).
QUALIFIED_KINDS: tuple[str, ...] = ()

#: §8.1 limits.
MAX_BODY_BYTES = 90 * 1024 * 1024  # 94,371,840 B
MAX_SIDE = 4096
MAX_PIXELS = 4096 * 4096  # 16,777,216

#: Seconds a client is told to wait after a 429.
RETRY_AFTER_S = 5


class ConfigError(ValueError):
    """The environment cannot start a server."""


@dataclass(frozen=True)
class Settings:
    auth_token: str
    model_dir: Path
    #: Loaded and reported as supported; must be qualified.
    enabled_kinds: tuple[str, ...] = QUALIFIED_KINDS
    #: Loaded and callable for evaluation only; never in `supported_kinds`.
    evaluation_kinds: tuple[str, ...] = ()
    #: Not read from the environment; tests replace it, deployments get [QUALIFIED_KINDS].
    qualified_kinds: tuple[str, ...] = QUALIFIED_KINDS
    max_running: int = 1
    max_queued: int = 4
    job_deadline_s: float = 55.0
    host: str = "127.0.0.1"
    port: int = 8084
    #: `auto` tries the CUDA execution provider and falls back to CPU; `cpu` never touches the GPU.
    execution_provider: str = "auto"
    gpu_mem_limit_mb: int = 2048

    def __post_init__(self) -> None:
        if not self.auth_token:
            raise ConfigError("RETOUCH_AUTH_TOKEN is empty; refusing to start without auth")
        unknown = [k for k in (*self.enabled_kinds, *self.evaluation_kinds) if k not in KINDS]
        if unknown:
            raise ConfigError(f"RETOUCH_ENABLED_KINDS / RETOUCH_EVALUATION_KINDS must name kinds from {KINDS}")
        unqualified = [k for k in self.enabled_kinds if k not in self.qualified_kinds]
        if unqualified:
            raise ConfigError(
                f"RETOUCH_ENABLED_KINDS has kinds without a passed quality gate: {unqualified}; "
                "load them with RETOUCH_EVALUATION_KINDS instead"
            )
        if set(self.enabled_kinds) & set(self.evaluation_kinds):
            raise ConfigError("a kind cannot be both enabled and evaluation-only")
        if not self.loaded_kinds:
            raise ConfigError("no kinds to load: set RETOUCH_ENABLED_KINDS or RETOUCH_EVALUATION_KINDS")
        if self.max_running < 1 or self.max_queued < 0 or self.job_deadline_s <= 0:
            raise ConfigError("queue limits and deadline must be positive")
        if self.execution_provider not in ("auto", "cpu"):
            raise ConfigError("RETOUCH_EXECUTION_PROVIDER must be auto or cpu")

    @property
    def loaded_kinds(self) -> tuple[str, ...]:
        return (*self.enabled_kinds, *self.evaluation_kinds)


def load_settings(env: dict[str, str] | None = None) -> Settings:
    env = dict(os.environ) if env is None else env

    def kinds(name: str, default: tuple[str, ...]) -> tuple[str, ...]:
        raw = env.get(name, ",".join(default))
        return tuple(dict.fromkeys(k.strip() for k in raw.split(",") if k.strip()))

    try:
        return Settings(
            auth_token=env.get("RETOUCH_AUTH_TOKEN", "").strip(),
            model_dir=Path(env.get("RETOUCH_MODEL_DIR", "~/.cache/vibe-retouch")).expanduser(),
            enabled_kinds=kinds("RETOUCH_ENABLED_KINDS", QUALIFIED_KINDS),
            evaluation_kinds=kinds("RETOUCH_EVALUATION_KINDS", ()),
            max_running=int(env.get("RETOUCH_MAX_RUNNING", "1")),
            max_queued=int(env.get("RETOUCH_MAX_QUEUED", "4")),
            job_deadline_s=float(env.get("RETOUCH_JOB_DEADLINE_S", "55")),
            host=env.get("RETOUCH_HOST", "127.0.0.1"),
            port=int(env.get("RETOUCH_PORT", "8084")),
            execution_provider=env.get("RETOUCH_EXECUTION_PROVIDER", "auto"),
            gpu_mem_limit_mb=int(env.get("RETOUCH_GPU_MEM_LIMIT_MB", "2048")),
        )
    except ValueError as exc:
        if isinstance(exc, ConfigError):
            raise
        raise ConfigError(f"invalid numeric setting: {exc.__class__.__name__}") from None
