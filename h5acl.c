/* h5acl: inject raw HCI ACL data (e.g. ATT Exchange MTU Request) into the
 * Realtek vendor lib running inside android.hardware.bluetooth@1.0-service
 * via ptrace remote-call of hci_h5_send_acl_data(2, buf, len).
 *
 * usage: h5acl <pid> <handle_hex> <att_hex>
 *   e.g. h5acl 1782 10 02C800
 *        -> builds HCI ACL: [handle][len=4+att][l2cap: len cid=4][att]
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <dirent.h>
#include <sys/ptrace.h>
#include <sys/wait.h>
#include <sys/user.h>
#include <sys/uio.h>

#define RET_MAGIC 0xdead02f0

static int hexval(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
}
static int parse_hex(const char *s, unsigned char *out, int max) {
    int n = 0;
    while (*s && n < max) {
        int hi = hexval(s[0]), lo = s[1] ? hexval(s[1]) : -1;
        if (hi < 0 || lo < 0) break;
        out[n++] = (hi << 4) | lo;
        if (s[1]) s += 2; else s += 1;
    }
    return n;
}

/* find r-xp base of libbt-vendor.so */
static unsigned long find_lib_base(int pid, const char *lib) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return 0;
    char line[512];
    unsigned long base = 0;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, lib) && strstr(line, "r-xp")) {
            unsigned long lo = strtoul(line, NULL, 16);
            base = lo;
            break;
        }
    }
    fclose(f);
    return base;
}

static int seize_all_threads(int pid, int *tids, int max) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *d = opendir(path);
    struct dirent *e;
    int n = 0;
    while ((e = readdir(d)) && n < max) {
        if (e->d_name[0] < '0' || e->d_name[0] > '9') continue;
        int tid = atoi(e->d_name);
        if (tid == pid) continue;
        if (ptrace(PTRACE_SEIZE, tid, 0, 0) == 0) {
            tids[n++] = tid;
        }
    }
    closedir(d);
    return n;
}

static int read_regs(int tid, struct pt_regs *r) {
    struct iovec iov = { r, sizeof(*r) };
    return ptrace(PTRACE_GETREGSET, tid, (void *)1 /*NT_PRSTATUS*/, &iov);
}
static int write_regs(int tid, struct pt_regs *r) {
    struct iovec iov = { r, sizeof(*r) };
    return ptrace(PTRACE_SETREGSET, tid, (void *)1, &iov);
}

/* write memory into target */
static int poke_buf(int pid, unsigned long addr, const unsigned char *buf, int len) {
    int fd;
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    fd = open(path, O_WRONLY);
    if (fd < 0) return -1;
    int r = pwrite64(fd, buf, len, (off64_t)addr);
    close(fd);
    return r;
}

int main(int argc, char **argv) {
    if (argc < 4) { printf("usage: h5acl <pid> <handle_hex> <att_hex>\n"); return 1; }
    int pid = atoi(argv[1]);
    unsigned char handle[2], att[256];
    char hs[8]; snprintf(hs, sizeof(hs), "%04s", argv[2]);
    int hlen = parse_hex(argv[2], handle, 2);
    int alen = parse_hex(argv[3], att, sizeof(att));
    if (hlen != 2) { printf("bad handle\n"); return 1; }

    unsigned long base = find_lib_base(pid, "libbt-vendor.so");
    if (!base) { printf("libbt-vendor.so r-xp not found\n"); return 1; }
    unsigned long fn = base + 0xaf98; /* hci_h5_send_acl_data + thumb bit handled below */
    printf("libbt-vendor r-xp base=%#lx fn=%#lx\n", base, fn);

    /* build ACL buffer: handle | pb/bc(0), datalen, l2cap(len, cid=4), att */
    unsigned char buf[300];
    int l2len = alen;
    int datalen = 4 + l2len;
    buf[0] = handle[1]; buf[1] = handle[0]; /* LE16 */
    buf[2] = datalen & 0xff; buf[3] = (datalen >> 8) & 0xff;
    buf[4] = l2len & 0xff; buf[5] = (l2len >> 8) & 0xff;
    buf[6] = 0x04; buf[7] = 0x00; /* cid = 0x0004 ATT */
    memcpy(buf + 8, att, alen);
    int buflen = 8 + alen;

    int tids[256], ntids;
    ntids = seize_all_threads(pid, tids, 256);
    printf("seized %d threads\n", ntids);

    /* interrupt all, pick main thread pid for call */
    for (int i = 0; i < ntids; i++)
        ptrace(PTRACE_INTERRUPT, tids[i], 0, 0);
    int status;
    int target = -1;
    struct pt_regs regs, saved;
    /* interrupt main thread too (it wasn't seized; seize now) */
    ptrace(PTRACE_SEIZE, pid, 0, 0);
    ptrace(PTRACE_INTERRUPT, pid, 0, 0);
    for (int i = 0; i < ntids + 1; i++) waitpid(-1, &status, __WALL);
    if (ptrace(PTRACE_GETREGS, pid, 0, &saved) < 0) { printf("getregs err %s\n", strerror(errno)); return 1; }
    regs = saved;

    unsigned long scratch = (regs.ARM_sp - 0x400) & ~0xfUL;
    if (poke_buf(pid, scratch, buf, buflen) != buflen) { printf("poke err %s\n", strerror(errno)); }

    regs.ARM_cpsr = (regs.ARM_cpsr & ~0x20u) | 0x20u; /* force thumb */
    regs.ARM_r0 = 2;              /* DATA_TYPE_ACL */
    regs.ARM_r1 = scratch;        /* data */
    regs.ARM_r2 = buflen;         /* length */
    regs.ARM_r3 = 0;
    regs.ARM_lr = RET_MAGIC;
    regs.ARM_pc = fn | 1u;
    if (ptrace(PTRACE_SETREGS, pid, 0, &regs) < 0) { printf("setregs err\n"); return 1; }
    printf("calling hci_h5_send_acl_data(2, %#lx, %d) ...\n", scratch, buflen);

    /* continue until RET_MAGIC segv */
    int stopped = 0;
    for (int k = 0; k < 200; k++) {
        ptrace(PTRACE_CONT, pid, 0, 0);
        if (waitpid(pid, &status, __WALL) < 0) break;
        if (WIFSTOPPED(status)) {
            int sig = WSTOPSIG(status);
            printf("stop sig=%d\n", sig);
            if (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL) {
                struct pt_regs now;
                ptrace(PTRACE_GETREGS, pid, 0, &now);
                if (now.ARM_pc == RET_MAGIC) {
                    printf("ret r0=%#lx (len=%d success / 0xffff=err)\n", now.ARM_r0, buflen);
                } else {
                    printf("crash pc=%#lx r0=%#lx\n", now.ARM_pc, now.ARM_r0);
                }
                stopped = 1;
                break;
            }
        }
    }
    if (!stopped) printf("no RET_MAGIC seen (timeout)\n");
    /* restore */
    ptrace(PTRACE_SETREGS, pid, 0, &saved);
    /* detach all */
    ptrace(PTRACE_DETACH, pid, 0, 0);
    for (int i = 0; i < ntids; i++)
        ptrace(PTRACE_DETACH, tids[i], 0, 0);
    printf("done, detached\n");
    return 0;
}
