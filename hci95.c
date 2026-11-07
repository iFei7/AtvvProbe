/*
 * hci95 - ptrace remote-call injector for Realtek BT vendor lib voice trigger
 *
 * Remotely calls rtk_vendor_cmd_to_fw(0x95, 1, &payload, 0) inside the
 * bluetooth HAL process (android.hardware.bluetooth@1.0-service) which owns
 * /dev/ttyS1 and the H5 state machine. payload = {enable} where enable=1
 * starts voice (RTL coex generic cmd, later forwarded to the remote over
 * the air by controller firmware) and 0 stops it.
 *
 * usage: hci95 <pid> <0|1> [func_offset_hex]
 *   default func offset 0xc920 (rtk_vendor_cmd_to_fw) inside libbt-vendor.so
 *
 * build (android ndk):
 *   armv7a-linux-androideabi28-clang -static -O2 -o hci95 hci95.c
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <elf.h>
#include <sys/ptrace.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <sys/user.h>

#define FUNC_OFF_DEFAULT 0xc920  /* rtk_vendor_cmd_to_fw */
#define LIB_NAME         "libbt-vendor.so"
#define RET_MAGIC        0xdead02f0

static int find_lib_base(int pid, const char *lib, unsigned long *base_out,
                         unsigned long *lo_addr, unsigned long *lo_off) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *f = fopen(path, "r");
    if (!f) return -1;
    char line[512];
    unsigned long base = 0;
    int found = 0;
    *lo_addr = 0; *lo_off = (unsigned long)-1;
    while (fgets(line, sizeof(line), f)) {
        if (strstr(line, lib) && strstr(line, "r-xp")) {
            unsigned long st, en, off;
            char perms[8], dev[16], namebuf[256];
            unsigned int inode; char *nm;
            if (sscanf(line, "%lx-%lx %7s %lx %15s %u %255s",
                       &st, &en, perms, &off, dev, &inode, namebuf) >= 6) {
                if (!found) { base = st - off; found = 1;
                    *lo_addr = st; *lo_off = off; }
            }
        }
    }
    fclose(f);
    if (!found) return -1;
    *base_out = base;
    return 0;
}

static struct pt_regs saved;

static int get_regs(int tid, struct pt_regs *regs) {
    struct iovec iov = { regs, sizeof(*regs) };
    return ptrace(PTRACE_GETREGSET, tid, (void*)1 /*NT_PRSTATUS*/, &iov);
}
static int set_regs(int tid, struct pt_regs *regs) {
    struct iovec iov = { regs, sizeof(*regs) };
    return ptrace(PTRACE_SETREGSET, tid, (void*)1, &iov);
}

static int write_mem(int pid, unsigned long addr, const void *buf, size_t len) {
    size_t i;
    for (i = 0; i < len; i += sizeof(long)) {
        long word;
        memcpy(&word, (const char*)buf + i, sizeof(long) < len - i ? sizeof(long) : len - i);
        if (ptrace(PTRACE_POKEDATA, pid, (void*)(addr + i), (void*)word) < 0) return -1;
    }
    return 0;
}

static void dump_threads(int pid, int *tids, int max) {
    char path[64];
    snprintf(path, sizeof(path), "/proc/%d/task", pid);
    DIR *d = opendir(path);
    int n = 0;
    if (d) {
        struct dirent *e;
        while ((e = readdir(d)) && n < max) {
            if (e->d_name[0] >= '0' && e->d_name[0] <= '9')
                tids[n++] = atoi(e->d_name);
        }
        closedir(d);
    }
    for (; n < max; n++) tids[n] = 0;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <pid> <opcode_hex> <param_hex> [func_offset_hex]\n", argv[0]);
        return 1;
    }
    int pid = atoi(argv[1]);
    unsigned long opcode = (argc > 2) ? strtoul(argv[2], 0, 16) : 0xFC1B;
    unsigned long param = (argc > 3) ? strtoul(argv[3], 0, 16) : 1;
    unsigned long func_off = (argc > 4) ? strtoul(argv[4], 0, 16) : FUNC_OFF_DEFAULT;

    unsigned long base, lo_addr, lo_off;
    if (find_lib_base(pid, LIB_NAME, &base, &lo_addr, &lo_off) < 0) {
        fprintf(stderr, "[-] %s not found in pid %d maps\n", LIB_NAME, pid);
        return 2;
    }
    unsigned long fn = base + func_off;
    printf("[+] %s base=0x%lx func=0x%lx opcode=0x%04x param=0x%x\n", LIB_NAME, base, fn, (unsigned)opcode, (unsigned)param);

    /* seize all threads */
    int tids[64];
    dump_threads(pid, tids, 64);
    int seized[64], nseized = 0, i;
    for (i = 0; i < 64 && tids[i]; i++) {
        int tid = tids[i];
        if (ptrace(PTRACE_SEIZE, tid, 0, (void*)(PTRACE_O_TRACESYSGOOD)) == 0) {
            seized[nseized++] = tid;
        }
    }
    printf("[+] seized %d threads\n", nseized);
    if (nseized == 0) { fprintf(stderr, "[-] seize failed: %s\n", strerror(errno)); return 3; }

    /* interrupt all: they stop in group-stop/signals */
    for (i = 0; i < nseized; i++)
        ptrace(PTRACE_INTERRUPT, seized[i], 0, 0);
    for (i = 0; i < nseized; i++) {
        int st;
        waitpid(seized[i], &st, __WALL);
    }

    /* use the first thread that stopped fine */
    int tid = seized[0];
    if (get_regs(tid, &saved) < 0) {
        fprintf(stderr, "[-] getregset: %s\n", strerror(errno));
        return 4;
    }
    printf("[+] stopped tid=%d pc=0x%lx sp=0x%lx\n", tid,
           (unsigned long)saved.ARM_pc, (unsigned long)saved.ARM_sp);

    /* build remote call: stack args area below sp */
    struct pt_regs r = saved;
    unsigned long sp = (saved.ARM_sp - 256) & ~0xfUL;
    unsigned char payload[4];
    payload[0] = (unsigned char)(param & 0xff);
    payload[1] = payload[2] = payload[3] = 0;
    if (write_mem(pid, sp, payload, 4) < 0) {
        fprintf(stderr, "[-] poke payload failed\n");
        return 5;
    }

    memset(&r, 0, sizeof(r));
    r.ARM_cpsr = (saved.ARM_cpsr & ~0x20u) | 0x20u; /* force thumb mode (T bit) */
    r.ARM_r0 = (unsigned long)opcode; /* full 16-bit HCI opcode, e.g. 0xFC1B */
    r.ARM_r1 = 1;                     /* param len */
    r.ARM_r2 = sp;                    /* param buffer ptr */
    r.ARM_r3 = 0;                     /* 4th arg */
    r.ARM_sp = sp;
    r.ARM_lr = RET_MAGIC;             /* return trap */
    r.ARM_pc = fn | 1u;               /* thumb bit: offset 0xc920 is thumb code */

    if (set_regs(tid, &r) < 0) {
        fprintf(stderr, "[-] setregset: %s\n", strerror(errno));
        return 6;
    }

    /* continue until our return trap (SIGSEGV at RET_MAGIC) */
    int st, got = 0;
    for (int loop = 0; loop < 200; loop++) {
        ptrace(PTRACE_CONT, tid, 0, 0);
        if (waitpid(tid, &st, __WALL) < 0) { fprintf(stderr, "[-] waitpid: %s\n", strerror(errno)); break; }
        if (!WIFSTOPPED(st)) { got = 0; break; }
        int sig = WSTOPSIG(st);
        fprintf(stderr, "[.] stop sig=%d status=0x%x\n", sig, st);
        if (sig == SIGSEGV || sig == SIGBUS || sig == SIGILL) { got = 1; break; }
        /* swallow syscall stops and unrelated signals, keep going */
    }
    struct pt_regs now;
    if (get_regs(tid, &now) < 0) {
        fprintf(stderr, "[-] getregset after run\n");
        return 7;
    }
    int ret = (int)now.ARM_r0;
    printf("[+] trap pc=0x%lx r0=%d (0x%x) %s\n",
           (unsigned long)now.ARM_pc, ret, ret,
           got ? "" : "(no trap signal seen!)");
    if ((unsigned long)now.ARM_pc != RET_MAGIC)
        fprintf(stderr, "[!] warning: did not land on RET_MAGIC\n");

    /* restore saved regs of the main tid */
    set_regs(tid, &saved);
    /* detach everything */
    for (i = 0; i < nseized; i++) {
        ptrace(PTRACE_DETACH, seized[i], 0, 0);
    }
    printf("[+] detached, HAL resumed\n");
    return 0;
}
