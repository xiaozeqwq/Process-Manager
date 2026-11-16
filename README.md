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

## Commands

All mod functionality lives under a single `/pm` command tree. The server
console has full access; players must run `/pm verify <password>` first, which
grants an authorized session (24h by default, managed via `/pm auth`).
Failure attempts lock `verify` out for 60s (5 attempts by default). Command
blocks are always rejected. Commands marked *console* can only be run from the
server console.

### General

| Command | Access | Description |
|---|---|---|
| `/pm` | anyone | Show help |
| `/pm verify <password>` | anyone | Verify the main password, start an authorized session |
| `/pm status` | authorized | Console: full status (instances + authorized players). Player: remaining session time |
| `/pm reload` | authorized | Reload instance configuration |
| `/pm set password <password>` | console | Set the main password (6-128 chars, no whitespace) |
| `/pm set password confirm <password>` | console | Confirmation form; usable while `log-commands` is on (warns in log) |
| `/pm unset password` | console | Remove the main password (disables `verify`) |
| `/pm auth list` | console | List authorized players (expiry, remaining time, IP) |
| `/pm auth grant <player> <duration>` | console | Grant a session to an online player |
| `/pm auth grantuuid <uuid> <duration>` | console | Grant a session by UUID (offline works) |
| `/pm auth revoke <player\|uuid>` | console | Revoke all sessions of a player (also invalidates their cookies) |
| `/pm menu` / `/pm menu status` | anyone | Show TUI menu status (enabled, hash algorithm, active sessions) |
| `/pm menu setpassword <password>` | console | Set the menu password (12-64 chars); disconnects active menu sessions |
| `/pm menu clearpassword` | console | Remove the menu password (rejects the `menu` SSH username) |
| `/pm hash` / `/pm hash list` | anyone | List registered password hash algorithms (`(default)` marked) |
| `/pm hash set <algorithm>` | console | Switch the default algorithm; affects new passwords only |

`<duration>` accepts plain seconds or `30s` / `5m` / `2h` / `1d` suffixes
(empty or invalid = default 24h).

### Instance commands

`<instance>` accepts the instance id or name. `start`, `stop`, `restart` and
`forcestop` are mutually exclusive while a transition is already running.

| Command | Access | Description |
|---|---|---|
| `/pm <instance>` | anyone | Show the sub-command list of the instance |
| `/pm <instance> start` | authorized | Start the instance (async) |
| `/pm <instance> stop` | authorized | Graceful stop (sends the stop command, then waits) |
| `/pm <instance> restart` | authorized | Restart the instance |
| `/pm <instance> forcestop` | authorized | Force kill the instance |
| `/pm <instance> input <command>` | authorized | Send a command to the instance stdin (must be running) |
| `/pm <instance> status` | authorized | Console: text status. Player: live status GUI (CPU / memory / network) |
| `/pm <instance> immersive on` | authorized | Subscribe to instance output in the client (unavailable for SSH-enabled instances) |
| `/pm <instance> immersive off` | anyone | Unsubscribe from instance output |
| `/pm <instance> set history <lines>` | anyone | Output history buffer limit (1-10000), applies immediately |
| `/pm <instance> set exitcode <code>` | anyone | Custom exit code (0-255), requires restart |
| `/pm <instance> set autorestart enable\|disable` | anyone | Toggle auto restart |
| `/pm <instance> set help` | anyone | Show `set` sub-command help |

### Instance SSH console

| Command | Access | Description |
|---|---|---|
| `/pm <instance> ssh` | authorized | Show SSH status (auth mode, credentials, service, sessions) |
| `/pm <instance> ssh enable` | authorized | Enable the SSH console (credentials ready + instance stopped) |
| `/pm <instance> ssh disable` | authorized | Disable the SSH console (instance stopped); disconnects sessions |
| `/pm <instance> ssh setpassword <password>` | console | Set the SSH password (12-64 chars); disconnects sessions |
| `/pm <instance> ssh auth password\|publickey` | console | Switch auth mode (instance stopped); default instance stays password-only |
| `/pm <instance> ssh keys` | authorized | List public keys (index, type, fingerprint, comment) |
| `/pm <instance> ssh addkey <key>` | console | Add an OpenSSH public key (e.g. `ssh-ed25519 AAAA... comment`) |
| `/pm <instance> ssh delkey <index>` | console | Remove a public key by index |
| `/pm <instance> ssh default` | console | Show the default fallback instance |
| `/pm <instance> ssh default set` | console | Make this instance the SSH default (SSH on + password mode + password set) |
| `/pm <instance> ssh default clear` | console | Clear the default instance |

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
