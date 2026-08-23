#!/bin/sh

# 获取脚本自身的文件名（仅文件名，不包含路径）
script_name=$(basename "$0")
output_name="output.txt"

# 删除可能存在的旧输出文件，避免干扰（重定向会立即新建，但我们可以用 -path 排除它）
rm -f "$output_name"

# 扫描所有文件，精确排除脚本和输出文件
find . -type f \
  ! -path "./$script_name" \
  ! -path "./$output_name" \
  -exec sh -c '
    for file; do
      printf "%s\n" "$file"   # 输出文件名（相对路径）
      cat "$file"             # 输出文件内容
      echo                    # 空行分隔
    done
  ' _ {} + > "$output_name"