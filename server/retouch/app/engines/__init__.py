"""The real engines. `build_real_engines` is the only factory deployment code uses."""

from __future__ import annotations

from app.config import Settings
from app.engines.base import Engine


def build_real_engines(settings: Settings) -> dict[str, Engine]:
    from app.engines.blemish import BlemishEngine
    from app.engines.dark_circles import DarkCirclesEngine
    from app.engines.shaving_shadow import ShavingShadowEngine
    from app.engines.shine import ShineEngine

    constructors = {
        "blemish": lambda: BlemishEngine(settings.model_dir, settings.execution_provider, settings.gpu_mem_limit_mb),
        "shine": ShineEngine,
        "dark_circles": DarkCirclesEngine,
        "shaving_shadow": ShavingShadowEngine,
    }
    return {kind: constructors[kind]() for kind in settings.loaded_kinds}
