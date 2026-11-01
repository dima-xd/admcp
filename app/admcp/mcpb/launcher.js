"use strict";

const { spawn } = require("node:child_process");
const { existsSync, readdirSync } = require("node:fs");
const path = require("node:path");

const libDir = path.join(__dirname, "lib");
const javaHome = process.env.JAVA_HOME;
const java = javaHome
  ? path.join(javaHome, "bin", process.platform === "win32" ? "java.exe" : "java")
  : "java";

const jars = existsSync(libDir)
  ? readdirSync(libDir)
      .filter((file) => file.endsWith(".jar"))
      .sort()
      .map((file) => path.join(libDir, file))
  : [];

if (jars.length === 0) {
  console.error("admcp: server libraries are missing from the bundle.");
  process.exit(1);
}

const child = spawn(
  java,
  [
    "-Dfile.encoding=UTF-8",
    "-cp",
    jars.join(path.delimiter),
    "dev.admcp.app.MainKt",
    ...process.argv.slice(2),
  ],
  {
    stdio: "inherit",
    windowsHide: true,
  },
);

child.on("error", (error) => {
  console.error(`admcp: failed to start Java: ${error.message}`);
  console.error("admcp: install JDK 21 and make sure java is on PATH, or set JAVA_HOME.");
  process.exit(1);
});

for (const signal of ["SIGINT", "SIGTERM"]) {
  process.on(signal, () => child.kill(signal));
}

child.on("exit", (code, signal) => {
  if (signal) {
    process.kill(process.pid, signal);
    return;
  }
  process.exit(code === null ? 1 : code);
});
