#!/usr/bin/env node
// Runs the Gradle wrapper with the arguments given, picking the right wrapper
// for the platform. `./gradlew` is unusable from cmd.exe and `gradlew.bat`
// doesn't exist on POSIX, so npm scripts go through here instead.
import { spawnSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');
const isWindows = process.platform === 'win32';
const wrapper = join(repoRoot, isWindows ? 'gradlew.bat' : 'gradlew');

const { status, error } = spawnSync(isWindows ? `"${wrapper}"` : wrapper, process.argv.slice(2), {
  stdio: 'inherit',
  cwd: repoRoot,
  // Node refuses to spawn .bat files without a shell.
  shell: isWindows,
});

if (error) {
  console.error(`Could not run the Gradle wrapper: ${error.message}`);
  process.exit(1);
}
process.exit(status ?? 1);
