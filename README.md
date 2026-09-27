# Nexa Plugins

Official plugins for [Nexa](https://github.com/pigeonmal/nexa), the ahead-of-time
compiler that turns `.nx` apps into native SwiftUI and Jetpack Compose projects.

Every package here is a standard Nexa plugin package: a `plugin.config.nx`
manifest, a `native.nxid` contract for native (IDL-backed) plugins or `plugin.nx`
sources for pure plugins, and direct Swift / Kotlin / C++ implementations. No
runtime shims, no reflection, no service locators — the same rules the compiler
enforces for the core framework.

[![License: MPL 2.0](https://img.shields.io/badge/license-MPL--2.0-blue.svg)](LICENSE)

## Available plugins

| Plugin | Package id | Platforms | Description |
|---|---|---|---|
| _none yet_ | | | The first official plugin is being written. |

## Using a plugin

Plugins are consumed as ordinary Nexa dependencies. Pin the repository to a full
commit hash and point `package` at the plugin directory inside it:

```nexa
config {
    dependencies {
        MMKV {
            id: "dev.nexa.mmkv",
            git: "https://github.com/pigeonmal/nexa-plugins.git",
            rev: "0123456789abcdef0123456789abcdef01234567",
            package: "mmkv"
        }
    }
}
```

```nexa
plugin "dev.nexa.mmkv" as MMKV
```

`nexa dev`, `nexa test`, and `nexa release` resolve the dependency, verify that
the manifest id matches, and record resolved sources and content hashes in
`nexa.lock`. Use `--locked` on `check`, `dev`, `test`, or `release` to require an
up-to-date lockfile. Checkouts are cached under `.nexa/plugins/`; that directory
is generated and must not be committed.

Working inside this repository, a local path dependency is usually faster while
the plugin is being developed:

```nexa
config {
    dependencies {
        MMKV { id: "dev.nexa.mmkv", path: "../mmkv" }
    }
}
```

## Repository layout

One directory per plugin at the repository root, named after its package id
suffix, so `package: "mmkv"` addresses it directly:

```
nexa-plugins/
├── mmkv/                     # native (IDL-backed) plugin
│   ├── native.nxid           # typed contract
│   ├── plugin.config.nx      # manifest: platforms, deps, options
│   ├── ios/Sources/          # Swift implementation
│   ├── android/src/main/kotlin/  # Kotlin implementation
│   └── tests/                # conformance + native tests
├── CONTRIBUTING.md
└── README.md
```

This repository is consumed two ways: cloned on its own, or as the
`plugins/` submodule of the Nexa monorepo, where the same `mmkv/` directory is
reachable as `Nexa/plugins/mmkv` for local path dependencies and end-to-end
tests.

A pure (source-only) plugin instead ships `plugin.nx` plus `assets/` and no
`native.nxid`.

## Creating a new plugin

Scaffold it with the Nexa CLI, then validate and generate bindings:

```bash
nexa plugin init dev.nexa.my-plugin --out my-plugin --name MyPlugin
nexa plugin check my-plugin
nexa plugin generate my-plugin --target swift
nexa plugin generate my-plugin --target kotlin
```

`nexa plugin check` parses the manifest and IDL, syntax-checks the declared
Nexa graph, confirms declared source patterns and asset roots exist, and
type-checks dependency-free Swift/Kotlin sources when the toolchains are
available. See the [Nexa plugin
documentation](https://github.com/pigeonmal/nexa/blob/main/docs/plugins.md) for
the full contract.

## License

Mozilla Public License 2.0 — see [LICENSE](LICENSE).
