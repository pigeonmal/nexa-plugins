# Contributing to nexa-plugins

Official Nexa plugins live here. Each plugin is a self-contained Nexa package
that must satisfy the same standards as the compiler and backends.

| **Scope**: create, validate, and generate plugins | **Contract**: `native.nxid` | **Targets**: Swift and Kotlin |

## Quick start

Create a package, validate its contract and implementation paths, then generate its bindings:

```bash
nexa plugin init dev.nexa.my-plugin --out my-plugin --name MyPlugin
nexa plugin check my-plugin
nexa plugin generate my-plugin --target swift --out my-plugin/generated/swift
```

## Ground rules

- **No runtime reflection or boxing.** Typed contracts in `native.nxid`, direct
  generated Swift/Kotlin calls, no JSON/RPC, no runtime registry.
- **No `AnyView` in hot paths and no boxed Kotlin primitive state** in generated
  or hand-written UI.
- **Explicit error handling.** Declare typed error variants in the IDL instead of
  trapping or force-unwrapping in generated code paths.
- **Deterministic layout.** A given package must generate byte-identical
  bindings for the same inputs; no timestamps, no absolute paths, no ordering
  that depends on the filesystem.
- **One plugin per directory**, named after the package id suffix, with its own
  manifest, contract, platform sources, and tests.

## Workflow

```bash
nexa plugin init dev.nexa.my-plugin --out my-plugin --name MyPlugin
nexa plugin check my-plugin
nexa plugin generate my-plugin --target swift --out my-plugin/generated/swift
nexa plugin generate my-plugin --target kotlin --out my-plugin/generated/kotlin
nexa plugin generate my-plugin --target cpp --out my-plugin/generated/cpp   # only when C++ is implemented
```

Validate a minimal `.nx` app that actually calls the plugin before opening a
pull request; `nexa test --ios` and `nexa test --android` must both compile for
every platform the manifest claims.

CI runs `nexa plugin check` over every package in this repository, so a broken
manifest, IDL, or missing declared source fails the build.
