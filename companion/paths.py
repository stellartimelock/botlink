"""Suite save path: StellarTimeLock/backups/Bot Link/."""

from __future__ import annotations

import os
from pathlib import Path

APP_FOLDER = "Bot Link"


def backup_dir() -> Path:
    override = os.environ.get("BOTLINK_BACKUP_DIR")
    if override:
        path = Path(override).expanduser()
    else:
        path = Path.home() / "StellarTimeLock" / "backups" / APP_FOLDER
    path.mkdir(parents=True, exist_ok=True)
    return path


def config_path() -> Path:
    return backup_dir() / "botlink.local.json"


def spend_path() -> Path:
    return backup_dir() / "botlink-spend.json"
