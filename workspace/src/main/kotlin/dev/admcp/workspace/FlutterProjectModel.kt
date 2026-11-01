package dev.admcp.workspace

import java.nio.file.Path

data class FlutterProjectModel(val root: Path, val androidRoot: Path, val flutterSdk: Path?)
