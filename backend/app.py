"""
Compatibility launcher.

This file keeps legacy startup commands working while delegating all API
routes to the complete backend under mediconnect_backend/app.py.
"""

import sys
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parent.parent
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from mediconnect_backend.app import app  # noqa: E402


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=5000, debug=True)
