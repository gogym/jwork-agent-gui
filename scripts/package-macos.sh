#!/usr/bin/env bash
# Jwork macOS 打包脚本（镜像 package-windows.ps1 的 Maven/暂存/jpackage 主干）
# 用法（必须在 macOS 真机执行，jpackage 不能跨平台编译）:
#   bash scripts/package-macos.sh
# 依赖:
#   - JDK 21（含 jpackage）：JAVA_HOME 指向它，或 jpackage/java 在 PATH
#   - mac 自带工具链：sips / iconutil / PlistBuddy / hdiutil（jpackage 内部调用）
# 可选签名与公证（缺省跳过，产出未签名开发包）:
#   MAC_SIGN_IDENTITY="Developer ID Application: xxx"   阶段 A 签名 app-image
#   MAC_NOTARIZE_PROFILE=<notarytool keychain profile>  dmg 产出后公证并 staple
# 开发运行注意: mac 上 mvn javafx:run 缺 sun.lwawt 两条 opens（插件配置为 Windows 共用，
#   不加 mac 专属参数以免 Windows 启动刷警告），请统一用 IDEA 的 "Jwork Agent (Debug macOS)" 运行配置。
set -euo pipefail

APP_NAME="Jwork"
MAIN_CLASS="com.gettyio.jwork.agent.ui.Launcher"
BUNDLE_ID="io.gettyio.jwork"

REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
UI_DIR="$REPO_ROOT/jwork-agent-ui"
TARGET_DIR="$UI_DIR/target"
LIB_DIR="$TARGET_DIR/lib"
STAGE_DIR="$TARGET_DIR/package"
MAC_BUILD_DIR="$REPO_ROOT/build/mac"
OUT_DIR="$REPO_ROOT/build/installer"

# ---- 0 前置检查 ----
if [[ "$(uname)" != "Darwin" ]]; then
    echo "错误: 本脚本只能在 macOS 上执行（jpackage 不能跨平台编译）" >&2
    exit 1
fi
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/jpackage" ]]; then
    JPACKAGE="$JAVA_HOME/bin/jpackage"
    JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v jpackage >/dev/null 2>&1; then
    JPACKAGE="$(command -v jpackage)"
    JAVA_BIN="$(command -v java)"
else
    echo "错误: 未找到 jpackage，请设置 JAVA_HOME 指向 JDK 21" >&2
    exit 1
fi
JAVA_MAJOR="$("$JAVA_BIN" -version 2>&1 | head -1 | sed -E 's/.*version "([0-9]+).*/\1/')"
if [[ "$JAVA_MAJOR" != "21" ]]; then
    echo "错误: 需要 JDK 21（当前主版本 $JAVA_MAJOR），请检查 JAVA_HOME" >&2
    exit 1
fi
# 架构后缀与 OS 激活 profile 同源：arm64 JDK 带 macosx-arm64 natives，
# Rosetta 下的 x86_64 JDK 带 macosx-amd64 natives、整套跑 Rosetta，自洽
case "$(uname -m)" in
    arm64)  ARCH_SUFFIX="arm64" ;;
    x86_64) ARCH_SUFFIX="x64" ;;
    *) echo "错误: 不支持的架构 $(uname -m)" >&2; exit 1 ;;
esac

echo "== 1/6 Maven 打包 (-Ppackage，OS profile 自动带入本平台 natives) =="
(cd "$REPO_ROOT" && mvn -q -Ppackage clean package -pl jwork-agent-ui -am)

# 版本号单一来源：构建产物中 Maven 过滤后的 version.properties，去 -SNAPSHOT 供 jpackage 使用
VERSION_FILE="$TARGET_DIR/classes/version.properties"
[[ -f "$VERSION_FILE" ]] || { echo "错误: 未找到过滤后的 version.properties: $VERSION_FILE" >&2; exit 1; }
APP_VERSION="$(grep '^app\.version=' "$VERSION_FILE" | head -1 | sed -E 's/^app\.version=//; s/-SNAPSHOT$//' | tr -d '[:space:]')"
[[ -n "$APP_VERSION" ]] || { echo "错误: 无法从 version.properties 解析版本号" >&2; exit 1; }
echo "应用版本: $APP_VERSION（架构 $ARCH_SUFFIX）"

echo "== 2/6 组装扁平 classpath 暂存目录 =="
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/lib"
MAIN_JAR="$(find "$TARGET_DIR" -maxdepth 1 -name 'jwork-agent-ui-*.jar' ! -name '*sources*' ! -name '*javadoc*' | head -1)"
[[ -n "$MAIN_JAR" ]] || { echo "错误: 未找到主 jar: $TARGET_DIR/jwork-agent-ui-*.jar" >&2; exit 1; }
cp "$MAIN_JAR" "$STAGE_DIR/"
cp "$LIB_DIR"/* "$STAGE_DIR/lib/"
echo "主 jar: $(basename "$MAIN_JAR")"

echo "== 3/6 生成应用图标 icns =="
mkdir -p "$MAC_BUILD_DIR"
ICNS="$MAC_BUILD_DIR/Jwork.icns"
ICON_SRC_DIR="$UI_DIR/src/main/resources/icons"
ICONSET="$MAC_BUILD_DIR/Jwork.iconset"
rm -rf "$ICONSET" "$ICNS"
mkdir -p "$ICONSET"
# 源：brand-mark.png（512x512，仓库最大 PNG 源）；小尺寸直取现成 app-icon PNG，
# 512/1024 由 sips 缩放（1024 为 512 上采样，视觉可接受；有更高分辨率源时替换）
cp "$ICON_SRC_DIR/app-icon-16.png"  "$ICONSET/icon_16x16.png"
cp "$ICON_SRC_DIR/app-icon-32.png"  "$ICONSET/icon_16x16@2x.png"
cp "$ICON_SRC_DIR/app-icon-32.png"  "$ICONSET/icon_32x32.png"
cp "$ICON_SRC_DIR/app-icon-64.png"  "$ICONSET/icon_32x32@2x.png"
cp "$ICON_SRC_DIR/app-icon-128.png" "$ICONSET/icon_128x128.png"
cp "$ICON_SRC_DIR/app-icon-256.png" "$ICONSET/icon_128x128@2x.png"
cp "$ICON_SRC_DIR/app-icon-256.png" "$ICONSET/icon_256x256.png"
sips -z 512 512 "$ICON_SRC_DIR/brand-mark.png" --out "$ICONSET/icon_256x256@2x.png" >/dev/null
cp "$ICONSET/icon_256x256@2x.png" "$ICONSET/icon_512x512.png"
sips -z 1024 1024 "$ICON_SRC_DIR/brand-mark.png" --out "$ICONSET/icon_512x512@2x.png" >/dev/null
iconutil -c icns "$ICONSET" -o "$ICNS"
rm -rf "$ICONSET"

echo "== 4/6 jpackage 阶段 A：app-image =="
# jpackage 不覆盖已存在产物：重复打包前清理（应用需已退出，否则 .app 被占用清理失败）
rm -rf "$MAC_BUILD_DIR/$APP_NAME.app"
rm -f "$MAC_BUILD_DIR/$APP_NAME-$APP_VERSION.dmg"
# java-options 与 win 六条对齐 + mac 专属两条 opens（jcefmaven 官方 JDK16+ 要求）
JAVA_OPTIONS=(
    "-Xmx512m"
    "-Dfile.encoding=UTF-8"
    "--add-exports=java.base/java.lang=ALL-UNNAMED"
    "--add-exports=java.desktop/sun.awt=ALL-UNNAMED"
    "--add-exports=java.desktop/sun.java2d=ALL-UNNAMED"
    "--add-opens=java.desktop/sun.awt=ALL-UNNAMED"
    "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED"
    "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED"
)
SIGN_ARGS=()
if [[ -n "${MAC_SIGN_IDENTITY:-}" ]]; then
    echo "签名: $MAC_SIGN_IDENTITY"
    SIGN_ARGS=(--mac-sign --mac-signing-key-user-name "$MAC_SIGN_IDENTITY"
               --mac-entitlements "$REPO_ROOT/scripts/mac/entitlements.plist")
fi
JPACKAGE_IMAGE_ARGS=(
    --type app-image
    --name "$APP_NAME"
    --app-version "$APP_VERSION"
    --input "$STAGE_DIR"
    --main-jar "$(basename "$MAIN_JAR")"
    --main-class "$MAIN_CLASS"
    --icon "$ICNS"
    --mac-package-identifier "$BUNDLE_ID"
    --dest "$MAC_BUILD_DIR"
)
for opt in "${JAVA_OPTIONS[@]}"; do
    JPACKAGE_IMAGE_ARGS+=(--java-options "$opt")
done
# 空数组展开用 +保护：mac 自带 bash 3.2 在 set -u 下对空数组 "${arr[@]}" 报 unbound variable
"$JPACKAGE" "${JPACKAGE_IMAGE_ARGS[@]}" ${SIGN_ARGS[@]+"${SIGN_ARGS[@]}"}
APP_IMAGE="$MAC_BUILD_DIR/$APP_NAME.app"
[[ -d "$APP_IMAGE" ]] || { echo "错误: app-image 未产出: $APP_IMAGE" >&2; exit 1; }

echo "== 5/6 注入麦克风隐私声明（阶段 B） =="
# 两阶段打包避开自定义 Info.plist 模板的占位符陷阱：先出 app-image，再向真实 plist 注入
/usr/libexec/PlistBuddy -c "Delete :NSMicrophoneUsageDescription" "$APP_IMAGE/Contents/Info.plist" 2>/dev/null || true
/usr/libexec/PlistBuddy -c "Add :NSMicrophoneUsageDescription string 语音输入转写需要麦克风" "$APP_IMAGE/Contents/Info.plist"

echo "== 6/6 jpackage 阶段 C：从既有 app-image 出 dmg =="
"$JPACKAGE" --type dmg --app-image "$APP_IMAGE" --dest "$MAC_BUILD_DIR"
DMG="$MAC_BUILD_DIR/$APP_NAME-$APP_VERSION.dmg"
[[ -f "$DMG" ]] || { echo "错误: dmg 未产出: $DMG" >&2; exit 1; }

# 可选公证：notarytool 提交并等待，通过后 staple 到 dmg
if [[ -n "${MAC_NOTARIZE_PROFILE:-}" ]]; then
    if [[ -z "${MAC_SIGN_IDENTITY:-}" ]]; then
        echo "警告: 未签名包无法公证，跳过（需先设置 MAC_SIGN_IDENTITY）" >&2
    else
        echo "公证: profile=$MAC_NOTARIZE_PROFILE"
        xcrun notarytool submit "$DMG" --keychain-profile "$MAC_NOTARIZE_PROFILE" --wait
        xcrun stapler staple "$DMG"
    fi
fi

# 产物归档：带架构后缀（更新下载 URL 由服务端下发，客户端仅上报平台/架构，命名约定留服务端对齐）
mkdir -p "$OUT_DIR"
FINAL_DMG="$OUT_DIR/$APP_NAME-$APP_VERSION-macos-$ARCH_SUFFIX.dmg"
rm -f "$FINAL_DMG"
mv "$DMG" "$FINAL_DMG"
echo "打包完成，产物位于: $FINAL_DMG"
if [[ -z "${MAC_SIGN_IDENTITY:-}" ]]; then
    echo "提示: 未签名包首次打开会被 Gatekeeper 拦截——右键点应用选「打开」，或执行: xattr -cr \"$APP_IMAGE\""
fi