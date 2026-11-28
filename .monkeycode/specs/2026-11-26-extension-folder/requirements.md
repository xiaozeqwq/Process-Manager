# Requirements Document

Feature: 2026-11-26-extension-folder
Date: 2026-11-26
Status: confirmed

## Introduction

The Process Manager (PM) Mod currently loads external password hash algorithms as standalone Mod jars placed in `mods/` and discovered through the classpath Java SPI. This requirement replaces that channel with two dedicated folders under the server root directory: `ciphers/` for password hash algorithm jars and `ssh-ext/` for SSH transport customization jars. The `ssh-ext/` channel adds a new SPI interface that lets third-party jars customize the embedded SSH handshake algorithms (key exchange KEX, session cipher, MAC, signature, host key, and key exchange factory).

## Glossary

- **Server root directory**: the directory containing the server jar and the `mods/`, `config/` directories; identified as the parent directory of PM's config folder
- **Ciphers folder**: the `ciphers/` directory directly under the server root directory that stores password hash algorithm jars
- **SSH extension folder**: the `ssh-ext/` directory directly under the server root directory that stores SSH transport customization jars
- **Extension jar**: a plain Java jar that provides one or more algorithm implementation classes through SPI declaration files; the jar requires no Mod metadata
- **Transport algorithm**: the algorithm negotiated during the SSH protocol-layer handshake (key exchange KEX, session cipher, MAC, host key signature)
- **Host key spec**: a PM-defined value object describing host key algorithm, key size, key file name, and optional serialized key bytes
- **Extension load failure**: jar corruption, missing class, or constructor exception of an extension jar; handled without aborting server startup

## Requirements

### Requirement 1: Extension folder scanning

**User Story:** AS a server operator, I want PM itself to load algorithm jars from two dedicated folders under the server root directory, so that I do not need to create Mod metadata or rely on classpath loading.

#### Acceptance Criteria

1. WHEN PM startup requires starting the embedded SSH server, PM SHALL scan all `*.jar` files directly under the ciphers folder in file-name lexicographic order for `PasswordCipher` SPI declarations.
2. WHEN PM startup requires starting the embedded SSH server, PM SHALL scan all `*.jar` files directly under the SSH extension folder in file-name lexicographic order for `SshTransportCustomizer` SPI declarations.
3. IF the ciphers folder does not exist, PM SHALL skip that scan and register only the built-in password hash algorithms.
4. IF the SSH extension folder does not exist, PM SHALL skip that scan and start the SSH server with the built-in default transport algorithms.
5. PM SHALL load each extension jar with a classloader whose parent is PM's runtime classloader, and PM SHALL load only algorithms whose SPI declaration files inside the jar declare the matching PM extension interface for that folder.
6. PM SHALL create one isolated classloader per extension jar; IF the same class name appears in different jars, the first-loaded jar SHALL take precedence.

### Requirement 2: Extension loading of password hash algorithms

**User Story:** AS a server operator, I want to drop a password hash algorithm jar into the ciphers folder to make a new algorithm available, so that I can switch the hashing algorithm of all PM passwords without modifying the main Mod.

#### Acceptance Criteria

1. WHEN a jar in the ciphers folder contains a `PasswordCipher` SPI declaration, PM SHALL register that algorithm into `PasswordCipherRegistry` and output a load log including the algorithm id and the class name.
2. IF parsing or construction of a `PasswordCipher` implementation inside a ciphers-folder jar fails, PM SHALL log a warning and skip that jar, while continuing to load other jars and starting the server normally.
3. WHILE an extension algorithm is registered, PM SHALL allow the operator to switch the default algorithm via `/pm hash set <id>`, and new password hashes SHALL use the selected algorithm's prefix format.
4. WHILE a password stored by an old algorithm exists, PM SHALL identify the algorithm by the stored string prefix and verify it in the corresponding algorithm, and old passwords SHALL remain usable after the default algorithm changes.
5. PM SHALL maintain uniqueness of algorithm ids; IF two jars register the same id, the first-registered one SHALL take precedence, and later ones SHALL be ignored with a warning.
6. PM SHALL keep the built-in algorithms `bcrypt` and `pbkdf2-sha512` registered regardless of extension loading outcomes.

### Requirement 3: Extension loading of SSH transport algorithms

**User Story:** AS a server operator, I want a jar in the SSH extension folder to customize the SSH handshake algorithm list (KEX/cipher/MAC/signature/host key), so that the embedded SSH service can negotiate a stronger or non-standard algorithm set.

#### Acceptance Criteria

1. PM SHALL define the `SshTransportCustomizer` SPI interface; an extension jar SHALL provide, per item: key-exchange algorithm name list, cipher algorithm name list, MAC algorithm name list, signature algorithm name list, a host key spec, and/or a key exchange factory.
2. WHEN the embedded SSH server starts and one or more transport customizers are registered, PM SHALL merge the provided algorithm names into the server's negotiation algorithm lists, placing custom names ahead of the built-in default names, and PM SHALL log the effective merged lists.
3. PM SHALL ignore algorithm names that the Apache MINA SSHD runtime does not resolve during negotiation, and the remaining valid names SHALL stay in effect.
4. IF a customizer provides a host key spec, PM SHALL build the host key provider from that spec; the key file name in the spec SHALL include the algorithm identifier to avoid collisions with the existing EC key file `pm_ssh_hostkey_ec.ser`.
5. IF a customizer provides a key exchange factory, PM SHALL set that factory into the embedded SSH server before startup, so that a third-party jar can bring a genuinely new key exchange implementation.
6. IF no transport customizer is registered, PM SHALL keep the current behavior: EC P-256 host key and the built-in default algorithm lists of Apache MINA SSHD 2.13.2.
7. WHILE multiple customizers provide values for the same item, PM SHALL use the first non-null value in jar-name lexicographic order and log a warning about the ignored later values.

### Requirement 4: Observability

**User Story:** AS a server operator, I want to see loaded extensions and effective SSH algorithms, so that I can verify whether the configuration takes effect.

#### Acceptance Criteria

1. PM SHALL output to the startup log: the ciphers folder path, the SSH extension folder path, the name and load status of each jar (success or failure reason), and the list of registered algorithms.
2. PM SHALL provide a console command `/pm ext list` to list all loaded extension jars and the SSH transport algorithm lists currently in effect (KEX/cipher/MAC/signature/host key).

### Requirement 5: Extension jar packaging

**User Story:** AS an extension developer, I want to keep the extension jar's dependency boundaries clear, so that it does not break the main Mod's bundled classes.

#### Acceptance Criteria

1. An extension jar SHALL package only its own implementation classes and shadow-relocated third-party dependencies, and SHALL not package PM interface classes, `org.apache.sshd`, `com.sun.jna`, `net.i2p.crypto.eddsa`, or `org.jetbrains.pty4j` classes.
2. WHERE an extension jar's implementation references PM extension interfaces at compile time, those dependencies SHALL be declared in provided scope, and those classes SHALL not be included in the runtime jar.
3. WHERE an extension jar provides a key exchange factory, the jar SHALL apply the same relocation as the main Mod (`org.apache.sshd` to `com.xiaoze_qwq_.processmanager.shadow.sshd`) with sshd-core in provided scope, so that the factory links against PM's relocated SSHD classes at runtime.
4. An extension jar SHALL require no `fabric.mod.json` or `META-INF/neoforge.mods.toml` metadata, because PM loads the jar directly from the extension folder.