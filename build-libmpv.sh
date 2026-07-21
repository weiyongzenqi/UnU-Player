#!/bin/bash
# =============================================================
# 重编 libmpv-android AAR(启用 Vulkan HDR)
#
# 功能: 自动从 GitHub 克隆 libmpv-android 源码 → 打 Vulkan 补丁 → 编译 arm64 AAR → 替换到本项目
#
# 使用:
#   ./build-libmpv.sh              # 自动: 已有 ../libmpv-android 就用, 否则克隆 v1.0.0
#   ./build-libmpv.sh --latest     # 克隆 main 最新 commit 再编译
#   ./build-libmpv.sh --clone      # 强制重新克隆 v1.0.0(删旧的)
#   ./build-libmpv.sh --existing   # 只用已有 ../libmpv-android, 不克隆
#   ./build-libmpv.sh --clean      # 清理编译缓存后重编(不重新克隆)
#   ./build-libmpv.sh --help       # 帮助
#
# 与 libmpv-android 原版的差异(9 处):
#   1. buildscripts/scripts/libplacebo.sh: -Dvulkan=disabled → -Dvulkan=enabled
#   2. buildscripts/scripts/mpv.sh:       加 -Dvulkan=enabled
#   3. buildscripts/include/depinfo.sh:   dep_libplacebo=() → dep_libplacebo=(shaderc)
#   4. libmpv/build.gradle.kts:          abiFilters += "arm64-v8a"(单架构)
#   5. buildscripts/include/depinfo.sh:   mbedtls→openssl 版本/依赖/ffmpeg 后端
#   6. buildscripts/include/download-deps.sh: mbedtls clone→openssl clone
#   7. buildscripts/scripts/openssl.sh:  新建(ffmpeg TLS 后端 OpenSSL 编译)
#   8. buildscripts/scripts/ffmpeg.sh:   --enable-mbedtls→--enable-openssl
#   9. buildscripts/scripts/mbedtls.sh:  删除(mbedTLS 不再需要)
#
# 5-9: ffmpeg TLS 后端 mbedTLS→OpenSSL(修 https webdav 握手)
#
# 前提: tools/jdk21 + tools/android-sdk
# 耗时: ~25 分钟(中国网络直连无需代理)
# =============================================================

set -euo pipefail

# ─── 常量 ──────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LIBMPV_REPO="https://github.com/jarnedemeulemeester/libmpv-android.git"
LIBMPV_TAG="v1.0.0"
LIBMPV_LOCAL="$SCRIPT_DIR/../libmpv-android"
TARGET_AAR="$SCRIPT_DIR/tools/libmpv/maven/dev/jdtech/mpv/libmpv/1.0.0/libmpv-1.0.0.aar"
AAR_SRC="$LIBMPV_LOCAL/libmpv/build/outputs/aar/libmpv-release.aar"
BS="$LIBMPV_LOCAL/buildscripts"
BUILD_LOG="$BS/build-$(date +%Y%m%d-%H%M%S).log"

# ─── 输出 ──────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; CYAN='\033[0;36m'; NC='\033[0m'
info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()   { echo -e "${RED}[ERR]${NC}   $*"; }
die()   { err "$*"; exit 1; }
step()  { echo ""; echo -e "${GREEN}── $* ──${NC}"; }

# ─── 参数 ──────────────────────────────────────────────────
MODE="auto"; CLEAN=false; USE_LATEST=false
for arg in "$@"; do
    case "$arg" in
        --clone)    MODE="clone" ;;
        --latest)   USE_LATEST=true; MODE="clone" ;;
        --existing) MODE="existing" ;;
        --clean)    CLEAN=true ;;
        --help|-h)  head -30 "$0" | tail -22; exit 0 ;;
        *) die "未知参数: $arg (--help 查看用法)" ;;
    esac
done

# ═══════════════════════════════════════════════════════════
# Phase 1: 环境检测
# ═══════════════════════════════════════════════════════════
step "Phase 1: 环境检测"

# JDK
if [[ -x "$SCRIPT_DIR/tools/jdk21/bin/java" ]]; then
    export JAVA_HOME="$SCRIPT_DIR/tools/jdk21"
    export PATH="$JAVA_HOME/bin:$PATH"
    ok "JDK 21: $(java -version 2>&1 | head -1)"
else
    die "未找到 tools/jdk21/bin/java"
fi

# Android SDK
if [[ -d "$SCRIPT_DIR/tools/android-sdk/platforms" ]]; then
    export ANDROID_HOME="$SCRIPT_DIR/tools/android-sdk"
    export PATH="$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
    ok "Android SDK: $ANDROID_HOME"
else
    die "未找到 tools/android-sdk/platforms"
fi

# NDK r29
NDK_DIR=$(find "$ANDROID_HOME/ndk" -maxdepth 1 -type d -name "29.0*" 2>/dev/null | sort -V | tail -1)
if [[ -n "$NDK_DIR" ]]; then
    ok "NDK: $NDK_DIR"
else
    info "NDK 未安装, 将由 download-sdk.sh 自动下载(约 1.5GB)"
fi

# 系统工具
for cmd in git wget ninja; do
    command -v "$cmd" &>/dev/null || die "缺少 $cmd, 请安装: apt install $cmd"
done

# gperf
if ! command -v gperf &>/dev/null; then
    warn "gperf 未找到, 安装中..."
    sudo apt-get install -y gperf || die "gperf 安装失败"
fi

# meson(需要 >= 1.6.1)
if command -v meson &>/dev/null; then
    MESON_VER=$(meson --version)
    MESON_NUM=$(echo "$MESON_VER" | awk -F. '{print $1*10000+$2*100+$3}')
    if [[ "$MESON_NUM" -lt 10601 ]]; then
        warn "meson $MESON_VER 太旧(fontconfig 需 >= 1.6.1), 升级..."
        pip3 install --user --break-system-packages meson 2>/dev/null || \
        pip3 install --user meson 2>/dev/null || die "meson 升级失败"
        export PATH="$HOME/.local/bin:$PATH"
        ok "meson 升级到 $(meson --version)"
    else
        ok "meson: $MESON_VER"
    fi
else
    info "安装 meson..."
    pip3 install --user --break-system-packages meson 2>/dev/null || \
    pip3 install --user meson 2>/dev/null || die "meson 安装失败"
    export PATH="$HOME/.local/bin:$PATH"
    ok "meson: $(meson --version)"
fi

# ═══════════════════════════════════════════════════════════
# Phase 2: 获取源码
# ═══════════════════════════════════════════════════════════
step "Phase 2: 获取 libmpv-android 源码"

if $USE_LATEST; then
    info "克隆 main 分支最新 commit..."
    rm -rf "$LIBMPV_LOCAL"
    git clone "$LIBMPV_REPO" "$LIBMPV_LOCAL"  # 不 --depth, 可能需要 git history
    ok "克隆完成: $(cd "$LIBMPV_LOCAL" && git log --oneline -1)"
else
    case "$MODE" in
        clone)
            info "克隆 $LIBMPV_TAG..."
            rm -rf "$LIBMPV_LOCAL"
            git clone "$LIBMPV_REPO" "$LIBMPV_LOCAL" --branch "$LIBMPV_TAG" --depth 1
            ok "克隆完成: $LIBMPV_TAG"
            ;;
        existing)
            [[ -d "$BS" ]] || die "$LIBMPV_LOCAL 不存在或不完整"
            ok "使用已有: $LIBMPV_LOCAL"
            ;;
        auto)
            if [[ -d "$BS/scripts" ]]; then
                ok "检测到已有: $LIBMPV_LOCAL"
            else
                info "未检测到源码, 自动克隆 $LIBMPV_TAG..."
                git clone "$LIBMPV_REPO" "$LIBMPV_LOCAL" --branch "$LIBMPV_TAG" --depth 1
                ok "克隆完成"
            fi
            ;;
    esac
fi

# ═══════════════════════════════════════════════════════════
# Phase 3: 应用 Vulkan 补丁
# ═══════════════════════════════════════════════════════════
step "Phase 3: 应用 Vulkan 补丁"

# 补丁 1: libplacebo vulkan=enabled
if grep -q '\-Dvulkan=disabled' "$BS/scripts/libplacebo.sh"; then
    sed -i 's/-Dvulkan=disabled/-Dvulkan=enabled/' "$BS/scripts/libplacebo.sh"
    ok "[1/4] libplacebo.sh: vulkan=disabled → enabled"
elif grep -q '\-Dvulkan=enabled' "$BS/scripts/libplacebo.sh"; then
    ok "[1/4] libplacebo.sh: vulkan=enabled (已应用)"
else
    warn "[1/4] libplacebo.sh: 未找到 vulkan 配置行"
fi

# 补丁 2: mpv vulkan=enabled
if grep -q '\-Dvulkan=enabled' "$BS/scripts/mpv.sh"; then
    ok "[2/4] mpv.sh: vulkan=enabled (已应用)"
else
    sed -i '/-Dmanpage-build=disabled/a\\t-Dvulkan=enabled' "$BS/scripts/mpv.sh"
    ok "[2/4] mpv.sh: + vulkan=enabled"
fi

# 补丁 3: depinfo shaderc
if grep -q '^dep_libplacebo=(shaderc)' "$BS/include/depinfo.sh"; then
    ok "[3/4] depinfo.sh: dep_libplacebo=(shaderc) (已应用)"
else
    sed -i 's/^dep_libplacebo=()/dep_libplacebo=(shaderc)/' "$BS/include/depinfo.sh"
    ok "[3/4] depinfo.sh: dep_libplacebo=(shaderc)"
fi

# 补丁 4: abiFilters arm64 only
if grep -q 'abiFilters' "$LIBMPV_LOCAL/libmpv/build.gradle.kts"; then
    ok "[4/4] build.gradle.kts: abiFilters arm64 (已应用)"
else
    sed -i '/consumerProguardFiles/a\        ndk {\n            abiFilters += "arm64-v8a"\n        }' \
        "$LIBMPV_LOCAL/libmpv/build.gradle.kts"
    ok "[4/4] build.gradle.kts: + abiFilters arm64-v8a"
fi

# ═══════════════════════════════════════════════════════════
# Phase 3b: 应用 OpenSSL 补丁(ffmpeg TLS 后端 mbedTLS→OpenSSL)
# ═══════════════════════════════════════════════════════════
step "Phase 3b: 应用 OpenSSL 补丁"

# 补丁 5: depinfo.sh — mbedtls→openssl 版本/依赖/ffmpeg 后端
if grep -q '^v_openssl=' "$BS/include/depinfo.sh"; then
    ok "[5/9] depinfo.sh: v_openssl 已存在"
else
    sed -i 's/^v_mbedtls=.*/v_openssl=3.5.0/' "$BS/include/depinfo.sh"
    sed -i 's/^dep_mbedtls=()/dep_openssl=()/' "$BS/include/depinfo.sh"
    sed -i 's/^dep_ffmpeg=(mbedtls /dep_ffmpeg=(openssl /' "$BS/include/depinfo.sh"
    ok "[5/9] depinfo.sh: mbedtls→openssl"
fi

# 补丁 6: download-deps.sh — mbedtls clone→openssl clone
if grep -q 'openssl.git' "$BS/include/download-deps.sh"; then
    ok "[6/9] download-deps.sh: openssl clone 已存在"
else
    sed -i 's|mbedtls.git|openssl.git|' "$BS/include/download-deps.sh"
    sed -i 's|--branch v\$v_mbedtls --recurse-submodules|--branch openssl-\$v_openssl|' "$BS/include/download-deps.sh"
    sed -i 's|mbedtls.git mbedtls|openssl.git openssl|' "$BS/include/download-deps.sh"
    ok "[6/9] download-deps.sh: mbedtls clone→openssl clone"
fi

# 补丁 7: ffmpeg.sh — --enable-mbedtls→--enable-openssl
if grep -q 'mbedtls' "$BS/scripts/ffmpeg.sh"; then
    sed -i 's/mbedtls/openssl/g' "$BS/scripts/ffmpeg.sh"
    ok "[7/9] ffmpeg.sh: mbedtls→openssl"
elif grep -q 'openssl' "$BS/scripts/ffmpeg.sh"; then
    ok "[7/9] ffmpeg.sh: openssl (已应用)"
else
    warn "[7/9] ffmpeg.sh: 未找到 mbedtls/openssl 配置行"
fi

# 补丁 8: 新建 openssl.sh(若不存在)
if [[ -f "$BS/scripts/openssl.sh" ]]; then
    ok "[8/9] openssl.sh: 已存在"
else
    cat > "$BS/scripts/openssl.sh" << 'OPENSSL_EOF'
#!/bin/bash -e

. ../../include/depinfo.sh
. ../../include/path.sh

if [ "$1" == "build" ]; then
	true
elif [ "$1" == "clean" ]; then
	make clean 2>/dev/null || true
	exit 0
else
	exit 255
fi

# OpenSSL 自带 android target, 按 ndk_triple 选
case "$ndk_triple" in
	aarch64-linux-android) osl_target=android-arm64 ;;
	arm-linux-androideabi) osl_target=android-arm ;;
	x86_64-linux-android)  osl_target=android-x86_64 ;;
	i686-linux-android)    osl_target=android-x86 ;;
	*) echo "openssl: 未知 arch $ndk_triple"; exit 1 ;;
esac

# OpenSSL 的 android 配置靠 ANDROID_NDK_ROOT 定位工具链/sysroot
export ANDROID_NDK_ROOT="$DIR/sdk/android-sdk-$os/ndk/$v_ndk"
export ANDROID_API=26

./Configure "$osl_target" \
	-D__ANDROID_API__=26 \
	--prefix="$prefix_dir" \
	no-shared no-tests no-makedepend no-ssl3 no-comp \
	-j"$cores"

make -j"$cores"
make install_sw
OPENSSL_EOF
    chmod +x "$BS/scripts/openssl.sh"
    ok "[8/9] openssl.sh: 新建"
fi

# 补丁 9: 删除 mbedtls.sh(若存在)
if [[ -f "$BS/scripts/mbedtls.sh" ]]; then
    rm "$BS/scripts/mbedtls.sh"
    ok "[9/9] mbedtls.sh: 已删除"
else
    ok "[9/9] mbedtls.sh: 已无此文件"
fi

# ═══════════════════════════════════════════════════════════
# Phase 4: 配置编译环境
# ═══════════════════════════════════════════════════════════
step "Phase 4: 配置编译环境"

mkdir -p "$BS/sdk/bin"
ln -sf "$ANDROID_HOME" "$BS/sdk/android-sdk-linux"
ln -sf "$(command -v meson)" "$BS/sdk/bin/meson"
if [[ -x "$ANDROID_HOME/cmake/4.1.2/bin/cmake" ]]; then
    ln -sf "$ANDROID_HOME/cmake/4.1.2/bin/cmake" "$BS/sdk/bin/cmake"
fi
ln -sf "$(command -v ninja)" "$BS/sdk/bin/ninja"

# gas-preprocessor.pl
if [[ ! -f "$BS/sdk/bin/gas-preprocessor.pl" ]]; then
    info "下载 gas-preprocessor.pl..."
    wget -q "https://raw.githubusercontent.com/nicehash/nicehash-quickminer/main/scripts/gas-preprocessor.pl" \
         -O "$BS/sdk/bin/gas-preprocessor.pl" 2>/dev/null || \
    warn "gas-preprocessor.pl 下载失败(汇编优化可能不可用)"
fi
chmod +x "$BS/sdk/bin/gas-preprocessor.pl" 2>/dev/null || true

mkdir -p "$BS/deps/shaderc"
ok "编译环境就绪"

if $CLEAN; then
    info "清理编译缓存..."
    cd "$BS" && ./build.sh --arch arm64 clean 2>/dev/null || true
fi

# ═══════════════════════════════════════════════════════════
# Phase 5: 下载依赖源码
# ═══════════════════════════════════════════════════════════
step "Phase 5: 下载依赖源码"

cd "$BS"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/cmake/4.1.2/bin:$HOME/.local/bin:$PATH"

info "运行 download.sh(下载 deps + sdk)..."
# download.sh 在顶层, 内部调 include/download-deps.sh + include/download-sdk.sh。
# 注意: 上游脚本在 include/ 下, 不能直接 ./download-deps.sh(顶层无此文件)。
./download.sh 2>&1 | tail -8
ok "依赖源码就绪"

if [[ ! -d "$ANDROID_HOME/ndk/29.0.14206865" ]]; then
    info "NDK r29 未就绪, 单独下载..."
    yes | ./include/download-sdk.sh 2>&1 | tail -5
fi

# ═══════════════════════════════════════════════════════════
# Phase 6: 编译
# ═══════════════════════════════════════════════════════════
step "Phase 6: 编译 libmpv-android (arm64, 预计 20-40 分钟)"
info "完整日志: $BUILD_LOG"

BUILD_START=$(date +%s)

cd "$BS"
# tee 保留完整日志, tail 只显示最后 40 行
./build.sh --arch arm64 2>&1 | tee "$BUILD_LOG" | tail -40

# 检查编译是否成功(tee 的 exit code 就是 build.sh 的)
BUILD_EXIT=${PIPESTATUS[0]}
BUILD_END=$(date +%s)
BUILD_MIN=$(( (BUILD_END - BUILD_START) / 60 ))

if [[ "$BUILD_EXIT" -ne 0 ]]; then
    err "编译失败(exit code $BUILD_EXIT), 完整日志: $BUILD_LOG"
    err "常见原因: meson 版本太旧(需 >= 1.6.1) / 缺 gperf / NDK 路径不对 / 磁盘空间不足"
    exit "$BUILD_EXIT"
fi

ok "编译完成, 耗时约 ${BUILD_MIN} 分钟"

# ═══════════════════════════════════════════════════════════
# Phase 7: 验证 + 替换
# ═══════════════════════════════════════════════════════════
step "Phase 7: 验证产物"

[[ -f "$AAR_SRC" ]] || die "未找到 AAR: $AAR_SRC (编译可能成功但 Gradle 打包失败)"
AAR_SIZE=$(du -h "$AAR_SRC" | cut -f1)
ok "AAR: $AAR_SRC ($AAR_SIZE)"

# 提取 libmpv.so 验证 vulkan
TMP_DIR=$(mktemp -d)
trap "rm -rf $TMP_DIR" EXIT
unzip -j -o "$AAR_SRC" "jni/arm64-v8a/libmpv.so" -d "$TMP_DIR/" >/dev/null 2>&1

VK_COUNT=$(nm -D "$TMP_DIR/libmpv.so" 2>/dev/null | grep -c "vk" || echo "0")
NEEDS_VULKAN=$(readelf -d "$TMP_DIR/libmpv.so" 2>/dev/null | grep -c "libvulkan.so" || echo "0")

ok "vk 符号数: $VK_COUNT"
ok "libvulkan.so 依赖: $NEEDS_VULKAN"

if [[ "$VK_COUNT" -eq 0 ]] || [[ "$NEEDS_VULKAN" -eq 0 ]]; then
    err "==========================================="
    err " Vulkan 验证失败!"
    err " vk符号=$VK_COUNT  libvulkan依赖=$NEEDS_VULKAN"
    err " AAR 可能未启用 Vulkan, 请查看日志: $BUILD_LOG"
    err "==========================================="
    exit 1
fi

# 替换
echo ""
info "替换到 UnU-Player..."
mkdir -p "$(dirname "$TARGET_AAR")"
cp "$AAR_SRC" "$TARGET_AAR"
ok "已替换: $TARGET_AAR ($(du -h "$TARGET_AAR" | cut -f1))"

echo ""
echo -e "${GREEN}═══════════════════════════════════════════════════════════${NC}"
echo -e "${GREEN} 全部完成!${NC}"
echo -e "${GREEN} AAR: $TARGET_AAR${NC}"
echo -e "${GREEN} Vulkan: $VK_COUNT symbols, libvulkan.so linked${NC}"
echo -e "${GREEN} 构建 UnU-Player: ./gradlew :androidApp:assembleDebug${NC}"
echo -e "${GREEN}═══════════════════════════════════════════════════════════${NC}"
