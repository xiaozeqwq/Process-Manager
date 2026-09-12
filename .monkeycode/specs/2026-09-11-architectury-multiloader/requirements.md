# Requirements Document

## Introduction

将 MCC In Server（当前仅支持 NeoForge 1.21.1）改造为 Architectury 多模块工程，在同一 Minecraft 1.21.1 版本线上同时产出 NeoForge 与 Fabric 两套模组产物。业务逻辑、命令、会话、MCC 进程管理、GUI 与网络协议放入 `common`；加载器入口、元数据与 Architectury 引导放入 `neoforge` / `fabric`。运行时依赖 Architectury API，用其统一事件、网络、环境检测与配置目录。

## Glossary

- **System**: MCC In Server 模组及其 Gradle 多模块构建系统
- **Common module**: 存放跨加载器共享 Java 源码与资源的 Gradle 子项目
- **Loader module**: `neoforge` 或 `fabric` 子项目，负责加载器入口、mods.toml / fabric.mod.json 与产物打包
- **Architectury API**: 跨 Fabric / NeoForge 的中间层 API，提供 NetworkManager、Event、Platform、EnvExecutor 等
- **MCC**: Minecraft Console Client，由模组在服务端拉起的外部进程
- **Payload**: 客户端与服务端之间的自定义网络包

## Requirements

### Requirement 1: Multi-module project layout

**User Story:** AS a maintainer, I want common / fabric / neoforge Gradle modules, so that one repository produces both loader jars.

#### Acceptance Criteria

1. THE System SHALL organize sources into `common`, `fabric`, and `neoforge` Gradle subprojects.
2. THE System SHALL keep Minecraft version at 1.21.1 for both loader modules.
3. THE System SHALL produce a NeoForge jar and a Fabric jar from a single `./gradlew build`.
4. WHEN a class uses only Minecraft API, Architectury API, or loader-agnostic Java, THE System SHALL place that class in the common module.
5. WHEN a class registers a loader entrypoint or loader metadata, THE System SHALL place that class in the corresponding loader module.
6. THE System SHALL build `common`, `fabric`, and `neoforge` with Architectury Loom as the unified Minecraft toolchain.
7. THE System SHALL keep `archivesName` as `mccinserver` for both loader jars and distinguish them by output directory (`neoforge/build/libs` versus `fabric/build/libs`).
8. THE Fabric loader module SHALL declare Architectury API as its Fabric-side platform dependency and omit an explicit `fabric-api` dependency in `fabric.mod.json`.

### Requirement 2: Architectury API as the platform layer

**User Story:** AS a developer, I want Architectury API to wrap events, networking, environment, and config paths, so that common code compiles once for both loaders.

#### Acceptance Criteria

1. THE common module SHALL depend on Architectury API for Fabric and NeoForge 1.21.1.
2. WHEN the mod needs the game config directory, THE System SHALL resolve it via `dev.architectury.platform.Platform.getConfigFolder()`.
3. WHEN the mod needs to distinguish client versus dedicated server, THE System SHALL use Architectury `Env` / `EnvExecutor` (or equivalent Architectury environment API).
4. THE common module SHALL replace NeoForge-only types (`NeoForge.EVENT_BUS`, `FMLPaths`, `FMLEnvironment`, `PacketDistributor`, `RegisterPayloadHandlersEvent`) with Architectury or Minecraft equivalents.
5. THE loader modules SHALL declare a runtime dependency on Architectury API with a version compatible with Minecraft 1.21.1.

### Requirement 3: Dual-loader entrypoints and metadata

**User Story:** AS a server operator, I want a Fabric jar and a NeoForge jar that both load MCC In Server, so that I can run the same feature set on either loader.

#### Acceptance Criteria

1. WHEN the NeoForge jar is placed in a NeoForge 1.21.1 mods folder, THE System SHALL load with mod id `mccinserver`.
2. WHEN the Fabric jar is placed in a Fabric 1.21.1 mods folder, THE System SHALL load with mod id `mccinserver`.
3. THE NeoForge metadata SHALL declare dependencies on Minecraft 1.21.1, NeoForge, and Architectury API.
4. THE Fabric metadata SHALL declare dependencies on Minecraft 1.21.1, Fabric Loader, Fabric API, and Architectury API.
5. THE System SHALL keep `mod_id`, `mod_name`, `mod_version`, and `mod_group_id` consistent across both loaders.

### Requirement 4: Shared gameplay behavior

**User Story:** AS a player, I want `/mis`, MCC instance control, immersive console, status GUI, and cookie auth to work the same on Fabric and NeoForge, so that the loader choice does not change gameplay.

#### Acceptance Criteria

1. THE System SHALL register the `/mis` command tree on both loaders with the same subcommands as the current NeoForge implementation.
2. WHEN the dedicated server starts, THE System SHALL load config, purge expired sessions, and start MCCManager on both loaders.
3. WHEN the dedicated server stops, THE System SHALL stop MCCManager and the session purger on both loaders.
4. WHEN a player disconnects, THE System SHALL remove that player from MCCManager tracking on both loaders.
5. THE System SHALL keep cookie grant, cookie auth, immersive input/exit, status request, and MCC output packet semantics identical on both loaders.

### Requirement 5: Cross-loader networking

**User Story:** AS a developer, I want packets defined once in common and registered through Architectury NetworkManager, so that Fabric and NeoForge clients can talk to either loader's server.

#### Acceptance Criteria

1. THE System SHALL define all current custom payloads in the common module.
2. THE System SHALL register C2S and S2C receivers through Architectury networking APIs.
3. WHEN common code sends a packet to a player, to all players, or to the server, THE System SHALL use Architectury NetworkManager (or a thin common wrapper over it).
4. WHEN a packet handler runs on the client, THE System SHALL keep Screen / Minecraft client class loading off the dedicated-server classpath.
5. THE System SHALL preserve the existing protocol version string `"1"` unless a breaking packet change is introduced in the same change set.

### Requirement 6: Shadowed libraries and packaging

**User Story:** AS a pack maintainer, I want each loader jar to shade toml4j and Apache Commons the same way the current NeoForge jar does, so that config parsing stays isolated from loader libraries.

#### Acceptance Criteria

1. THE System SHALL shade `toml4j`, `commons-compress`, and `commons-codec` into both loader jars.
2. THE System SHALL relocate shaded packages under `com.xiaoze_qwq_.mis.shadow.*` as in the current NeoForge build.
3. THE assembled loader jar SHALL be the default `build` output (empty classifier), matching the current NeoForge packaging intent.
4. THE System SHALL exclude `**/*.bbmodel` and generated cache files from resources on both loaders.

### Requirement 7: CI and developer workflow

**User Story:** AS a contributor, I want CI to build both loader artifacts, so that Fabric and NeoForge breakage is caught on every push.

#### Acceptance Criteria

1. WHEN GitHub Actions runs on push or pull request, THE workflow SHALL build both Fabric and NeoForge jars with JDK 21.
2. THE root README SHALL document the Architectury module layout and the two output jars.
3. THE Gradle wrapper SHALL remain the supported way to build the project.

## Out of Scope

- Minecraft versions other than 1.21.1
- Quilt as a first-class loader
- Stonecutter / multi-Minecraft-version source sets
- Changing `/mis` command semantics, auth rules, or MCC process behavior beyond what is required to compile on Fabric
