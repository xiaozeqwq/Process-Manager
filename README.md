# Process Manager

Architectury multi-loader mod for Minecraft 1.21.1. Same common sources produce NeoForge and Fabric jars.

## Requirements

- JDK 21
- Gradle wrapper (9.2.1)

## Build

```bash
./gradlew build
```

Artifacts:

- `neoforge/build/libs/processmanager-1.0.0.jar`
- `fabric/build/libs/processmanager-1.0.0.jar`

Both loaders require Architectury API 13.0.8+. Fabric does not declare Fabric API.

## Dev runs

```bash
./gradlew :neoforge:runClient
./gradlew :neoforge:runServer
./gradlew :fabric:runClient
./gradlew :fabric:runServer
```

## Mapping Names

Official Mojang mappings. License: https://github.com/NeoForged/NeoForm/blob/main/Mojang.md
