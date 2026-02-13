# ServerFabric

ServerFabric is a SubServers-inspired orchestration system for BungeeCord networks.

It splits responsibilities into three small parts:

- **ServerFabric-Host** (standalone Java app): runs on a machine and manages Minecraft server instances (create/start/stop/delete/command).
- **ServerFabric-Proxy** (BungeeCord plugin): connects to one or more ServerFabric-Host nodes, registers instances into the proxy, and routes actions to the correct host. Proxies can restart without restarting servers.
- **ServerFabric-Client** (Paper/Spigot plugin): provides an in-game inventory GUI (SubServers-style) for managing instances and templates.

The goal is to keep the system lightweight, understandable, and fun to extend, while still supporting multi-host networks.

---

## Features

### Core
- Multi-host support (proxy config lists multiple hosts)
- Proxy reboot-safe: restarting the proxy does **not** restart running servers
- Host-side instance persistence (`instance.json` per instance)
- Host crash recovery: instances marked for autostart come back on ServerFabric-Host reboot
- Periodic polling: proxy discovers new instances when hosts come online later

### In-game GUI (ServerFabric-Client)
- Instances view: see instances + hostId + state
- Start/Stop instances
- Join instances (Bungee connect)
- Send server console commands (chat capture, SubServers-style)
- Templates view: list templates per host and “Play” (create+start) on a selected host

---

## Architecture Overview

**ServerFabric-Client (Paper GUI)**  
→ sends plugin messages on `serverfabric:main`  
→ **ServerFabric-Proxy** receives and forwards to the correct host  
→ **ServerFabric-Host** does the actual filesystem/process work

ServerFabric-Host is the “resource owner” (the thing that actually runs processes). ServerFabric-Proxy is the controller/router and can be restarted independently.

---

## Requirements

- Java 17+ for Paper (ServerFabric-Client)
- Java 17+ recommended for ServerFabric-Host (can also run on newer)
- BungeeCord / Waterfall / compatible proxy for ServerFabric-Proxy
- Paper/Spigot server(s) for ServerFabric-Client GUI

---

## Quick Start

### 1) Run ServerFabric-Host (on each node)

Create a config file, e.g. `dyn/config.properties`:

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

Run ServerFabric-Host:
```
java -jar ServerFabricHost.jar
```
Use `--config path/to/config.properties` to use another path

ServerFabric-Host stores data under:

* rootPath/templates/
* rootPath/instances/

NOTE: Templates do **not** include the server jar by default. Add paper.jar or server.jar into the template folder you want to use.

##
### 2) Install ServerFabric-Proxy (Proxy plugin)
`plugins/ServerFabric-Proxy/config.yml`:
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
Start the proxy. On boot, ServerFabric-Proxy will:
* query every configured host `/status`
* register instances into Bungee
* build instance → host routing map
* keep polling for new/removed instances


##
### 3) Install ServerFabric-Client (Spigot/Paper GUI Plugin)
Install `ServerFabricClient.jar` on a paper/Spigot (typically your lobby).
<br>If you want newly-created servers to have the GUI too, include ServerFabric-Client in your template:

```templates/<templateName>/plugins/ServerFabricClient.jar```

In-game command:
* `/serf` Opens the GUI

Permissions:
* `serf.gui` (defaults to OP)
---

## Instance States

ServerFabric-Host tracks process + readiness state:
* `STOPPED`
* `STARTING`
* `RUNNING` (when log contains `Done (ss.msms s)! For help, type "help"`)
* `CRASHED`

ServerFabric-Host persists `autoStart` intent:
* servers that were running will auto-start again after ServerFabric-Host reboot
* servers that were intentionally stopped will stay stopped
---

## Security Notes
* ServerFabric-Host exposes an HTTP API secured by a bearer token.
* For local networks, bind ServerFabric-Host to `127.0.0.1` or internal LAN IPs
* Do **not** expose ServerFabric-Host ports to the public internet without additional protections (firewalls / reverse proxy / TLS).
---

## Development / Building
This project is a multi-module Gradle setup (ServerFabric-Host / ServerFabric-Proxy / ServerFabric-Client).

Typical flow:

* build jars (For ServerFabric-Host use `./gradlew :serverfabrichost:shadowJar`)
* copy ServerFabric-Host to host machines
* install ServerFabric-Proxy in proxy `plugins/`
* install ServerFabric-Client in Paper/Spigot `plugins/`
---

## Roadmap Ideas
* Auto-connect after "Play" (create/start/wait ready/send player)
* Instance pooling per template (reuse stopped instances)
* Template metadata (`template.json`): jar name, JVM args, plugins, copy rules
* Live logs view in GUI (tail last N lines)
* Host health + "HOST DOWN" status in GUI
* Smarter host selection (least loaded / least instances / weighted)
---

## Credits / Inspiration

Inspired by the SubServers ecosystem and the idea of “host-managed instances + proxy routing + in-game UX”.

---

## License
```text
Copyright 2026 JumpWatch/HypersMC

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
