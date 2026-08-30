#!/usr/bin/env node
// Verifies every built plugin jar shades its dependencies under its own
// namespace. An unrelocated copy of Adventure, Configurate or Cloud breaks at
// runtime in ways that are miserable to debug, so this is the gate that keeps
// the shading rules honest.
import { spawnSync } from 'node:child_process';
import { existsSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const repoRoot = join(dirname(fileURLToPath(import.meta.url)), '..');

// Packages that must never appear at their original path inside a plugin jar:
// either Paper provides them, they arrive at runtime, or we relocate them.
const FORBIDDEN = [
  'org/incendo', 'org/spongepowered', 'io/leangen', 'xyz/xenondevs', 'org/bstats',
  'dev/criztian/framework', 'net/kyori', 'org/yaml', 'com/zaxxer', 'me/clip',
  'net/milkbowl', 'org/jetbrains', 'org/jspecify', 'org/intellij', 'org/checkerframework',
];

// framework-core is a plain library, not a shaded plugin — nothing to audit.
const SKIP_MODULES = new Set(['framework-core', 'build-logic', 'scripts', 'gradle']);

function pluginJars() {
  return readdirSync(repoRoot, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() && !SKIP_MODULES.has(entry.name) && !entry.name.startsWith('.'))
    .flatMap((entry) => {
      const libs = join(repoRoot, entry.name, 'build', 'libs');
      if (!existsSync(libs)) return [];
      return readdirSync(libs)
        .filter((file) => file.endsWith('.jar') && !file.endsWith('-unshaded.jar'))
        .map((file) => ({ module: entry.name, path: join(libs, file), file }));
    });
}

function listEntries(jarPath) {
  const result = spawnSync('jar', ['tf', jarPath], { encoding: 'utf8', shell: process.platform === 'win32' });
  if (result.error || result.status !== 0) {
    throw new Error(`could not read ${jarPath} — is the JDK's 'jar' tool on PATH?`);
  }
  return result.stdout.split('\n').map((line) => line.trim()).filter(Boolean);
}

const jars = pluginJars();
if (jars.length === 0) {
  console.error('No plugin jars found. Run `npm run build` first.');
  process.exit(1);
}

let failed = false;
for (const jar of jars) {
  const entries = listEntries(jar.path)
    // A multi-release class hides under META-INF/versions/<n>/ — strip that so
    // an unrelocated copy in there can't slip past the check.
    .map((entry) => entry.replace(/^META-INF\/versions\/\d+\//, ''));

  const leaks = [...new Set(
    entries
      .filter((entry) => FORBIDDEN.some((pkg) => entry.startsWith(`${pkg}/`)))
      .map((entry) => entry.split('/').slice(0, 2).join('/')),
  )];
  const relocated = [...new Set(
    entries
      .filter((entry) => /^dev\/criztian\/[^/]+\/libs\//.test(entry))
      .map((entry) => entry.split('/').slice(0, 5).join('/')),
  )];

  if (leaks.length > 0) {
    failed = true;
    console.error(`FAIL  ${jar.file} — unrelocated packages: ${leaks.join(', ')}`);
  } else if (relocated.length === 0) {
    failed = true;
    console.error(`FAIL  ${jar.file} — nothing was relocated; shading did not run`);
  } else {
    console.log(`OK    ${jar.file} — ${relocated.length} relocated namespaces, no leaks`);
  }
}

process.exit(failed ? 1 : 0);
