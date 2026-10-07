#!/usr/bin/env bash
# =============================================================================
#  Compile ccminer (minage de Verus) pour Android avec le NDK.
#  Lancé automatiquement par GitHub Actions. Utilisable aussi sur un PC Linux :
#     ANDROID_NDK_HOME=/chemin/du/ndk ABI=arm64-v8a bash miner/build-android.sh
#
#  Résultat dans miner/out/ :
#     arm64-v8a-libccminer.so          programme de minage (téléphones 64 bits)
#     armeabi-v7a-libccminer.so        programme 32 bits (tentative, peut échouer)
#     armeabi-v7a-libplaceholder.so    bibliothèque vide : permet d'installer l'application
#                                      sur les téléphones 32 bits
# =============================================================================
set -euo pipefail

ABI="${ABI:?Indique ABI=arm64-v8a ou ABI=armeabi-v7a}"
NDK="${ANDROID_NDK_HOME:?Indique ANDROID_NDK_HOME (dossier du NDK)}"
VARIANTS="${VARIANTS:-optimized}"   # « compat » est impossible : le code de Verus exige les instructions de chiffrement ARMv8
API=26

CCMINER_REPO="${CCMINER_REPO:-https://github.com/Oink70/CCminer-ARM-optimized.git}"
CCMINER_REF="${CCMINER_REF:-main}"
OPENSSL_VERSION="${OPENSSL_VERSION:-3.0.15}"
CURL_VERSION="${CURL_VERSION:-8.9.1}"
JANSSON_VERSION="${JANSSON_VERSION:-2.14}"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$ROOT/miner/work/$ABI"
OUT="$ROOT/miner/out"
LOGS="$ROOT/miner/logs"
PREFIX="$WORK/prefix"
DEPS_MARKER="$PREFIX/deps-done.txt"
JOBS="$(nproc)"
mkdir -p "$WORK/src" "$PREFIX/lib" "$PREFIX/include/sys" "$OUT" "$LOGS"

TOOLCHAIN="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

# --- Réglages propres à chaque famille de processeurs -------------------------
case "$ABI" in
  arm64-v8a)
    HOST="aarch64-linux-android"
    CLANG_TARGET="aarch64-linux-android"
    OSSL_TARGET="android-arm64"
    OMP_DIR="aarch64"
    ARCH_OPT="-march=armv8-a+crypto -mtune=cortex-a53"
    ARCH_COMPAT="-march=armv8-a"
    ;;
  armeabi-v7a)
    HOST="arm-linux-androideabi"
    CLANG_TARGET="armv7a-linux-androideabi"
    OSSL_TARGET="android-arm"
    OMP_DIR="arm"
    ARCH_OPT="-march=armv8-a+crypto -mfpu=crypto-neon-fp-armv8 -mfloat-abi=softfp"
    ARCH_COMPAT="-march=armv7-a -mfpu=neon -mfloat-abi=softfp"
    ;;
  *)
    echo "ABI inconnue : $ABI" >&2
    exit 1
    ;;
esac

export CC="$TOOLCHAIN/bin/${CLANG_TARGET}${API}-clang"
export CXX="$TOOLCHAIN/bin/${CLANG_TARGET}${API}-clang++"
export AR="$TOOLCHAIN/bin/llvm-ar"
export RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
export STRIP="$TOOLCHAIN/bin/llvm-strip"
READELF="$TOOLCHAIN/bin/llvm-readelf"

# 32 bits : on cherche des options avec lesquelles le compilateur sait VRAIMENT compiler
# les fonctions de chiffrement ARM (AES et multiplication sans retenue). On essaie de
# compiler un petit programme de test avec chaque jeu d'options.
ARM32_OK=1
if [ "$ABI" = "armeabi-v7a" ]; then
  ARM32_OK=0
  cat > "$WORK/probe.c" <<'PROBE'
#include <arm_neon.h>
poly128_t t_mul(poly64_t a, poly64_t b) { return vmull_p64(a, b); }
uint8x16_t t_aes(uint8x16_t a, uint8x16_t b) { return vaesmcq_u8(vaeseq_u8(a, b)); }
PROBE
  n=0
  for cand in \
    "-march=armv8-a+crypto -mfpu=crypto-neon-fp-armv8 -mfloat-abi=softfp" \
    "-mcpu=cortex-a53 -mfpu=crypto-neon-fp-armv8 -mfloat-abi=softfp" \
    "-march=armv8-a+crypto -mfloat-abi=softfp" \
    "-march=armv8-a+crypto -mfpu=neon-fp-armv8 -mfloat-abi=softfp" \
    "-march=armv8-a -mfpu=crypto-neon-fp-armv8 -mfloat-abi=softfp"; do
    n=$((n + 1))
    echo "options : $cand" > "$LOGS/probe-$n.log"
    # shellcheck disable=SC2086
    if "$CC" -c -x c "$WORK/probe.c" -o "$WORK/probe.o" $cand >> "$LOGS/probe-$n.log" 2>&1; then
      ARCH_OPT="$cand"
      ARM32_OK=1
      break
    fi
  done
  if [ "$ARM32_OK" = 1 ]; then
    echo "Options 32 bits retenues : $ARCH_OPT"
  else
    echo "Aucune option 32 bits ne permet de compiler les fonctions de chiffrement ARM."
  fi
fi

COMMON_FLAGS="-O3 -ffast-math -funroll-loops -finline-functions -fomit-frame-pointer -fno-stack-protector -D_REENTRANT -fpic -pthread"

# OpenMP : s'il est disponible on l'intègre au programme (sinon il dépendrait
# de libomp.so, absent des téléphones). Sinon on le désactive.
if [ -n "$(find "$TOOLCHAIN/lib" -path "*/linux/$OMP_DIR/libomp.a" 2>/dev/null | head -n 1)" ]; then
  OMP_LDFLAGS="-static-openmp"
  OMP_CONF=""
else
  OMP_LDFLAGS=""
  OMP_CONF="ac_cv_prog_c_openmp=unsupported ac_cv_prog_cxx_openmp=unsupported"
fi

fetch() {  # fetch <fichier-de-sortie> <url> [url de secours...]
  local out="$1"; shift
  local url
  for url in "$@"; do
    if curl -fsSL --retry 3 -o "$out" "$url"; then return 0; fi
    echo "Échec du téléchargement : $url" >&2
  done
  return 1
}

# Contrôles : le programme ne doit dépendre que de bibliothèques présentes sur tout Android
validate() {
  local f="$1" needed bad
  needed="$("$READELF" -d "$f" | grep NEEDED || true)"
  echo "Dépendances du programme :"; echo "$needed"
  bad="$(echo "$needed" | grep -Ev '\[lib(c|m|dl|log|z)\.so\]' | grep -v '^$' || true)"
  if [ -n "$bad" ]; then
    echo "ERREUR : dépendances absentes d'Android :" >&2
    echo "$bad" >&2
    return 1
  fi
  if grep -aq "com.termux" "$f"; then
    echo "ERREUR : le programme référence Termux" >&2
    return 1
  fi
  "$READELF" -h "$f" | grep -E 'Class|Machine'
}

# --- Bibliothèques nécessaires (compilées une seule fois par processeur) -------
build_deps() {
  # Quelques programmes cherchent -lpthread, qui n'existe pas sous Android : on crée une bibliothèque vide
  "$AR" rcs "$PREFIX/lib/libpthread.a"
  # <sys/sysctl.h> n'existe pas sous Android (même astuce que Termux)
  echo '#include <linux/sysctl.h>' > "$PREFIX/include/sys/sysctl.h"

  cd "$WORK/src"

  echo "=== OpenSSL $OPENSSL_VERSION ==="
  fetch openssl.tar.gz \
    "https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz" \
    "https://www.openssl.org/source/openssl-${OPENSSL_VERSION}.tar.gz" \
    "https://www.openssl.org/source/old/3.0/openssl-${OPENSSL_VERSION}.tar.gz"
  rm -rf openssl-src && mkdir openssl-src && tar xf openssl.tar.gz -C openssl-src --strip-components=1
  (
    cd openssl-src
    unset CC CXX AR RANLIB STRIP
    export ANDROID_NDK_ROOT="$NDK"
    export PATH="$TOOLCHAIN/bin:$PATH"
    ./Configure "$OSSL_TARGET" -D__ANDROID_API__="$API" no-shared no-tests no-ui-console no-comp \
      --prefix="$PREFIX" --openssldir="$PREFIX/ssl" --libdir=lib
    make -j"$JOBS" build_libs
    make install_dev
  )

  echo "=== jansson $JANSSON_VERSION ==="
  fetch jansson.tar.bz2 \
    "https://github.com/akheron/jansson/releases/download/v${JANSSON_VERSION}/jansson-${JANSSON_VERSION}.tar.bz2"
  rm -rf jansson-src && mkdir jansson-src && tar xf jansson.tar.bz2 -C jansson-src --strip-components=1
  (
    cd jansson-src
    ./configure --host="$HOST" --prefix="$PREFIX" --enable-static --disable-shared \
      CFLAGS="-O2 -fPIC"
    make -j"$JOBS"
    make install
  )

  echo "=== curl $CURL_VERSION ==="
  fetch curl.tar.gz "https://curl.se/download/curl-${CURL_VERSION}.tar.gz"
  rm -rf curl-src && mkdir curl-src && tar xf curl.tar.gz -C curl-src --strip-components=1
  (
    cd curl-src
    ./configure --host="$HOST" --prefix="$PREFIX" --disable-shared --enable-static \
      --with-openssl="$PREFIX" --with-ca-path=/system/etc/security/cacerts \
      --without-libpsl --without-zlib --without-brotli --without-zstd --without-nghttp2 \
      --without-libidn2 --without-librtmp --without-libssh2 \
      --disable-ldap --disable-ldaps --disable-manual --disable-docs \
      CPPFLAGS="-I$PREFIX/include" CFLAGS="-O2 -fPIC" LDFLAGS="-L$PREFIX/lib" LIBS="-ldl"
    make -j"$JOBS"
    make install
  )

  # Le code de ccminer a besoin de la macro LIBCURL_CHECK_CONFIG (fichier libcurl.m4)
  if [ ! -f "$PREFIX/share/aclocal/libcurl.m4" ]; then
    mkdir -p "$PREFIX/share/aclocal"
    if [ -f "$WORK/src/curl-src/docs/libcurl/libcurl.m4" ]; then
      cp "$WORK/src/curl-src/docs/libcurl/libcurl.m4" "$PREFIX/share/aclocal/libcurl.m4"
    else
      fetch "$PREFIX/share/aclocal/libcurl.m4" \
        "https://raw.githubusercontent.com/curl/curl/curl-${CURL_VERSION//./_}/docs/libcurl/libcurl.m4"
    fi
  fi

  touch "$DEPS_MARKER"
}

# --- Une variante de ccminer ----------------------------------------------------
build_variant() {
  local variant="$1" arch_flags out_name
  case "$variant" in
    optimized) arch_flags="$ARCH_OPT";    out_name="$ABI-libccminer.so" ;;
    compat)    arch_flags="$ARCH_COMPAT"; out_name="$ABI-libccminer_compat.so" ;;
    *) echo "Variante inconnue : $variant" >&2; exit 1 ;;
  esac

  local dir="$WORK/build-$variant"
  rm -rf "$dir"
  cp -r "$WORK/ccminer-src" "$dir"
  cd "$dir"

  # Le dépôt contient des fichiers déjà générés (dont un ancien programme) : on les enlève
  rm -rf ccminer autom4te.cache config.status config.log Makefile Makefile.in \
         configure aclocal.m4 ccminer-config.h stamp-h1
  find . -name '*.o' -delete
  find . -name '.deps' -type d -prune -exec rm -rf {} +

  # 32 bits : sse2neon.h n'accepte que l'ARMv7 exactement ; on l'autorise pour l'ARMv8 en mode 32 bits
  if [ "$ABI" = "armeabi-v7a" ]; then
    find . -name 'sse2neon.h' -exec sed -i 's/__ARM_ARCH == 7/__ARM_ARCH >= 7/g' {} +
  fi

  # Macro LIBCURL_CHECK_CONFIG : fournie à aclocal par acinclude.m4
  cp "$PREFIX/share/aclocal/libcurl.m4" acinclude.m4

  chmod +x autogen.sh
  ./autogen.sh

  # Fichiers config.sub / config.guess récents (reconnaissent Android)
  local f src
  for f in config.sub config.guess; do
    src="$(ls /usr/share/automake-*/"$f" 2>/dev/null | tail -n1 || true)"
    if [ -n "$src" ]; then cp "$src" "$f"; fi
  done

  # En-tête de compatibilité : ce que le système Android ne fournit pas
  cat > "$WORK/android-compat.h" <<'HDR'
/* Compatibilité Android (bionic) pour ccminer */
#ifndef ANDROID_COMPAT_H
#define ANDROID_COMPAT_H
#include <endian.h>
#include <pthread.h>

/* Android n'a pas l'annulation de threads POSIX : fonctions neutres à la place */
#ifndef PTHREAD_CANCEL_ASYNCHRONOUS
#define PTHREAD_CANCEL_ENABLE 0
#define PTHREAD_CANCEL_DISABLE 1
#define PTHREAD_CANCEL_DEFERRED 0
#define PTHREAD_CANCEL_ASYNCHRONOUS 1
static inline int pthread_setcancelstate(int state, int *old) { (void)state; if (old) *old = 0; return 0; }
static inline int pthread_setcanceltype(int type, int *old) { (void)type; if (old) *old = 0; return 0; }
static inline void pthread_testcancel(void) {}
static inline int pthread_cancel(pthread_t t) { (void)t; return 0; }
#endif

/* Les processeurs ARM d'Android sont little-endian */
#ifndef htole16
#define htole16(x) (x)
#endif
#ifndef htole32
#define htole32(x) (x)
#endif
#ifndef htole64
#define htole64(x) (x)
#endif
#ifndef le16toh
#define le16toh(x) (x)
#endif
#ifndef le32toh
#define le32toh(x) (x)
#endif
#ifndef le64toh
#define le64toh(x) (x)
#endif
#ifndef htobe16
#define htobe16(x) __builtin_bswap16(x)
#endif
#ifndef htobe32
#define htobe32(x) __builtin_bswap32(x)
#endif
#ifndef htobe64
#define htobe64(x) __builtin_bswap64(x)
#endif
#ifndef be16toh
#define be16toh(x) __builtin_bswap16(x)
#endif
#ifndef be32toh
#define be32toh(x) __builtin_bswap32(x)
#endif
#ifndef be64toh
#define be64toh(x) __builtin_bswap64(x)
#endif

/* ARM 32 bits : quelques fonctions NEON propres au 64 bits, réécrites */
#if defined(__arm__) && !defined(__aarch64__) && defined(__ARM_NEON)
#include <arm_neon.h>
#ifndef vcopyq_laneq_s32
#define vcopyq_laneq_s32(a, i, b, j) vsetq_lane_s32(vgetq_lane_s32((b), (j)), (a), (i))
#endif
static inline uint8x16_t vqtbl1q_u8(uint8x16_t t, uint8x16_t idx) {
    uint8x8x2_t tbl;
    tbl.val[0] = vget_low_u8(t);
    tbl.val[1] = vget_high_u8(t);
    return vcombine_u8(vtbl2_u8(tbl, vget_low_u8(idx)), vtbl2_u8(tbl, vget_high_u8(idx)));
}
#endif
#endif
HDR

  local flags="$COMMON_FLAGS $arch_flags -w -I$PREFIX/include -include $WORK/android-compat.h"
  local asflags="$COMMON_FLAGS $arch_flags"
  # shellcheck disable=SC2086
  ./configure --host="$HOST" --with-libcurl="$PREFIX" \
    CC="$CC" CXX="$CXX" \
    CPPFLAGS="-I$PREFIX/include" \
    CFLAGS="$flags" CXXFLAGS="$flags" CCASFLAGS="$asflags" \
    LDFLAGS="-L$PREFIX/lib -static-libstdc++ $OMP_LDFLAGS" \
    LIBS="-lssl -lcrypto -ldl -lm" \
    PKG_CONFIG_LIBDIR="$PREFIX/lib/pkgconfig" \
    $OMP_CONF

  make -k -j"$JOBS"
  test -f ccminer

  "$STRIP" --strip-unneeded ccminer
  validate ccminer
  cp ccminer "$OUT/$out_name"
  echo ">>> OK : $OUT/$out_name"
}

# --- 32 bits : la compilation de ccminer n'est qu'une tentative -----------------
# (le code de Verus vise le 64 bits). Quoi qu'il arrive, on fournit une bibliothèque vide :
# elle permet d'installer l'application sur les téléphones 32 bits ; sans programme de
# minage, l'application y affiche un message clair au lieu d'être refusée à l'installation.
make_placeholder() {
  echo 'int verusfarm_placeholder(void) { return 0; }' > "$WORK/placeholder.c"
  "$CC" -shared -fPIC -o "$OUT/armeabi-v7a-libplaceholder.so" "$WORK/placeholder.c"
  "$STRIP" --strip-unneeded "$OUT/armeabi-v7a-libplaceholder.so"
  echo "Bibliothèque vide créée pour armeabi-v7a."
}

give_up() {
  if [ "$ABI" = "armeabi-v7a" ]; then
    echo "::warning::Pas de programme de minage 32 bits cette fois (voir le résumé)."
    make_placeholder
    exit 0
  fi
  exit 1
}

# --- Modes appelés par la boucle plus bas (chacun dans son propre processus) ---
case "${1:-all}" in
  deps)    build_deps; exit 0 ;;
  variant) build_variant "${2:?variante manquante}"; exit 0 ;;
esac

# Écrit les dernières lignes d'un journal dans le résumé de la page GitHub
summarize_failure() {  # summarize_failure <titre> <journal> [config.log]
  {
    echo "### Échec : $1"
    echo "Toutes les erreurs trouvées :"
    echo '```'
    grep -aE ' error: |fatal error|undefined reference|\*\*\* \[|configure: error' "$2" | sort -u | head -n 60 || true
    echo '```'
    echo "Dernières lignes du journal :"
    echo '```'
    tail -n 25 "$2" || true
    echo '```'
    if [ -n "${3:-}" ] && [ -f "$3" ]; then
      echo "Fin de config.log :"
      echo '```'
      tail -n 30 "$3" || true
      echo '```'
    fi
  } | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
}

# 32 bits sans option de chiffrement ARM : inutile d'essayer
if [ "$ABI" = "armeabi-v7a" ] && [ "$ARM32_OK" != 1 ]; then
  {
    echo "### 32 bits : le compilateur ne sait pas compiler les fonctions de chiffrement ARM"
    echo "Compilateur : $("$CC" --version | head -n 1)"
    for f in "$LOGS"/probe-*.log; do
      echo '```'
      head -n 7 "$f"
      echo '```'
    done
  } | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
  give_up
fi

# Affiche un extrait du code source dans le résumé (pour comprendre une erreur de compilation)
excerpt() {  # excerpt <variante> <fichier> <début> <fin>
  local f="$WORK/build-$1/$2"
  if [ -f "$f" ]; then
    {
      echo "Extrait de $2 (lignes $3 à $4) :"
      echo '```'
      sed -n "$3,$4p" "$f" | cut -c1-200
      echo '```'
    } | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
  fi
}

# --- Bibliothèques (une fois par processeur) -----------------------------------
if [ ! -f "$DEPS_MARKER" ]; then
  if ! bash "${BASH_SOURCE[0]}" deps 2>&1 | tee "$LOGS/$ABI-deps.log"; then
    summarize_failure "bibliothèques pour $ABI" "$LOGS/$ABI-deps.log"
    give_up
  fi
fi
if [ ! -f "$DEPS_MARKER" ]; then
  echo "Les bibliothèques n'ont pas été compilées correctement." >&2
  give_up
fi

# --- Code source de ccminer ----------------------------------------------------
echo "=== Code source : $CCMINER_REPO ($CCMINER_REF) ==="
rm -rf "$WORK/ccminer-src" && mkdir -p "$WORK/ccminer-src"
(
  cd "$WORK/ccminer-src"
  git init -q
  git remote add origin "$CCMINER_REPO"
  git fetch -q --depth 1 origin "$CCMINER_REF"
  git checkout -q FETCH_HEAD
  echo "$CCMINER_REPO $(git rev-parse HEAD)" > "$OUT/$ABI-source.txt"
)

# --- Compilation de chaque variante (une qui échoue n'arrête pas l'autre) ------
ok=0
for v in $VARIANTS; do
  if bash "${BASH_SOURCE[0]}" variant "$v" 2>&1 | tee "$LOGS/$ABI-$v.log"; then
    ok=1
  else
    echo "::warning::La variante '$v' pour $ABI n'a pas pu être compilée (voir le résumé et les journaux)."
    summarize_failure "$ABI, variante $v" "$LOGS/$ABI-$v.log" "$WORK/build-$v/config.log"
    if [ "$ABI" = "armeabi-v7a" ]; then
      excerpt "$v" sse2neon.h 80 100
      excerpt "$v" sse2neon.h 4670 4690
      excerpt "$v" verus/verus_clhash_portable.cpp 55 80
      excerpt "$v" verus/verus_clhash_portable.cpp 285 310
    fi
  fi
done

if [ "$ok" -ne 1 ]; then
  echo "Aucune variante compilée pour $ABI." >&2
  give_up
fi
if [ "$ABI" = "armeabi-v7a" ]; then make_placeholder; fi
ls -l "$OUT"
