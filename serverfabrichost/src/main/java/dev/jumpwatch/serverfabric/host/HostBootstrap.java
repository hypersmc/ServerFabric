package dev.jumpwatch.serverfabric.host;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class HostBootstrap {

    private final HostConfig cfg;

    public HostBootstrap(HostConfig cfg) {
        this.cfg = cfg;
    }

    public void run() throws IOException {
        Path root = cfg.rootPath();
        Path templates = root.resolve("templates");
        Path instances = root.resolve("instances");
        Path buildCache = root.resolve("build-cache");


        Files.createDirectories(root);
        Files.createDirectories(templates);
        Files.createDirectories(instances);
        Files.createDirectories(buildCache);

        createSamplePaperTemplateIfMissing(templates.resolve("paper"));
        createSampleSpigotTemplateIfMissing(templates.resolve("spigot"));
        createSampleSchedulerIfMissing(root);
        root.resolve("tools-cache").resolve("jdks");
    }

    private void createSamplePaperTemplateIfMissing(Path dir) throws IOException {
        if (Files.exists(dir)) return;

        Files.createDirectories(dir);
        Files.writeString(dir.resolve("template.json"), """
{
  "displayName": "Paper template",
  "buildToolExec": "bash build.sh",
  "serverVersion": "1.21.1",
  "jar": "server.jar",
  "readiness": {
    "type": "LOG_CONTAINS",
    "contains": "Done ("
  },
  "jvm": { "args": ["-Xms1G", "-Xmx2G"] },
  "pool": { "enabled": false },
  "data": { "persistent": true }
}
""");
        Files.writeString(dir.resolve("build.sh"), """
#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:?Missing version (arg1)}"
OUTPUT="${2:?Missing output jar path (arg2)}"
PROJECT="${PROJECT:-paper}"
USER_AGENT="${USER_AGENT:-ServerFabricHost/0.3.0}"

TMP_JSON="$(mktemp)"
TMP_JAR="$(mktemp)"
cleanup() {
  rm -f "$TMP_JSON" "$TMP_JAR"
}
trap cleanup EXIT

echo "[build.sh] Resolving ${PROJECT} version ${VERSION}..."

curl -fsSL \\
  -H "User-Agent: ${USER_AGENT}" \\
  "https://fill.papermc.io/v3/projects/${PROJECT}/versions/${VERSION}/builds" \\
  -o "$TMP_JSON"

DOWNLOAD_URL="$(
python3 - "$TMP_JSON" <<'PY'
import json, sys

with open(sys.argv[1], "r", encoding="utf-8") as f:
    data = json.load(f)

if isinstance(data, dict) and data.get("ok") is False:
    raise SystemExit(data.get("message", "Paper downloads API error"))

url = None
for build in data:
    if build.get("channel") == "STABLE":
        downloads = build.get("downloads", {})
        entry = downloads.get("server:default", {})
        url = entry.get("url")
        if url:
            break

if not url:
    raise SystemExit("No stable Paper build found for requested version")

print(url)
PY
)"

echo "[build.sh] Downloading stable build..."
curl -fsSL \\
  -H "User-Agent: ${USER_AGENT}" \\
  "$DOWNLOAD_URL" \\
  -o "$TMP_JAR"

mkdir -p "$(dirname "$OUTPUT")"
mv "$TMP_JAR" "$OUTPUT"

echo "[build.sh] Wrote jar to: $OUTPUT"
""");
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("server.properties"), """
        accepts-transfers=false
        allow-flight=false
        allow-nether=false
        broadcast-console-to-ops=true
        broadcast-rcon-to-ops=true
        bug-report-link=
        debug=false
        difficulty=easy
        enable-command-block=false
        enable-jmx-monitoring=false
        enable-query=false
        enable-rcon=false
        enable-status=true
        enforce-secure-profile=false
        enforce-whitelist=false
        entity-broadcast-range-percentage=100
        force-gamemode=false
        function-permission-level=2
        gamemode=survival
        generate-structures=true
        generator-settings={}
        hardcore=false
        hide-online-players=false
        initial-disabled-packs=
        initial-enabled-packs=vanilla
        level-name=Spawn
        level-seed=
        level-type=default
        log-ips=true
        max-chained-neighbor-updates=1000000
        max-players=100
        max-tick-time=60000
        max-world-size=29999984
        motd=ServerFabric Paper Template
        network-compression-threshold=256
        online-mode=false
        op-permission-level=4
        pause-when-empty-seconds=-1
        player-idle-timeout=0
        prevent-proxy-connections=false
        pvp=true
        query.port=3013
        rate-limit=0
        rcon.password=178324tr6783t24gf67fg76934gf823qg
        rcon.port=25575
        region-file-compression=deflate
        require-resource-pack=false
        resource-pack=
        resource-pack-id=
        resource-pack-prompt=
        resource-pack-sha1=
        server-ip=0.0.0.0
        server-name=chunlocked
        simulation-distance=10
        spawn-animals=true
        spawn-monsters=true
        spawn-npcs=true
        spawn-protection=16
        sync-chunk-writes=true
        text-filtering-config=
        text-filtering-version=0
        use-native-transport=true
        view-distance=10
        white-list=false
        """);
        Files.writeString(dir.resolve("spigot.yml"), "settings:\n  bungeecord: true\n");

        dir.resolve("build.sh").toFile().setExecutable(true);

        System.out.println("[ServerFabric-Host] Created sample template: " + dir.getFileName());
    }

    private void createSampleSpigotTemplateIfMissing(Path dir) throws IOException {
        if (Files.exists(dir)) return;

        Files.createDirectories(dir);
        Files.writeString(dir.resolve("template.json"), """
{
  "displayName": "Spigot template",
  "buildToolExec": "bash build.sh",
  "serverVersion": "1.21.1",
  "jar": "server.jar",
  "readiness": {
    "type": "LOG_CONTAINS",
    "contains": "Done ("
  },
  "jvm": { "args": ["-Xms1G", "-Xmx2G"] },
  "pool": { "enabled": false },
  "data": { "persistent": true }
}
""");
        Files.writeString(dir.resolve("build.sh"), """
#!/usr/bin/env bash
set -euo pipefail

VERSION="${1:?Missing version}"
OUTPUT="${2:?Missing output path}"

WORKDIR="$(pwd)/.buildtools-work"
BUILDTOOLS_JAR="${WORKDIR}/BuildTools.jar"

mkdir -p "$WORKDIR"

if ! command -v git >/dev/null 2>&1; then
  echo "[build.sh] git is required"
  exit 1
fi

if [ ! -f "$BUILDTOOLS_JAR" ]; then
  echo "[build.sh] Downloading BuildTools..."
  curl -fsSL \\
    "https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar" \\
    -o "$BUILDTOOLS_JAR"
fi

cd "$WORKDIR"

echo "[build.sh] Running BuildTools for version ${VERSION}..."
java -jar "$BUILDTOOLS_JAR" --rev "$VERSION"

CANDIDATE="$(find "$WORKDIR" -maxdepth 1 -type f \\( -name 'spigot-*.jar' -o -name 'spigot*.jar' \\) | head -n 1)"

if [ -z "${CANDIDATE}" ]; then
  echo "[build.sh] Could not find built spigot jar"
  exit 1
fi

mkdir -p "$(dirname "$OUTPUT")"
cp "$CANDIDATE" "$OUTPUT"

echo "[build.sh] Wrote jar to: $OUTPUT"
""");
        Files.writeString(dir.resolve("eula.txt"), "eula=true\n");
        Files.writeString(dir.resolve("server.properties"), """
        accepts-transfers=false
        allow-flight=false
        allow-nether=false
        broadcast-console-to-ops=true
        broadcast-rcon-to-ops=true
        bug-report-link=
        debug=false
        difficulty=easy
        enable-command-block=false
        enable-jmx-monitoring=false
        enable-query=false
        enable-rcon=false
        enable-status=true
        enforce-secure-profile=false
        enforce-whitelist=false
        entity-broadcast-range-percentage=100
        force-gamemode=false
        function-permission-level=2
        gamemode=survival
        generate-structures=true
        generator-settings={}
        hardcore=false
        hide-online-players=false
        initial-disabled-packs=
        initial-enabled-packs=vanilla
        level-name=Spawn
        level-seed=
        level-type=default
        log-ips=true
        max-chained-neighbor-updates=1000000
        max-players=100
        max-tick-time=60000
        max-world-size=29999984
        motd=ServerFabric Spigot Template
        network-compression-threshold=256
        online-mode=false
        op-permission-level=4
        pause-when-empty-seconds=-1
        player-idle-timeout=0
        prevent-proxy-connections=false
        pvp=true
        query.port=3013
        rate-limit=0
        rcon.password=178324tr6783t24gf67fg76934gf823qg
        rcon.port=25575
        region-file-compression=deflate
        require-resource-pack=false
        resource-pack=
        resource-pack-id=
        resource-pack-prompt=
        resource-pack-sha1=
        server-ip=0.0.0.0
        server-name=chunlocked
        simulation-distance=10
        spawn-animals=true
        spawn-monsters=true
        spawn-npcs=true
        spawn-protection=16
        sync-chunk-writes=true
        text-filtering-config=
        text-filtering-version=0
        use-native-transport=true
        view-distance=10
        white-list=false
        """);
        Files.writeString(dir.resolve("spigot.yml"), "settings:\n  bungeecord: true\n");

        dir.resolve("build.sh").toFile().setExecutable(true);

        System.out.println("[ServerFabric-Host] Created sample template: " + dir.getFileName());
    }
    private void createSampleSchedulerIfMissing(Path dir) throws IOException {
        if (new File(dir.toFile(), "schedule.json").exists()) return;
        Files.writeString(dir.resolve("schedule.json"), """
    {
      "timezone": "Europe/Copenhagen",
      "enabled": true,
      "catchUpWindowHours": 24,
      "tasks": [
        {
          "id": "nightly-restart",
          "enabled": true,
          "cron": "0 0 0 * * ?",
          "offsetMs": 0,
          "actions": [
            { "type": "COMMAND", "instance": "local-paper-template-26829", "command": "broadcast Server restarting in 10 seconds..." },
            { "type": "COMMAND", "instance": "local-paper-template-26829", "command": "save-all", "delayMs": 6000 },
    
            { "type": "COMMAND", "instance": "local-paper-template-26829", "command": "broadcast 4...", "delayMs": 0 },
            { "type": "COMMAND", "instance": "local-paper-template-26829", "command": "broadcast 3...", "delayMs": 1000 },
            { "type": "COMMAND", "instance": "local-paper-template-26829", "command": "broadcast 2...", "delayMs": 1000 },
            { "type": "COMMAND", "instance": "local-paper-template-26829", "command": "broadcast 1...", "delayMs": 1000 },
    
            { "type": "STOP", "instance": "local-paper-template-26829", "waitMs": 5000 },
            { "type": "START", "instance": "local-paper-template-26829" }
          ]
        }
      ]
    }
    """);
        System.out.println("[ServerFabric-Host] Created sample scheduler");
    }
}