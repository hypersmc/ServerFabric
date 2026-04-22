# ServerFabric

ServerFabric is a SubServers-inspired orchestration system for BungeeCord-style networks.

It splits responsibility into small focused parts:

- **ServerFabric-Host** — standalone Java app that manages server instances on a machine
- **ServerFabric-Proxy** — proxy plugin that talks to one or more hosts, registers/routes instances, and exposes the public API
- **ServerFabric-Client** — Paper/Spigot plugin with an in-game GUI for viewing and controlling instances
- **SFabricAPI** — public API module for developers who want to build plugins against ServerFabric without touching raw HTTP or internal proxy code

The goal is to keep the system practical, understandable, and pleasant to extend, while still supporting real multi-host networks.

---

## Features

### Core
- Multi-host support
- Proxy-safe restarts: restarting the proxy does **not** restart running instances
- Host-side instance persistence (`instance.json` per instance)
- Host reboot recovery for instances marked to auto-start
- Instance state tracking
- Proxy polling and host/instance remapping
- Runtime stats support for instances
- Version-aware template creation
- Build cache for generated server jars
- First-boot bootstrap for config, folders, and default templates

### Host
- Create / start / stop / restart / kill / delete instances
- Send console commands to instances
- Runtime info and recent logs
- `/server/stats` for uptime, PID, RAM, VMEM, disk usage
- Internal JDK download/cache for builds
- Build log output written to file instead of flooding console
- Cleanup of temporary build directories after successful builds
- Default template generation on first boot

### Templates / Builds
- Template metadata via `template.json`
- Template default Minecraft version
- Optional version override during creation
- Build-driven templates via `buildToolExec`
- Host-level build cache so the same version is not rebuilt repeatedly
- Internal managed JDKs for build steps so builds do not depend on whatever Java happens to be installed on the host

### In-game GUI
- Instances view
- Templates view
- Instance details view
- Start / stop / restart / kill
- Join instances
- Send server console commands
- Instance stats in the GUI:
    - uptime
    - PID
    - RAM
    - disk usage
- Template quick-play and version-aware creation flow

### Developer API
- `SFabricAPI` exposed by the proxy
- Query hosts, instances, templates, and stats
- Start / stop / restart / kill / command instances
- Create instances through the API without talking to the host HTTP layer directly

---

## Architecture Overview

**ServerFabric-Client**  
→ sends plugin messages on `serverfabric:main`  
→ **ServerFabric-Proxy** receives and routes requests  
→ **ServerFabric-Host** performs the actual filesystem / process / build work

ServerFabric-Host is the resource owner.  
ServerFabric-Proxy is the controller/router.  
The proxy can restart independently of running instances.

---

## Requirements

### Current runtime
- Java 21 for current ServerFabric builds
- BungeeCord / Waterfall / compatible proxy for ServerFabric-Proxy
- Paper / Spigot / Purpur for ServerFabric-Client

### Notes
- ServerFabric can build older server versions using internally managed build JDKs
- This means the host does **not** rely on the system Java version for build steps

---

## Quick Start

## 1) Run ServerFabric-Host

You can simply run:

```bash
java -jar ServerFabricHost.jar
```

On first boot, ServerFabric-Host will create its default config and folder structure automatically if missing.

Default config path:

```text
dyn/config.properties
```

Example config:

```properties
bindHost=127.0.0.1
bindPort=8085
hostId=local

token=CHANGE_ME_TOKEN

rootPath=dyn/

portMin=25570
portMax=25650

javaCmd=java
jvmArgs=-Xms512M,-Xmx1024M
```

You can also use:

```bash
java -jar ServerFabricHost.jar --config path/to/config.properties
```

ServerFabric-Host stores data under:

```text
rootPath/templates/
rootPath/instances/
rootPath/build-cache/
rootPath/tools-cache/
```

### First boot bootstrap
On first boot, ServerFabric-Host can create:
- config file
- root directories
- default example templates

---

## 2) Install ServerFabric-Proxy

Example `plugins/ServerFabric-Proxy/config.yml`:

```yaml
token: "CHANGE_ME_TOKEN"
pollIntervalSeconds: 5

hosts:
  - id: "local"
    baseUrl: "http://127.0.0.1:8085"
    connectHost: "127.0.0.1"

  - id: "node2"
    baseUrl: "http://10.0.0.22:8085"
    connectHost: "10.0.0.22"
```

On startup, ServerFabric-Proxy will:
- query configured hosts
- register known instances into the proxy
- build instance → host routing
- keep polling for changes

---

## 3) Install ServerFabric-Client

Install `ServerFabricClient.jar` on a Paper/Spigot/Purpur server, typically your lobby.

If you want created servers to include the GUI plugin too, include it in your template:

```text
templates/<templateName>/plugins/ServerFabricClient.jar
```

In-game command:

```text
/serf
```

Permission:

```text
serf.gui
```

---

## Templates

Templates live under:

```text
templates/<templateName>/
```

Each template may include a `template.json`.

Example:

```json
{
  "displayName": "Spigot template",
  "buildToolExec": "bash build.sh",
  "serverVersion": "1.21.1",
  "jar": "server.jar",
  "readiness": {
    "type": "LOG_CONTAINS",
    "contains": "Done ("
  },
  "jvm": {
    "args": ["-Xms1G", "-Xmx2G"]
  },
  "pool": {
    "enabled": false
  },
  "data": {
    "persistent": true
  }
}
```

### Notes
- `serverVersion` is the default version for the template
- `buildToolExec` is used when the jar is not already present
- built jars are cached under `build-cache/`
- the same version should only need to be built once

---

## Build Cache

ServerFabric caches built jars per template and version.

Example layout:

```text
build-cache/
  spigot/
    1.21.1/
      server.jar
      buildlog.txt
```

This means:
- first build for a version does the expensive work
- later creations of the same version reuse the cached jar

---

## Internal JDK Cache

For build-driven templates, ServerFabric uses internally managed JDKs instead of depending on the host system Java.

Example layout:

```text
tools-cache/
  jdks/
    temurin-8/
    temurin-16/
    temurin-17/
    temurin-21/
```

This avoids issues where:
- host Java is too new for an old BuildTools target
- host Java is too old for a newer build target

---

## Instance States

ServerFabric tracks both process state and readiness-related state.

Common states include:

- `STOPPED`
- `STARTING`
- `RUNNING`
- `STOPPING`
- `START_TIMEOUT`
- `CRASHED`
- `BROKEN`

`autoStart` intent is persisted:
- instances that were running can come back after host reboot
- intentionally stopped instances stay stopped

---

## Host HTTP API

ServerFabric-Host exposes a token-protected HTTP API for the proxy.

Important routes include:

- `/server/create`
- `/server/start`
- `/server/stop`
- `/server/restart`
- `/server/kill`
- `/server/delete`
- `/server/command`
- `/server/runtime`
- `/server/logs`
- `/server/stats`
- `/templates`
- `/status`
- `/version`

This HTTP API is intended for ServerFabric itself.  
Plugin developers should use `SFabricAPI` instead of calling the host directly.

---

## SFabricAPI

`SFabricAPI` is the public integration layer for developers.

It is intended to prevent external plugins from:
- calling raw host HTTP
- depending on proxy internals
- parsing private message formats

### What it exposes
- query instances
- query hosts
- query templates
- query instance stats
- create instances
- start / stop / restart / kill
- send commands

### Runtime model
- `serverfabricapi` is the API module
- `ServerFabricProxy` provides the runtime implementation

### Recommended usage
Other plugins should depend on:
- `serverfabricapi` at compile time
- `ServerFabricProxy` at runtime

---

## Building

ServerFabric is a multi-module Gradle project.

Typical modules:

- `serverfabrichost`
- `serverfabricproxy`
- `serverfabricclient`
- `serverfabricapi`

Examples:

```bash
./gradlew :serverfabrichost:shadowJar
./gradlew :serverfabricproxy:shadowJar
./gradlew :serverfabricclient:shadowJar
./gradlew :serverfabricapi:build
```

---

## Publishing / Using SFabricAPI

### For plugin developers
`serverfabricapi` is the compile-time dependency.

Use `compileOnly`, not shaded/relocated copies.

### Gradle example
```gradle
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/hypersmc/serverfabric")
        credentials {
            username = project.findProperty("gprUser") ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gprKey") ?: System.getenv("GITHUB_TOKEN")
        }
    }
}

dependencies {
    compileOnly "dev.jumpwatch.serverfabric:serverfabricapi:0.1.0"
}
```

### Important
At runtime, the proxy provides the API implementation.

---

## Security Notes

- ServerFabric-Host exposes an HTTP API secured by bearer token
- Bind the host to localhost or internal LAN IPs where possible
- Do **not** expose host ports directly to the public internet without proper protections
- Treat tokens as secrets
- Do not hardcode publish credentials into source control

---

## Current Direction

ServerFabric is currently focused on:
- reliable host behavior
- version-aware templates
- build caching
- stable proxy/host routing
- in-game operational visibility
- public plugin API via `SFabricAPI`

The project is intentionally aiming for:
- practical
- stable
- admin-friendly

rather than trying to be a giant overcomplicated platform.

---

## Roadmap

### Near-term
- continue refining version-aware template creation
- add more admin/build convenience commands
- improve protocol/version discipline across components
- expand SFabricAPI coverage
- add API events

### Later
- richer template metadata
- richer web interface
- better host health/availability views
- more advanced host selection and balancing
- improved log viewing

---

## Credits / Inspiration

Inspired by the SubServers ecosystem and the idea of:
- host-managed instances
- proxy-based routing
- in-game management UX

---

## License

```text
Copyright 2026 JumpWatch/HypersMC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required