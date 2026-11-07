"""Load the integration's pure modules (protocol, schedule, hub) without importing Home Assistant.
The real package __init__ imports HA, which tests and the dev server do not have."""
import sys
import types
from pathlib import Path


def load():
    path = Path(__file__).resolve().parents[1] / "custom_components" / "phoneguard"
    if "phoneguard" not in sys.modules:
        pkg = types.ModuleType("phoneguard")
        pkg.__path__ = [str(path)]
        sys.modules["phoneguard"] = pkg
    return sys.modules["phoneguard"]
