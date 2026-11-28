package com.xiaoze_qwq_.processmanager.ext;

/**
 * 扩展 jar 的加载状态。
 */
public enum ExtensionStatus {
    /** 至少加载出一个实现。 */
    SUCCESS,
    /** 文件夹不存在或 jar 内没有对应 SPI 声明。 */
    SKIPPED,
    /** jar 损坏、类缺失或构造失败。 */
    FAILED
}