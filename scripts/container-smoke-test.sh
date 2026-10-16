#!/usr/bin/env bash
set -euo pipefail
for command in docker grep sed tr python3 mkdir tee sleep; do
  command -v "$command" >/dev/null || { echo "Missing required command: $command" >&2; exit 1; }
done
image=${1:-metro-world:ci}
name="metro-world-ci-${GITHUB_RUN_ID:-local}-$$"
mkdir -p build/container-check
cleanup() {
  docker logs "$name" > build/container-check/server.log 2>&1 || true
  docker rm -fv "$name" >/dev/null 2>&1 || true
}
trap cleanup EXIT
# Publish only the Minecraft port, on loopback and a free host port. RCON stays private.
docker run -d --name "$name" -p 127.0.0.1::25565 \
  -e MEMORY=2G -e LEVEL=ci-smoke -e VIEW_DISTANCE=3 -e SIMULATION_DISTANCE=3 \
  -e MAX_TICK_TIME=-1 -e ENABLE_RCON=TRUE "$image" > build/container-check/container-id.txt
ready=false
for ((attempt=0;attempt<120;attempt++)); do
  running=$(docker inspect -f '{{.State.Running}}' "$name")
  [[ "$running" == true ]] || { docker logs "$name"; exit 1; }
  if docker logs "$name" 2>&1 | grep -F 'Done (' >/dev/null; then
    if docker exec "$name" mc-health >/dev/null 2>&1; then ready=true; break; fi
  fi
  sleep 5
done
[[ "$ready" == true ]] || { echo 'Minecraft did not become ready within 10 minutes'; exit 1; }
docker exec "$name" mc-health
properties=$(docker exec "$name" cat /data/server.properties)
printf '%s\n' "$properties" | tr -d '\r' | grep -Fx 'online-mode=false' >/dev/null
printf '%s\n' "$properties" | tr -d '\r' | grep -Fx 'server-port=25565' >/dev/null
docker exec "$name" cat /data/eula.txt | tr -d '\r' | grep -Fx 'eula=true' >/dev/null
docker exec "$name" test -f /data/mods/metro-world.jar
for mod in fabric-api lithium ferritecore krypton; do
  docker exec "$name" sh -c "ls /data/mods/${mod}*.jar" >/dev/null
done
# Check the loaded custom dimension and generation; stations no longer sit at the origin.
output=$(docker exec "$name" rcon-cli 'execute in metro-world:metro-world run time query gametime')
printf '%s\n' "$output" | tee build/container-check/dimension.txt
printf '%s\n' "$output" | grep -E 'The time is [0-9]+' >/dev/null
docker exec "$name" rcon-cli 'execute in metro-world:metro-world run forceload add -16 -16 16 16' > build/container-check/worldgen.txt
sleep 10
output=$(docker exec "$name" rcon-cli 'metro-world validate-tracks')
printf '%s\n' "$output" | tee build/container-check/tracks.txt
printf '%s\n' "$output" | grep -F 'TRACKS_OK:' >/dev/null
output=$(docker exec "$name" rcon-cli 'metro-world validate-features')
printf '%s\n' "$output" | tee build/container-check/features.txt
printf '%s\n' "$output" | grep -F 'FEATURES_OK:' >/dev/null
output=$(docker exec "$name" rcon-cli 'metro-train validate')
printf '%s\n' "$output" | tee build/container-check/trains.txt
printf '%s\n' "$output" | grep -F 'TRAINS_OK:' >/dev/null
docker exec "$name" sh -c 'test -s /data/polymer/resource_pack.zip || test -s /data/polymer-resourcepack.zip'
for type in station passenger mini interchange terminal freight mixed biocenter aquarium spiral diagonal; do
  output=$(docker exec "$name" rcon-cli "metro-world locate $type")
  printf '%s\n' "$output" >> build/container-check/locate.txt
  printf '%s\n' "$output" | grep -F '/execute in metro-world:metro-world run tp @s' >/dev/null
done
output=$(docker exec "$name" rcon-cli 'metro-world locate entrance')
printf '%s\n' "$output" >> build/container-check/locate.txt
printf '%s\n' "$output" | grep -F 'Overworld:' >/dev/null
docker exec "$name" rcon-cli list > build/container-check/rcon.txt
# Query Minecraft status through the published host port.
port=$(docker port "$name" 25565/tcp | sed 's/.*://')
python3 scripts/check-server-status.py "$port" > build/container-check/status.json
docker logs "$name" > build/container-check/server.log 2>&1
if grep -Ei 'failed to load|mixin apply failed|exception generating|crash report|encountered an unexpected exception' build/container-check/server.log; then
  echo 'Server startup/world-generation error detected'; exit 1
fi
echo 'PASS: startup, installed mods, offline mode, EULA, dimension, world generation, RCON and published Minecraft port'
