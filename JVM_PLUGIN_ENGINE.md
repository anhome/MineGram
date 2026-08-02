# Mintgram JVM plugin engine

The JVM engine runs precompiled Java and Kotlin plugins alongside the legacy Python engine.
It deliberately does not compile source code itself: the separate IDE plugin will compile
sources and produce this package format.

Current JVM SDK version: `2.1.0`.

## Package format

A JVM plugin is a ZIP-compatible file with the `.plugin` extension:

```text
my-plugin.plugin
├── plugin.json
├── classes.dex
└── assets/                 # optional, read through JvmPluginContext
```

`plugin.json` example:

```json
{
  "formatVersion": 1,
  "engine": "jvm",
  "language": "kotlin",
  "id": "hello_mintgram",
  "name": "Hello Mintgram",
  "entrypoint": "dev.example.HelloPlugin",
  "version": "1.0.0",
  "author": "Example",
  "description": "Minimal JVM plugin",
  "appVersion": ">=1.0",
  "sdkVersion": ">=2.1.0",
  "hooks": [
    "send_message_hook",
    {"name": "TL_update", "substring": true, "priority": 10}
  ]
}
```

An optional manifest field `"image": "assets/icon.png"` stores a local image (up to 4 MB).
Mintgram shows it in the installation UI; the legacy `"icon": "stickerPack/index"` form remains
supported.

The entrypoint must have a public no-argument constructor and implement
`desu.mintgram.plugins.jvm.JvmPlugin`. Java plugins may extend `JvmPluginAdapter`; Kotlin
plugins may extend `KotlinPlugin`, which exposes a retained non-null `pluginContext`.

## Runtime API

- `JvmPlugin`: lifecycle, app events, request/update/send hooks and settings.
- `JvmPluginContext`: Telegram controllers, UI/plugin queues, typed settings, event/Xposed
  hook registration, per-plugin data directory and packaged assets. Large assets can be copied
  directly into the plugin data directory without loading them entirely into memory.
- `JvmSettings`: Java/Kotlin-friendly factories for Mintgram's existing plugin settings UI,
  including action rows which can open a plugin-owned tool or screen.
- `PluginsController.HookResult`: pass, modify, final-result and cancel strategies compatible
  with the legacy Python dispatcher.

JVM plugins execute in the Mintgram process and can access the app classpath. They are trusted
code with the same privileges as Python plugins, so packages must only be installed from
trusted sources.

## Compatibility and limits

- Existing single-file Python `.plugin` packages remain supported unchanged.
- JVM packages are distinguished by `plugin.json` plus `classes.dex`.
- Package size is limited to 64 MB; unsafe ZIP paths and incompatible SDK/app versions are
  rejected before installation.
- A single packaged asset may be up to 48 MB, allowing trusted developer tools to carry an
  offline compiler or SDK stubs without expanding the core APK.
- The engine loads DEX. Plain JVM `.class`/`.jar` bytecode must first be converted by D8; this
  conversion will be handled by the separate IDE plugin.
