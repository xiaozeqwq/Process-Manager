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

- `neoforge/build/libs/processmanager-1.0.13.jar`
- `fabric/build/libs/processmanager-1.0.13.jar`

Both loaders require Architectury API 13.0.8+. Fabric does not declare Fabric API.

## SSH console

Set `sshEnabled` for an instance and connect with the instance id or name as the
SSH username over the Minecraft port: `ssh -p <mc-port> <instance>@<host>`.

The reserved username `menu` opens a full-screen TUI menu for managing every
instance: start/stop/restart/force-stop (with confirmation), live status,
sending commands to stdin and attaching the real-time PTY console
(`Ctrl-A` `d` returns to the menu). The menu password is managed from the
server console only (`/pm menu setpassword`); the menu itself can never change
any password.

## Password hashing modules

All passwords are hashed through the pluggable `PasswordCipher` interface.
Built-in: `bcrypt`, `pbkdf2-sha512`. New algorithms ship as independent SPI
mod jars discovered via ServiceLoader; the specification for building one lives
in `skills/crypto-module/SKILL.md`. Switch the default with `/pm hash set`;
existing hashes keep verifying via automatic format detection.

## Dev runs

```bash
./gradlew :neoforge:runClient
./gradlew :neoforge:runServer
./gradlew :fabric:runClient
./gradlew :fabric:runServer
```

## Mapping Names

Official Mojang mappings. License: https://github.com/NeoForged/NeoForm/blob/main/Mojang.md
