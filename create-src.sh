#!/bin/sh

# 目标目录（当前脚本所在目录下的 src）
TARGET="./src"
# 源目录（Forge 版模板）
SOURCE="/storage/emulated/0/mod/mis-template-forge/src"

# 检查源目录是否存在
if [ ! -d "$SOURCE" ]; then
    echo "错误：源目录 $SOURCE 不存在，请确认路径是否正确。"
    exit 1
fi

# 创建目标根目录
mkdir -p "$TARGET"

# 复制非星号（*）文件
copy_file() {
    local relpath="$1"
    local src="$SOURCE/$relpath"
    local dst="$TARGET/$relpath"
    mkdir -p "$(dirname "$dst")"
    if [ -f "$src" ]; then
        cp -p "$src" "$dst"
        echo "已复制：$relpath"
    else
        echo "警告：源文件 $src 不存在，跳过。"
    fi
}

# 创建星号（*）文件（空文件）
touch_file() {
    local relpath="$1"
    local dst="$TARGET/$relpath"
    mkdir -p "$(dirname "$dst")"
    touch "$dst"
    echo "已创建空文件：$relpath"
}

# ---- 无 * 的文件（从源目录复制） ----
copy_file "main/java/com/xiaoze_qwq_/mis/MCCManager.java"
copy_file "main/java/com/xiaoze_qwq_/mis/MISSessionManager.java"
copy_file "main/java/com/xiaoze_qwq_/mis/client/gui/ChartDataTracker.java"
copy_file "main/java/com/xiaoze_qwq_/mis/client/util/ANSITextProcessorClient.java"
copy_file "main/java/com/xiaoze_qwq_/mis/config/MISConfig.java"
copy_file "main/java/com/xiaoze_qwq_/mis/logging/MCCOutputLogger.java"
copy_file "main/java/com/xiaoze_qwq_/mis/monitor/StatusMonitor.java"
copy_file "main/java/com/xiaoze_qwq_/mis/util/ANSITextProcessor.java"
copy_file "main/java/com/xiaoze_qwq_/mis/util/RingBuffer.java"
copy_file "main/java/com/xiaoze_qwq_/mis/util/LogEntry.java"
copy_file "main/resources/assets/mccinserver/lang/en_us.json"
copy_file "main/resources/assets/mccinserver/lang/zh_cn.json"
copy_file "main/resources/assets/mccinserver/icon.png"

# ---- 有 * 的文件（创建空文件） ----
touch_file "main/java/com/xiaoze_qwq_/mis/MCCInServer.java"
touch_file "main/java/com/xiaoze_qwq_/mis/MCCInstance.java"
touch_file "main/java/com/xiaoze_qwq_/mis/client/gui/MCCImmersiveScreen.java"
touch_file "main/java/com/xiaoze_qwq_/mis/client/gui/StatusScreen.java"
touch_file "main/java/com/xiaoze_qwq_/mis/command/MISCommand.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/MISNetwork.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/MCCOutputPacket.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/ImmersiveStatusPacket.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/OpenStatusScreenPacket.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/StatusDataPacket.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/StatusRequestPacket.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/ClientPacketHandlers.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/ImmersiveInputPacket.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/ImmersiveExitPacket.java"
touch_file "main/java/com/xiaoze_qwq_/mis/network/InstanceRemovedPacket.java"
touch_file "main/resources/pack.mcmeta"
touch_file "main/resources/META-INF/mods.toml"

echo "所有操作已完成。"