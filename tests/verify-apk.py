#!/usr/bin/env python3
"""Reject native-library regressions in the Java/Kotlin-only companion."""
import hashlib
from pathlib import Path
import sys
import zipfile

for argument in sys.argv[1:]:
    path = Path(argument)
    with zipfile.ZipFile(path) as archive:
        assert archive.testzip() is None, f"Corrupt APK: {path}"
        native = [name for name in archive.namelist() if name.endswith(".so")]
        assert not native, f"Unexpected native libraries: {native}"
    print(f"{path.name}: no native libraries, SHA-256 {hashlib.sha256(path.read_bytes()).hexdigest()}")
