package com.xiaoze_qwq_.processmanager.ssh;

/**
 * 自定义主机密钥规范，由 {@link SshTransportCustomizer#hostKey()} 返回。
 * PM 根据该规范在内嵌 SSH 服务启动前构建或加载主机密钥。
 *
 * @param algorithm  主机密钥算法标识，如 "EC" / "RSA" / "Ed25519"
 * @param keySize    密钥大小（位），如 EC 用 256 / RSA 用 2048
 * @param fileName   相对于 PM 配置目录的密钥文件名，必须包含算法标识以避免与默认 EC 密钥文件 `pm_ssh_hostkey_ec.ser` 冲突
 * @param keyBytes   可选，已序列化的密钥字节；若为 null 则由 PM 生成并持久化
 */
public record HostKeySpec(
        String algorithm,
        int keySize,
        String fileName,
        byte[] keyBytes) {
}