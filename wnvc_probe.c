/*
 * wnvc_probe.c --- 黑盒调用 Nanosic vaudio SDK 的 WNVC 解码器
 *
 * 事实（已由反汇编确认）：
 *   WnvcDecoderInit()            @0x25518  顶层初始化（无参）
 *   Wnvc320DecoderInit()         @0x27b0c  320 样点解码器初始化（无参）
 *   Wnvc160DecoderInit()         @0x2928c  160 样点解码器初始化（无参）
 *   WnvcDecoder(frame, len, out, &len_out) @0x25564
 *       校验 'W''N' 且 (frame[2]&0xF7)==0x10；
 *       len 24/34 -> Wnvc160Decoder，44/64/84/124 -> Wnvc320Decoder
 *       直接把 r2/r3 透传给下层
 *   Wnvc320Decoder(frame, unused, out_i16, &len_bytes) @0x27b94
 *       frame+4 起是帧体；*len_bytes 回填 0x280 (640B = 320 × int16)
 *       状态全部保存在 .bss 的全局对象里（controldec / bitobj / randobj /
 *       old_samples / old_decoder_mlt_coefs），因此 **必须先 Init**，
 *       且各帧必须严格按顺序解码（old_samples 跨帧延续）。
 *
 * 本程序对若干种调用组合逐一尝试，每种在独立 fork 子进程内执行，
 * 崩溃不影响其它组合。输出写 <outdir>/wnvc_v<hex>.pcm（int16 LE, 16kHz）。
 *
 * 用法: wnvc_probe <voice_stream.bin> <libvaudio.so> <outdir>
 */
#include <dlfcn.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/wait.h>
#include <unistd.h>

#define FRAME_BODY 40
#define UNIT 48
#define MAX_FRAMES 512
#define OUT_PER_FRAME 320 /* 样点 */
#define STATE_SZ 65536

typedef int (*fn_dec4)(const uint8_t *, int, int16_t *, int *);
typedef void (*fn_init)(void);
typedef int (*fn_ver)(void);

static uint8_t g_body[MAX_FRAMES][FRAME_BODY];
static int g_frames;

/* 读入 voice_stream.bin，按 48 字节/序列拆出 40 字节载荷。 */
static int load_frames(const char *path)
{
    FILE *f = fopen(path, "rb");
    if (!f) {
        perror("open voice_stream");
        return -1;
    }
    static uint8_t raw[MAX_FRAMES * UNIT];
    size_t n = fread(raw, 1, sizeof(raw), f);
    fclose(f);
    g_frames = (int)(n / UNIT);
    if (g_frames <= 0 || g_frames > MAX_FRAMES) {
        fprintf(stderr, "bad frame count %d (n=%zu)\n", g_frames, n);
        return -1;
    }
    for (int i = 0; i < g_frames; i++)
        memcpy(g_body[i], raw + i * UNIT, FRAME_BODY);
    if (raw[FRAME_BODY] != 0x1D || raw[FRAME_BODY + 1] != 0x00) {
        fprintf(stderr, "unexpected trailer %02x %02x\n",
                raw[FRAME_BODY], raw[FRAME_BODY + 1]);
        return -1;
    }
    printf("frames=%d\n", g_frames);
    return 0;
}

/* 40 字节帧体包装成 44 字节 WNVC 帧。 */
static void build_frame(uint8_t *dst, const uint8_t *body, int mode)
{
    dst[0] = 0x57;
    dst[1] = 0x4E;
    dst[2] = (uint8_t)mode; /* (mode & 0xF7) 必须等于 0x10 */
    dst[3] = 0x00;
    memcpy(dst + 4, body, FRAME_BODY);
}

/*
 * 变体位定义：
 *   bit0 调用 WnvcDecoderInit（顶层）
 *   bit1 调用 Wnvc320DecoderInit（320 专用）
 *   bit2 frame[2] 用 0x18（默认 0x10）
 *   bit3 直接调 Wnvc320Decoder（跳过 WnvcDecoder 的封装/校验）
 *   bit4 每帧重调 Init（测试是否跨帧需要状态）
 */
static int run_variant(int variant, void *h, const char *outdir)
{
    fn_dec4 dec = (fn_dec4)dlsym(h, "WnvcDecoder");
    fn_dec4 dec320 = (fn_dec4)dlsym(h, "Wnvc320Decoder");
    fn_init init_top = (fn_init)dlsym(h, "WnvcDecoderInit");
    fn_init init320 = (fn_init)dlsym(h, "Wnvc320DecoderInit");
    if (!dec || !dec320 || !init_top || !init320)
        return -3;

    static int16_t out[MAX_FRAMES * OUT_PER_FRAME];
    memset(out, 0, sizeof(out));

    int use_top = variant & 1;
    int use_320 = variant & 2;
    int mode = (variant & 4) ? 0x18 : 0x10;
    int direct = variant & 8;
    int reinit = variant & 16;

    if (use_top)
        init_top();
    if (use_320)
        init320();

    uint8_t frame[44];
    int total = 0, last_len = 0, ok = 0;
    for (int i = 0; i < g_frames; i++) {
        if (reinit && i) {
            if (use_top)
                init_top();
            if (use_320)
                init320();
        }
        build_frame(frame, g_body[i], mode);
        int outlen = 0;
        if (direct)
            dec320(frame, 44, out + total, &outlen);
        else
            dec(frame, 44, out + total, &outlen);
        if (i == 0)
            last_len = outlen;
        total += OUT_PER_FRAME;
        if (outlen > 0)
            ok++;
    }

    char path[256];
    snprintf(path, sizeof(path), "%s/wnvc_v%02x.pcm", outdir, variant);
    FILE *f = fopen(path, "wb");
    if (!f)
        return -4;
    fwrite(out, sizeof(int16_t), (size_t)total, f);
    fclose(f);
    printf("variant %02x -> %s samples=%d first_outlen=%d nonempty=%d/%d\n",
           variant, path, total, last_len, ok, g_frames);
    return 0;
}

int main(int argc, char **argv)
{
    if (argc < 4) {
        fprintf(stderr, "usage: %s voice_stream.bin libvaudio.so outdir\n",
                argv[0]);
        return 2;
    }
    if (load_frames(argv[1]) != 0)
        return 1;

    void *h = dlopen(argv[2], RTLD_NOW);
    if (!h) {
        fprintf(stderr, "dlopen(RTLD_NOW) failed: %s\n", dlerror());
        h = dlopen(argv[2], RTLD_LAZY);
        if (!h) {
            fprintf(stderr, "dlopen(RTLD_LAZY) failed: %s\n", dlerror());
            return 1;
        }
        printf("fallback to RTLD_LAZY ok\n");
    }
    printf("libvaudio loaded at %p\n", h);

    const char *names[] = {"WnvcDecoder", "WnvcDecoderInit", "WnvcDecoderVersion",
                           "Wnvc320Decoder", "Wnvc320DecoderInit",
                           "Wnvc160Decoder", "Wnvc160DecoderInit",
                           "decoder", "adpcm_decoder", NULL};
    for (int i = 0; names[i]; i++)
        printf("sym %-22s = %p\n", names[i], dlsym(h, names[i]));

    fn_ver ver = (fn_ver)dlsym(h, "WnvcDecoderVersion");
    if (ver)
        printf("WnvcDecoderVersion() = 0x%x\n", ver());

    /* 依次尝试 8 种组合（0..7 + 8..15 的 direct 变体共 16 个，取关键 8 个） */
    const int variants[] = {0x03, 0x01, 0x02, 0x07, 0x0B, 0x13, 0x00, 0x0F};
    for (unsigned i = 0; i < sizeof(variants) / sizeof(variants[0]); i++) {
        int v = variants[i];
        pid_t pid = fork();
        if (pid == 0) {
            int r = run_variant(v, h, argv[3]);
            fflush(NULL);
            _exit(r == 0 ? 0 : 1);
        }
        int st = 0;
        waitpid(pid, &st, 0);
        if (!WIFEXITED(st) || WEXITSTATUS(st) != 0)
            printf("variant %02x FAILED status=0x%x (exit=%d sig=%d)\n",
                   v, st, WIFEXITED(st) ? WEXITSTATUS(st) : -1,
                   WIFSIGNALED(st) ? WTERMSIG(st) : 0);
    }
    printf("done. pull %s/wnvc_v*.pcm\n", argv[3]);
    return 0;
}
