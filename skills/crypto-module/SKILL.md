---
name: crypto-module
description: 为 Process Manager 模组开发密码哈希算法模块（PasswordCipher SPI jar）的完整规范。接口契约、项目模板、打包要求、安装验证与安全清单，供 AI 或开发者照此产出独立可加载的算法模块 jar。
---

# Process Manager 密码哈希算法模块开发规范

本规范面向要为 Process Manager（下称 PM）新增密码哈希算法的开发者或 AI。
PM 的所有密码（主密码 pmpassword、实例 SSH 密码、SSH 菜单密码）都通过
`PasswordCipher` 接口哈希与校验。内置算法为 `bcrypt` 与 `pbkdf2-sha512`，
外部算法以独立 jar 形式提供，放入服务器根目录下的 `ciphers/` 文件夹即被
PM 启动时自动发现并注册，无需修改 PM 源码，也无需在 `mods/` 中放置 Mod 元数据。

## 一、系统工作原理

- 接口：`com.xiaoze_qwq_.processmanager.crypto.PasswordCipher`，由 PM 主模组 jar 在运行时提供。
- 注册：PM 启动时扫描服务端根目录下的 `ciphers/` 文件夹，对其中每个 jar 创建独立的 `URLClassLoader`，
  并读取 jar 内的 `META-INF/services/com.xiaoze_qwq_.processmanager.crypto.PasswordCipher` SPI 声明，
  注册声明的算法实现。单个 jar 加载失败只记录警告，不影响其他 jar 或服务器启动。
- 新哈希：使用配置 `pm_config.toml` 中 `[hashing] algorithm` 指定的算法生成
  （`/pm hash set <算法>` 可切换）。
- 旧哈希：校验时按存储字符串前缀自动识别算法（`matches()` / `matchesLegacy()`），
  更换算法后历史密码继续有效，平滑迁移。

## 二、接口契约（必须严格遵守）

```java
package com.xiaoze_qwq_.processmanager.crypto;

public interface PasswordCipher {
    String id();                          // 算法唯一 id，同时作为输出前缀，如 "argon2id"
    String hash(String plain);            // 生成自识别格式的哈希
    boolean verify(String plain, String stored); // 常量时间比较校验
    boolean matches(String stored);       // stored 是否为本算法当前输出格式
    default boolean matchesLegacy(String stored) { return false; } // 历史格式回退识别
}
```

## 三、项目模板

纯 Java 21 项目即可，无需 Architectury Loom。目录结构：

```
pm-cipher-argon2id/
├── build.gradle
├── settings.gradle
└── src/
    └── main/
        ├── java/com/example/pmcipher/Argon2IdCipher.java
        └── resources/
            └── META-INF/services/com.xiaoze_qwq_.processmanager.crypto.PasswordCipher
```

`settings.gradle`:

```gradle
rootProject.name = 'pm-cipher-argon2id'
```

`build.gradle`（编译期依赖 PM 主模组 jar，运行时由 PM 提供接口，禁止打进产物）：

```gradle
plugins { id 'java' }

group = 'com.example'
version = '1.0.0'

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories { mavenCentral() }

dependencies {
    // 指向从 GitHub Release 下载的 processmanager fat jar，仅参与编译
    compileOnly files('libs/processmanager-1.0.13.jar')
    // 模块自身需要的第三方库（如有，需 shadow 重定位到本模块私有包）
    implementation 'org.bouncycastle:bcprov-jdk18on:1.78.1'
}
```

硬性要求：

1. **零依赖 Minecraft**：实现类只允许依赖 JDK 与模块自带且重定位过的第三方库。
   禁止引用 `net.minecraft.*`、`dev.architectury.*`、fabric-loader / neoforge 类。
2. **前缀唯一**：`hash()` 输出必须以 `<id>$` 开头（bcrypt 类天然自识别格式除外，
   如 `$2b$`），且不得与其他已注册算法的前缀冲突。`matches()` 只匹配自己的格式。
3. **常量时间比较**：`verify()` 必须使用 `MessageDigest.isEqual` 等常量时间比较，
   禁止 `String.equals` 直接比较哈希。
4. **线程安全**：`verify()` 会被多个 SSH 会话并发调用；`hash()` 会消耗 CPU，
   必须能承受每分钟数十次调用。
5. **盐值随机**：每次 `hash()` 必须使用新的随机盐（`SecureRandom`）。
6. **禁止副作用**：不得读写文件、网络、系统属性；不得记录明文密码或哈希到日志。
7. **失败即拒绝**：`verify()` 对任何解析异常返回 false，禁止抛出异常。
8. **`matchesLegacy()` 语义**：仅用于兼容某算法的历史格式（如 PM 旧版无前缀的
   `iterations$salt$hash` PBKDF2）。新算法模块一般返回 false。多个算法的 legacy
   检测都可能命中时先注册者优先，因此 legacy 格式必须足够特异。

## 三、项目模板

纯 Java 21 项目即可，无需 Architectury Loom。目录结构：

```
pm-cipher-argon2id/
├── build.gradle
├── settings.gradle
└── src/
    └── main/
        ├── java/com/example/pmcipher/Argon2IdCipher.java
        └── resources/
            ├── META-INF/services/com.xiaoze_qwq_.processmanager.crypto.PasswordCipher
            ├── fabric.mod.json
            └── META-INF/neoforge.mods.toml
```

`settings.gradle`:

```gradle
rootProject.name = 'pm-cipher-argon2id'
```

`build.gradle`（编译期依赖 PM 主模组 jar，运行时由 PM 提供接口，禁止打进产物）：

```gradle
plugins { id 'java' }

group = 'com.example'
version = '1.0.0'

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

repositories { mavenCentral() }

dependencies {
    // 指向从 GitHub Release 下载的 processmanager fat jar，仅参与编译
    compileOnly files('libs/processmanager-1.0.13.jar')
    // 模块自身需要的第三方库（如有，需 shadow 重定位到本模块私有包）
    implementation 'org.bouncycastle:bcprov-jdk18on:1.78.1'
}
```

实现类示例（演示接口实现方式，算法仅为示意）：

```java
package com.example.pmcipher;

import com.xiaoze_qwq_.processmanager.crypto.PasswordCipher;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/** 算法 id 即前缀：输出 argon2id$<params>$<saltB64>$<hashB64>（示意，非真实 Argon2）。 */
public final class Argon2IdCipher implements PasswordCipher {
    private static final SecureRandom RNG = new SecureRandom();

    @Override public String id() { return "argon2id"; }

    @Override public String hash(String plain) {
        byte[] salt = new byte[16];
        RNG.nextBytes(salt);
        byte[] hash = derive(plain, salt); // 实现自行替换
        return "argon2id$"
                + Base64.getEncoder().encodeToString(salt) + "$"
                + Base64.getEncoder().encodeToString(hash);
    }

    @Override public boolean verify(String plain, String stored) {
        if (plain == null || stored == null || !stored.startsWith("argon2id$")) return false;
        try {
            String[] parts = stored.substring("argon2id$".length()).split("\\$");
            byte[] salt = Base64.getDecoder().decode(parts[0]);
            byte[] expected = Base64.getDecoder().decode(parts[1]);
            return MessageDigest.isEqual(expected, derive(plain, salt));
        } catch (Exception e) {
            return false;
        }
    }

    @Override public boolean matches(String stored) {
        return stored != null && stored.startsWith("argon2id$");
    }

    private byte[] derive(String plain, byte[] salt) {
        // 真实实现应调用 Argon2 库；此处仅为结构示例
        return plain.getBytes(StandardCharsets.UTF_8);
    }
}
```

SPI 声明文件内容（文件名必须是接口全限定名，内容是实现类全限定名）：

```
com.example.pmcipher.Argon2IdCipher
```

## 四、依赖打包规则（血泪教训，必须遵守）

PM 主模组因 JNA 原生库冲突付出过三个作废版本的代价，模块作者必须吸取：

1. **优先纯 JDK 实现**：PBKDF2、scrypt（自实现）、SHA-2/SHA-3 系列无需第三方库。
2. **必须带第三方库时**：使用 gradle shadow 重定位到模块私有包
   （如 `com.example.pmcipher.shadow.de.jargon2`），再通过 SPI 文件暴露实现类。
3. **绝对禁止引入或触碰**：`com.sun.jna`、`com.pty4j`、`net.i2p.crypto.eddsa`、
   `org.apache.sshd` —— 这些与 PM 主模组强绑定（部分不可重定位），重复类会导致
   `UnsatisfiedLinkError` / NeoForge 模块包冲突。
4. **禁止打包** `com.xiaoze_qwq_.processmanager.crypto` 接口类本身。
5. 产出后必须 `unzip -l` 自检：包根目录不得出现 `com/sun/jna`、`org/apache/sshd`、
   `com/xiaoze_qwq_/processmanager`（实现类放在自己的包内）。

## 五、构建产物

`gradle build` 产出一个 jar（如 `pm-cipher-argon2id-1.0.0.jar`）。

## 六、安装与验证清单（置于服务器根目录）

1. 将模块 jar 放入服务器根目录下的 `ciphers/` 文件夹（如不存在则创建）。
2. 启动服务端，启动日志应出现：`PM-Ext loaded cipher '<id>' from pm-cipher-argon2id-1.0.0.jar`。
3. 游戏内或控制台执行 `/pm hash list`，新算法 id 出现且 PM 仍正常运行。
4. 控制台执行 `/pm hash set <新算法id>`。
5. 控制台执行 `/pm menu setpassword <新密码>`，确认 `pm_config.toml` 的
   `[menu] passwordHash` 以新算法前缀开头。
6. 用旧密码登录旧实例 SSH 成功（旧格式自动识别校验）。
7. 用新密码 `ssh -p <mc端口> menu@<host>` 登录 TUI 菜单成功。
8. `/pm hash set bcrypt` 切回，新设密码回到 bcrypt 前缀。

任何一步失败，检查：SPI 文件名拼写、jar 是否放在 `ciphers/` 文件夹内（而非 mods/）、
启动日志中的 PM-Ext 加载警告、第三方库是否发生包冲突。

## 七、SSH 传输算法自定义（ssh-ext/）

除密码哈希算法外，PM 还支持在服务器根目录下的 `ssh-ext/` 文件夹中放置 SSH 传输自定义 jar，
用于定制 SSH 握手时的密钥交换（KEX）、会话加密套件、MAC、签名算法与主机密钥规范。
jar 中需声明 `META-INF/services/com.xiaoze_qwq_.processmanager.ssh.SshTransportCustomizer`。

打包约束：
- 与密码算法 jar 相同的 red line：不得打包 `org.apache.sshd`、`com.sun.jna`、`net.i2p.crypto.eddsa`、
  `com.xiaoze_qwq_.processmanager` 接口类本身。

注意：当前 sshd 2.13.2 不支持设置自定义 KeyExchangeFactory，因此全新的 KEX 算法（如后量子 KEX）
需先升级 sshd-core 版本；ssh-ext jar 目前可自定义算法名称列表与主机密钥规范两项。

## 八、安全红线

- 禁止实现任何可逆加密、明文存储或弱哈希（MD5、SHA-1、无盐 SHA-256）。
- 禁止在 `hash()`/`verify()` 中读取环境变量、系统属性或网络。
- 禁止静态日志记录任何密码内容；审计日志由 PM 负责且不含密码。
- 新算法默认迭代/成本参数不得低于对应年份 OWASP 推荐值。

## 九、版本与交付

- 语义化版本号；接口行为不变时只升 patch。
- 交付物：模块 jar + 一份简短 README（算法、参数、格式示例、构建复现步骤）。
- 建议附带 JUnit 测试（纯 JUnit 5，不依赖 Minecraft）覆盖：hash/verify 往返、
  错误密码拒绝、格式畸形输入拒绝、并发 verify 无异常。
