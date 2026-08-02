# Mint IDE

Mint IDE is a separate Java JVM plugin for Mintgram. It does not place the editor or
compiler UI in the app core.

## What it provides

- a monospace Java editor with line numbers and syntax highlighting;
- multiple named projects with quick switching and autosave;
- a **New project** button which creates an up-to-date, buildable SDK 2.1 template;
- a project file panel and creation of additional `.java` files;
- in-process Java compilation with Eclipse ECJ;
- conversion of compiled classes to Android DEX with D8;
- a metadata step for plugin ID, name, author, description and image;
- automatic `.plugin` packaging, installation and activation;
- a Telegram duck success dialog with **Share** and **Close** actions.

Projects and exported packages live inside Mint IDE's private plugin data directory. A generated
package contains only its own `plugin.json`, `classes.dex` and optional `assets/icon.png`.

## Entry point

After installing and enabling `mint_ide.plugin`, open its settings and tap **Открыть редактор**.
Every new project contains a ready-to-build `JvmPluginAdapter` example with its own
settings action and a visible greeting, while an existing legacy workspace is migrated
without deleting user code.

## Build

`build-plugin.sh` creates `dist/mint_ide.plugin`. The toolchain is carried by the IDE plugin
itself: the main Mintgram APK only provides a generic JVM action-row API.
