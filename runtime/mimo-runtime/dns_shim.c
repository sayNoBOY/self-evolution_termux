/* dns_shim.c — glibc getaddrinfo 垫片（智衍 SelfEvolution 容器专用）
 *
 * 用途：让 glibc 构建的 .mimocode（bun 单文件）在 Android/bionic 环境下完成域名解析。
 *   - Android 没有 /etc/resolv.conf，且该路径位于只读的 /system/etc，无法创建；
 *   - glibc 的 getaddrinfo 只认 /etc/resolv.conf（RESOLV_CONF 环境变量实测无效）；
 *   - 本垫片直接拦截 getaddrinfo，自己实现 DNS 客户端，向国内公共 DNS 发 UDP/53 查询。
 *
 * 注入方式（不用 LD_PRELOAD 环境变量，避免污染 agent 派生的 bionic 子进程）：
 *   $PREFIX/lib/glibc/ld-linux-aarch64.so.1 \
 *       --library-path $PREFIX/lib/glibc \
 *       --preload $PREFIX/lib/getaddrinfo_shim.so \
 *       $PREFIX/bin/.mimocode <args>
 *
 * 构建：gcc -shared -fPIC -O2 -Wall -o getaddrinfo_shim.so dns_shim.c   （aarch64 glibc 宿主原生编译）
 */
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <netdb.h>
#include <netinet/in.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

/* Termux 为 bionic 程序设置 LD_PRELOAD=libtermux-exec-ld-preload.so（bionic 库，
 * SONAME libc.so），glibc loader 启动时不能带它（会找 libc.so 失败），故 wrapper
 * 在 spawn 前清掉并暂存到 LD_PRELOAD_BIONIC。此处把原值写回本进程环境，使
 * .mimocode 之后派生的 bionic 子进程（bash/git/node 等）重新获得 termux-exec。 */
__attribute__((constructor)) static void se_restore_termux_preload(void) {
    const char *v = getenv("LD_PRELOAD_BIONIC");
    if (v && *v) setenv("LD_PRELOAD", v, 1);
}

static const char *kServers[] = {"223.5.5.5", "119.29.29.29", "8.8.8.8", NULL};

/* 单次 DNS 查询（A=1 / AAAA=28），取应答中第一条匹配类型的记录；解析失败返回 -1 */
static int dns_query(const char *name, int qtype, void *out, int *out_len) {
    unsigned char pkt[512];
    unsigned short id = (unsigned short)(getpid() ^ (unsigned)time(NULL) ^ (unsigned)clock());
    memset(pkt, 0, sizeof(pkt));
    pkt[0] = (unsigned char)(id >> 8);
    pkt[1] = (unsigned char)(id & 0xff);
    pkt[2] = 0x01; /* RD */
    pkt[5] = 0x01; /* QDCOUNT = 1 */
    int off = 12;
    const char *p = name;
    while (*p && off < 255) {
        const char *dot = strchr(p, '.');
        int l = dot ? (int)(dot - p) : (int)strlen(p);
        if (l <= 0 || l > 63) return -1;
        pkt[off++] = (unsigned char)l;
        memcpy(pkt + off, p, (size_t)l);
        off += l;
        if (!dot) break;
        p = dot + 1;
    }
    pkt[off++] = 0;
    pkt[off++] = (unsigned char)(qtype >> 8);
    pkt[off++] = (unsigned char)(qtype & 0xff);
    pkt[off++] = 0x00;
    pkt[off++] = 0x01; /* IN */
    int qlen = off;

    for (int s = 0; kServers[s]; s++) {
        int fd = socket(AF_INET, SOCK_DGRAM | SOCK_CLOEXEC, 0);
        if (fd < 0) return -1;
        struct timeval tv = {1, 500000};
        setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
        struct sockaddr_in sa;
        memset(&sa, 0, sizeof(sa));
        sa.sin_family = AF_INET;
        sa.sin_port = htons(53);
        inet_pton(AF_INET, kServers[s], &sa.sin_addr);
        if (sendto(fd, pkt, (size_t)qlen, 0, (struct sockaddr *)&sa, sizeof(sa)) < 0) {
            close(fd);
            continue;
        }
        unsigned char resp[1024];
        int n = (int)recvfrom(fd, resp, sizeof(resp), 0, NULL, NULL);
        close(fd);
        if (n < 12) continue;
        if (resp[0] != pkt[0] || resp[1] != pkt[1]) continue;
        if ((resp[3] & 0x0f) != 0) continue; /* rcode != NOERROR */
        int qd = (resp[4] << 8) | resp[5];
        int an = (resp[6] << 8) | resp[7];
        int o = 12;
        for (int k = 0; k < qd && o < n; k++) { /* 跳过 question */
            while (o < n && resp[o]) o += resp[o] + 1;
            o += 5;
        }
        for (int k = 0; k < an && o < n; k++) {
            if (resp[o] & 0xc0) {
                o += 2; /* 压缩指针 */
            } else {
                while (o < n && resp[o]) o += resp[o] + 1;
                o += 1;
            }
            if (o + 10 > n) break;
            int type = (resp[o] << 8) | resp[o + 1];
            int cls = (resp[o + 2] << 8) | resp[o + 3];
            int rdlen = (resp[o + 8] << 8) | resp[o + 9];
            o += 10;
            if (cls != 1 || o + rdlen > n) {
                o += rdlen;
                continue;
            }
            if (type == qtype && rdlen == (qtype == 1 ? 4 : 16)) {
                memcpy(out, resp + o, (size_t)rdlen);
                *out_len = rdlen;
                return 0;
            }
            o += rdlen;
        }
    }
    return -1;
}

static struct addrinfo *make_ai(int family, const void *addr, int alen, const char *node,
                                const char *service, const struct addrinfo *hints) {
    struct addrinfo *ai = (struct addrinfo *)calloc(1, sizeof(*ai));
    if (!ai) return NULL;
    ai->ai_family = family;
    ai->ai_socktype = (hints && hints->ai_socktype) ? hints->ai_socktype : SOCK_STREAM;
    ai->ai_protocol = (hints && hints->ai_protocol) ? hints->ai_protocol : (family == AF_INET6 ? IPPROTO_TCP : IPPROTO_TCP);
    ai->ai_flags = hints ? (hints->ai_flags & (AI_CANONNAME | AI_NUMERICSERV | AI_NUMERICHOST)) : 0;
    if (hints && (hints->ai_flags & AI_CANONNAME)) ai->ai_canonname = strdup(node);

    unsigned short port = 0;
    if (service && *service) {
        int ok = 1;
        for (const char *c = service; *c; c++)
            if (*c < '0' || *c > '9') { ok = 0; break; }
        if (ok)
            port = (unsigned short)atoi(service);
        else if (strcmp(service, "http") == 0) port = 80;
        else if (strcmp(service, "https") == 0) port = 443;
        else if (strcmp(service, "domain") == 0 || strcmp(service, "dns") == 0) port = 53;
    }

    if (family == AF_INET) {
        struct sockaddr_in *sin = (struct sockaddr_in *)calloc(1, sizeof(*sin));
        if (!sin) { free(ai); return NULL; }
        sin->sin_family = AF_INET;
        sin->sin_port = htons(port);
        memcpy(&sin->sin_addr, addr, (size_t)alen);
        ai->ai_addr = (struct sockaddr *)sin;
        ai->ai_addrlen = sizeof(*sin);
    } else {
        struct sockaddr_in6 *sin6 = (struct sockaddr_in6 *)calloc(1, sizeof(*sin6));
        if (!sin6) { free(ai); return NULL; }
        sin6->sin6_family = AF_INET6;
        sin6->sin6_port = htons(port);
        memcpy(&sin6->sin6_addr, addr, (size_t)alen);
        ai->ai_addr = (struct sockaddr *)sin6;
        ai->ai_addrlen = sizeof(*sin6);
    }
    return ai;
}

int getaddrinfo(const char *node, const char *service, const struct addrinfo *hints,
                struct addrinfo **res) {
    if (!node || !res) return EAI_NONAME;
    int fam = hints ? hints->ai_family : AF_UNSPEC;
    if (fam != AF_UNSPEC && fam != AF_INET && fam != AF_INET6) return EAI_FAMILY;

    /* 数字地址直通 */
    struct in_addr v4;
    struct in6_addr v6;
    if (inet_pton(AF_INET, node, &v4) == 1 && fam != AF_INET6) {
        *res = make_ai(AF_INET, &v4, 4, node, service, hints);
        return *res ? 0 : EAI_MEMORY;
    }
    if (inet_pton(AF_INET6, node, &v6) == 1 && fam != AF_INET) {
        *res = make_ai(AF_INET6, &v6, 16, node, service, hints);
        return *res ? 0 : EAI_MEMORY;
    }

    unsigned char addr[16];
    int alen = 0;
    int want4 = (fam != AF_INET6);
    int want6 = (fam != AF_INET);
    if (want4 && dns_query(node, 1, addr, &alen) == 0) {
        *res = make_ai(AF_INET, addr, alen, node, service, hints);
        return *res ? 0 : EAI_MEMORY;
    }
    if (want6 && dns_query(node, 28, addr, &alen) == 0) {
        *res = make_ai(AF_INET6, addr, alen, node, service, hints);
        return *res ? 0 : EAI_MEMORY;
    }
    return EAI_NONAME;
}
