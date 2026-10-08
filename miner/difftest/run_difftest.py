#!/usr/bin/env python3
"""Compare les fonctions « SSE » émulées par sse2neon sur ARM 32 bits avec les vraies instructions x86.

Même entrées pseudo-aléatoires des deux côtés ; pour chaque fonction on calcule une somme de contrôle des
résultats. Si les sommes diffèrent, la fonction ARM donne un résultat faux : c'est probablement ce qui fait
rejeter les parts par Luckpool.
"""
import argparse, os, re, subprocess, sys

T2 = ("and_si128 or_si128 xor_si128 andnot_si128 add_epi8 add_epi16 add_epi32 add_epi64 sub_epi8 sub_epi16 "
      "sub_epi32 sub_epi64 adds_epi8 adds_epi16 adds_epu8 adds_epu16 subs_epi8 subs_epi16 subs_epu8 subs_epu16 "
      "mullo_epi16 mullo_epi32 mulhi_epi16 mulhi_epu16 mul_epu32 mul_epi32 madd_epi16 mulhrs_epi16 avg_epu8 "
      "avg_epu16 sad_epu8 cmpeq_epi8 cmpeq_epi16 cmpeq_epi32 cmpeq_epi64 cmpgt_epi8 cmpgt_epi16 cmpgt_epi32 "
      "cmplt_epi8 cmplt_epi16 cmplt_epi32 min_epu8 max_epu8 min_epi16 max_epi16 min_epi32 max_epi32 min_epu32 "
      "max_epu32 min_epu16 max_epu16 min_epi8 max_epi8 unpacklo_epi8 unpacklo_epi16 unpacklo_epi32 "
      "unpacklo_epi64 unpackhi_epi8 unpackhi_epi16 unpackhi_epi32 unpackhi_epi64 packs_epi16 packs_epi32 "
      "packus_epi16 packus_epi32 shuffle_epi8 aesenc_si128 aesenclast_si128 aesdec_si128 aesdeclast_si128 "
      "sll_epi16 sll_epi32 sll_epi64 srl_epi16 srl_epi32 srl_epi64 sra_epi16 sra_epi32 hadd_epi16 hadd_epi32 "
      "hsub_epi16 hsub_epi32 sign_epi8 sign_epi16 sign_epi32").split()
T1 = ("abs_epi8 abs_epi16 abs_epi32 cvtepu8_epi16 cvtepu8_epi32 cvtepu8_epi64 cvtepu16_epi32 cvtepu16_epi64 "
      "cvtepu32_epi64 cvtepi8_epi16 cvtepi8_epi32 cvtepi8_epi64 cvtepi16_epi32 cvtepi16_epi64 "
      "cvtepi32_epi64").split()
TI1 = {"slli_epi16": [1, 7, 15], "slli_epi32": [1, 13, 31], "slli_epi64": [1, 13, 63],
       "srli_epi16": [1, 7, 15], "srli_epi32": [1, 13, 31], "srli_epi64": [1, 13, 63],
       "srai_epi16": [3, 15], "srai_epi32": [5, 31], "slli_si128": [1, 4, 8, 15], "srli_si128": [1, 4, 8, 15],
       "shuffle_epi32": [0x1B, 0x4E, 0xE4, 0x39, 0xB1], "shufflelo_epi16": [0x1B, 0xE4],
       "shufflehi_epi16": [0x1B, 0xE4]}
TI2 = {"alignr_epi8": [1, 5, 8, 15], "clmulepi64_si128": [0x00, 0x01, 0x10, 0x11], "blend_epi16": [0x5A, 0xF0]}
T3 = ["blendv_epi8"]
S1 = ["cvtsi128_si32", "cvtsi128_si64", "movemask_epi8"]
SI1 = {"extract_epi32": [0, 1, 2, 3], "extract_epi64": [0, 1], "extract_epi16": [0, 5, 7],
       "extract_epi8": [0, 7, 15]}
INS = {"insert_epi32": [0, 3], "insert_epi16": [0, 7], "insert_epi8": [0, 15]}
INS64 = {"insert_epi64": [0, 1]}
FROM32 = ["cvtsi32_si128", "set1_epi8", "set1_epi16", "set1_epi32"]
FROM64 = ["cvtsi64_si128", "set1_epi64x"]

HEAD = r'''
#include <stdio.h>
#include <stdint.h>
#include <string.h>
#if defined(__x86_64__)
#include <immintrin.h>
#else
#include "sse2neon.h"
#endif
#define N 200
#define FNV0 1469598103934665603ULL
static uint64_t rs = 0x9E3779B97F4A7C15ULL;
static uint64_t rnd(void) { rs ^= rs << 13; rs ^= rs >> 7; rs ^= rs << 17; return rs; }
static uint64_t fnvb(uint64_t h, const uint8_t *b, int n) { for (int i = 0; i < n; i++) { h ^= b[i]; h *= 1099511628211ULL; } return h; }
static uint64_t fnv(uint64_t h, __m128i v) { uint8_t b[16]; _mm_storeu_si128((__m128i *)b, v); return fnvb(h, b, 16); }
static uint64_t fnvi(uint64_t h, long long v) { return fnvb(h, (const uint8_t *)&v, 8); }
static __m128i A[N], B[N], C[N];
static long long X[N];
#define OUT(name, h) printf("%s %016llx\n", name, (unsigned long long)(h))
#define TEST_T1(fn) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnv(h, fn(A[i])); OUT(#fn, h); }
#define TEST_T2(fn) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnv(h, fn(A[i], B[i])); OUT(#fn, h); }
#define TEST_T3(fn) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnv(h, fn(A[i], B[i], C[i])); OUT(#fn, h); }
#define TEST_TI1(fn, imm) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnv(h, fn(A[i], imm)); OUT(#fn "/" #imm, h); }
#define TEST_TI2(fn, imm) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnv(h, fn(A[i], B[i], imm)); OUT(#fn "/" #imm, h); }
#define TEST_S1(fn) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnvi(h, (long long)fn(A[i])); OUT(#fn, h); }
#define TEST_SI1(fn, imm) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnvi(h, (long long)fn(A[i], imm)); OUT(#fn "/" #imm, h); }
#define TEST_INS(fn, imm) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnv(h, fn(A[i], (int)X[i], imm)); OUT(#fn "/" #imm, h); }
#define TEST_INS64(fn, imm) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnv(h, fn(A[i], (long long)X[i], imm)); OUT(#fn "/" #imm, h); }
#define TEST_F32(fn) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnv(h, fn((int)X[i])); OUT(#fn, h); }
#define TEST_F64(fn) { uint64_t h = FNV0; for (int i = 0; i < N; i++) h = fnv(h, fn((long long)X[i])); OUT(#fn, h); }
int main(void) {
  for (int i = 0; i < N; i++) {
    uint8_t buf[16];
    for (int k = 0; k < 2; k++) { uint64_t v = rnd(); memcpy(buf + 8 * k, &v, 8); }
    A[i] = _mm_loadu_si128((const __m128i *)buf);
    for (int k = 0; k < 2; k++) { uint64_t v = rnd(); memcpy(buf + 8 * k, &v, 8); }
    B[i] = _mm_loadu_si128((const __m128i *)buf);
    for (int k = 0; k < 2; k++) { uint64_t v = rnd(); memcpy(buf + 8 * k, &v, 8); }
    C[i] = _mm_loadu_si128((const __m128i *)buf);
    X[i] = (long long)rnd();
  }
'''

def build_source(skips):
    lines = [HEAD]
    def add(kind, fn, imm=None):
        name = "_mm_" + fn
        if name in skips:
            return
        arg = "%s" % name if imm is None else "%s, %s" % (name, imm)
        lines.append("#ifndef SKIP_%s\n  TEST_%s(%s);\n#endif\n" % (name, kind, arg))
    for f in T2: add("T2", f)
    for f in T1: add("T1", f)
    for f in T3: add("T3", f)
    for f in S1: add("S1", f)
    for f in FROM32: add("F32", f)
    for f in FROM64: add("F64", f)
    for d, kind in ((TI1, "TI1"), (TI2, "TI2"), (SI1, "SI1"), (INS, "INS"), (INS64, "INS64")):
        for f, imms in d.items():
            for imm in imms: add(kind, f, hex(imm) if imm > 15 else str(imm))
    lines.append("  return 0;\n}\n")
    return "".join(lines)

def run(cmd, **kw):
    return subprocess.run(cmd, capture_output=True, text=True, **kw)

def parse(text):
    out = {}
    for ln in text.splitlines():
        parts = ln.split()
        if len(parts) == 2: out[parts[0]] = parts[1]
    return out

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--label", required=True)
    ap.add_argument("--dir", required=True, help="dossier contenant sse2neon.h")
    ap.add_argument("--compat", default="miner/work/armeabi-v7a/android-compat.h")
    ap.add_argument("--work", default="difftest-work")
    a = ap.parse_args()
    os.makedirs(a.work, exist_ok=True)
    cc = os.path.join(os.environ["ANDROID_NDK_HOME"], "toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi26-clang")
    src = os.path.join(a.work, "difftest.c")
    print("### Comparaison : %s" % a.label)

    # x86 : vraies instructions
    open(src, "w").write(build_source(set()))
    r = run(["gcc", "-O2", "-w", "-msse4.1", "-mssse3", "-maes", "-mpclmul", src, "-o", os.path.join(a.work, "t_x86")])
    if r.returncode != 0:
        print("Compilation x86 impossible :\n```\n%s\n```" % r.stderr[:1500]); return 1
    x86 = parse(run([os.path.join(a.work, "t_x86")]).stdout)

    # ARM 32 bits : sse2neon + en-tête de compatibilité ; on retire les fonctions absentes
    skips = set()
    flags = ["-O2", "-w", "-static", "-march=armv8-a+crypto", "-mfpu=crypto-neon-fp-armv8", "-mfloat-abi=softfp",
             "-flax-vector-conversions", "-fno-strict-aliasing", "-include", a.compat, "-I", a.dir]
    for _ in range(60):
        open(src, "w").write(build_source(skips))
        r = run([cc] + flags + [src, "-o", os.path.join(a.work, "t_arm")])
        if r.returncode == 0: break
        new = set(re.findall(r"'(_mm_[A-Za-z0-9_]+)'", r.stderr)) - skips
        if not new:
            print("Compilation ARM impossible :\n```\n%s\n```" % r.stderr[:2500]); return 1
        skips |= new
    else:
        print("Trop de fonctions manquantes."); return 1

    q = run(["qemu-arm-static", "-cpu", "max", os.path.join(a.work, "t_arm")])
    if q.returncode != 0 or not q.stdout.strip():
        print("Exécution ARM impossible (code %s) :\n```\n%s\n```" % (q.returncode, (q.stderr or q.stdout)[:1500])); return 1
    arm = parse(q.stdout)

    bad = [n for n in x86 if n in arm and arm[n] != x86[n]]
    ok = [n for n in x86 if n in arm and arm[n] == x86[n]]
    missing = sorted(skips)
    print("Fonctions testées : %d, identiques : %d, **différentes : %d**, absentes de sse2neon : %d" % (len(arm), len(ok), len(bad), len(missing)))
    if bad:
        print("\nRésultats DIFFÉRENTS de x86 (probablement faux sur ARM) :\n```\n" + "\n".join(sorted(bad)) + "\n```")
    if missing:
        print("\nAbsentes de cette version de sse2neon :\n```\n" + " ".join(missing) + "\n```")
    return 0

if __name__ == "__main__":
    sys.exit(main())
