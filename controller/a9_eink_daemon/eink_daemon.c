/* HiBreak Pro control prototype. Repository MIT license applies.
 * Only documented HiBreak endpoints are supported. No shell execution.
 * Run --self-test anywhere; --probe only reads. Daemon writes require root
 * and an authenticated system/root peer. SELinux integration remains pending.
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stddef.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <sys/un.h>
#include <signal.h>
#include <time.h>
#ifdef __ANDROID__
#include <sys/system_properties.h>
#include "refresh_once.h"
#endif

#define LIGHT_BASE "/sys/devices/platform/11d01000.i2c7/i2c-7/7-0036/lm3630a_"
#define EINK_BASE "/sys/kernel/debug/eink_debug/"
#define MAX_LINE 64
struct operation { const char *path; int value; };
struct frame { char line[MAX_LINE]; size_t used; };
static int peer_allowed(unsigned int uid) { return uid==0 || uid==1000; }

static int decimal(const char *text, int max, int *result) {
    if (!*text || strlen(text) > 3) return -1;
    int value = 0;
    for (const unsigned char *s = (const unsigned char *)text; *s; ++s) {
        if (*s < '0' || *s > '9') return -1;
        value = value * 10 + (*s - '0');
        if (value > max) return -1;
    }
    *result = value;
    return 0;
}

/* Property commands are laboratory controls for stock HWC paths. No caller can
 * supply a property name, arbitrary rate, shell command, or filesystem path. */
static int property_command(const char *text, struct operation *op) {
    static const struct { const char *prefix, *property; int max; } fields[] = {
        {"hw_m", "vendor.xrz.global_refresh_mode", 183},
        {"cl_fr", "vendor.xrz.clean_frequency", 50},
        {"cl_en", "vendor.xrz.check_commit", 1},
        {"rr_fps", "persist.vendor.xrz.fps", 60},
    };
    for (size_t i=0;i<sizeof(fields)/sizeof(fields[0]);i++) {
        size_t n=strlen(fields[i].prefix);
        if (strncmp(text,fields[i].prefix,n)) continue;
        op->path=fields[i].property;
        if(decimal(text+n,fields[i].max,&op->value)) return -1;
        if(i==0 && op->value!=0 && op->value!=177 && op->value!=178 &&
           op->value!=179 && op->value!=180 && op->value!=183) return -1;
        if(i==3 && op->value!=0 && op->value!=15 && op->value!=30 && op->value!=60) return -1;
        return 0;
    }
    return -1;
}
static int decode(const char *text, struct operation *op) {
    static const struct { const char *prefix, *path; int max; } fields[] = {
        {"br_co", LIGHT_BASE "cold_light", 254},
        {"br_wm", LIGHT_BASE "warm_light", 254},
        {"au_br", EINK_BASE "clean_a2", 1},
        {"an_fl", EINK_BASE "anti_flicker", 1},
        /* Pixel processing only. Exact stock kernel clamps contrast to 0..99;
         * brightness is divided by five and bounded to +20 in its pixel loop. */
        {"im_co", EINK_BASE "contrast", 99},
        {"im_br", EINK_BASE "brightness", 100},
    };
    for (size_t i = 0; i < sizeof(fields)/sizeof(fields[0]); ++i) {
        size_t n = strlen(fields[i].prefix);
        if (strncmp(text, fields[i].prefix, n) == 0) {
            op->path = fields[i].path;
            return decimal(text+n, fields[i].max, &op->value);
        }
    }
    op->path = EINK_BASE "global_mode";
    if (strcmp(text,"c") == 0) op->value = 180;
    else if (strcmp(text,"b") == 0) op->value = 178;
    else if (strcmp(text,"s") == 0) op->value = 177;
    else if (strcmp(text,"p") == 0) op->value = 179;
    else if (strcmp(text,"h") == 0) op->value = 183;
    else return -1; /* Includes inherited Hisense-only commands and empty refresh. */
    return 0;
}

/* Returns 1 for a complete line, 0 for more input, -1 for malformed input.
 * Caller must consume the completed line before passing the next byte.
 */
static int feed(struct frame *f, unsigned char c) {
    if (c == '\n') {
        if (!f->used) return -1;
        f->line[f->used] = '\0'; f->used = 0; return 1;
    }
    if (c < 32 || c > 126 || f->used >= MAX_LINE-1) return -1;
    f->line[f->used++] = (char)c;
    return 0;
}

static int self_test(void) {
    if(!peer_allowed(0) || !peer_allowed(1000) || peer_allowed(2000) || peer_allowed(10001)) return 1;
    struct operation op;
    const char *bad[] = {"", "br_co255", "br_co-1", "br_co9999", "br_co1x",
                        "br_co1;id", "br_co 1", "br_co", "an_fl2", "au_br10",
                        "r", "cm", "sco1", "stw0", "stb0", "theme0 ffffff", "im_co100", "im_br101", "im_br-1"};
    for (size_t i=0;i<sizeof(bad)/sizeof(bad[0]);++i)
        if (decode(bad[i], &op) == 0) return 1;
    if (decode("br_co254",&op) || op.value != 254) return 1;
    if (decode("br_wm0",&op) || op.value != 0) return 1;
    if (decode("c",&op) || op.value != 180) return 1;
    if (decode("h",&op) || op.value != 183 || strcmp(op.path,EINK_BASE "global_mode")) return 1;
    if (decode("an_fl1",&op) || op.value != 1) return 1;
    if (decode("im_co99",&op) || op.value != 99) return 1;
    if (decode("im_br100",&op) || op.value != 100) return 1;
    const char *bad_props[]={"rr_fps61","rr_fps1","rr_fps-1","hw_m181","hw_m184","cl_fr51","cl_en2","vendor.xrz.fps_limit20","hw_m183;id"};
    for(size_t i=0;i<sizeof(bad_props)/sizeof(bad_props[0]);i++)
        if(property_command(bad_props[i],&op)==0) return 1;
    if(property_command("rr_fps30",&op) || op.value!=30 || strcmp(op.path,"persist.vendor.xrz.fps")) return 1;
    if(property_command("hw_m183",&op) || op.value!=183) return 1;
    if(property_command("cl_fr50",&op) || op.value!=50) return 1;
    struct frame f = {{0},0};
    const char *chunks[] = {"br_", "co", "10\nbr_w", "m20\n"};
    int lines=0;
    for (size_t i=0;i<4;++i) for (const char *p=chunks[i];*p;++p) {
        int r=feed(&f,(unsigned char)*p);
        if (r<0) return 1;
        if (r==1) {
            if (decode(f.line,&op) || op.value != (++lines)*10) return 1;
        }
    }
    if (lines!=2 || f.used || feed(&f,0)!=-1 || feed(&f,'\n')!=-1) return 1;
    for (size_t i=0;i<MAX_LINE-1;++i) if (feed(&f,'x')!=0) return 1;
    if (feed(&f,'x')!=-1) return 1;
    puts("PASS: numeric limits, unsupported commands, fragmented/coalesced frames, NUL and overflow rejection");
    return 0;
}

static int probe(void) {
    const char *paths[] = {LIGHT_BASE "cold_light", LIGHT_BASE "warm_light",
                           EINK_BASE "global_mode", EINK_BASE "clean_a2", EINK_BASE "anti_flicker"};
    for(size_t i=0;i<sizeof(paths)/sizeof(paths[0]);++i) {
        int fd=open(paths[i],O_RDONLY|O_CLOEXEC);
        if(fd<0) { printf("%s: unavailable (%s)\n",paths[i],strerror(errno)); continue; }
        char b[128];ssize_t n=read(fd,b,sizeof(b)-1);int err=errno;close(fd);
        if(n<0) printf("%s: read failed (%s)\n",paths[i],strerror(err));
        else { b[n]='\0';printf("%s: %s\n",paths[i],b); }
    }
    return 0;
}

#ifdef __ANDROID__
/* Fixed property diagnostics. The property namespace is never supplied by a peer. */
static const char *query_property(const char *command) {
    static const struct { const char *name, *property; } fields[] = {
        {"get_requested_fps", "persist.vendor.xrz.fps"},
        {"get_hwc_mode", "vendor.xrz.global_refresh_mode"},
        {"get_hwc_frequency", "vendor.xrz.clean_frequency"},
        {"get_hwc_fps_limit", "vendor.xrz.fps_limit"},
        {"get_hwc_idle", "vendor.xrz.hwc_idle"},
        {"get_hwc_dither", "vendor.xrz.gray_256_dither"},
        {"get_force_refresh", "vendor.xrz.force_global_refresh_mode"},
    };
    for(size_t i=0;i<sizeof(fields)/sizeof(fields[0]);i++)
        if(strcmp(command,fields[i].name)==0) return fields[i].property;
    return NULL;
}
static void query_clocks(char *reply,size_t capacity) {
    struct timespec elapsed,awake;
    if(clock_gettime(CLOCK_BOOTTIME,&elapsed) || clock_gettime(CLOCK_MONOTONIC,&awake)) {
        snprintf(reply,capacity,"ERR clock-read-failed\n");return;
    }
    snprintf(reply,capacity,"VALUE elapsed_ms=%lld,awake_ms=%lld\n",
        (long long)elapsed.tv_sec*1000+elapsed.tv_nsec/1000000,
        (long long)awake.tv_sec*1000+awake.tv_nsec/1000000);
}
/* Read-only diagnostics for this device. No caller-provided path. */
static const char *query_path(const char *command) {
    static const struct { const char *name, *path; } fields[] = {
        {"get_cold", LIGHT_BASE "cold_light"}, {"get_warm", LIGHT_BASE "warm_light"},
        {"get_mode", EINK_BASE "global_mode"}, {"get_auto", EINK_BASE "clean_a2"},
        {"get_flicker", EINK_BASE "anti_flicker"},
        {"get_brightness", EINK_BASE "brightness"}, {"get_contrast", EINK_BASE "contrast"},
        {"get_manual_refresh", EINK_BASE "manual_refresh"},
    };
    for(size_t i=0;i<sizeof(fields)/sizeof(fields[0]);i++)
        if(strcmp(command,fields[i].name)==0) return fields[i].path;
    return NULL;
}
static void query_value(const char *path, char *reply, size_t capacity) {
    int fd=open(path,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0) {snprintf(reply,capacity,"ERR hardware-read-failed\n");return;}
    char b[128];ssize_t n=read(fd,b,sizeof(b)-1);close(fd);
    if(n<0) {snprintf(reply,capacity,"ERR hardware-read-failed\n");return;}
    while(n>0 && (b[n-1]=='\n' || b[n-1]=='\r')) n--;
    for(ssize_t i=0;i<n;i++) if(b[i]<32 || b[i]>126) b[i]=' ';
    b[n]=0;snprintf(reply,capacity,"VALUE %s\n",b);
}
static int apply(const struct operation *op) {
    char value[8];int n=snprintf(value,sizeof(value),"%d",op->value);
    int fd=open(op->path,O_WRONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0) return -1;
    ssize_t done;
    do { done=write(fd,value,(size_t)n); } while(done<0 && errno==EINTR);
    int err=errno;close(fd);errno=err;
    return done==n ? 0 : -1;
}

static int refresh_read(int *value) {
    char raw[PROP_VALUE_MAX]={0};
    if(__system_property_get("vendor.xrz.force_global_refresh_mode",raw)<=0) return -1;
    if(strcmp(raw,"-1")==0) *value=-1;
    else if(strcmp(raw,"4")==0) *value=4;
    else return -1;
    return 0;
}
static int refresh_write(int value) {
    if(value!=4 && value!=-1) return -1;
    return __system_property_set("vendor.xrz.force_global_refresh_mode",value==4?"4":"-1");
}
static int refresh_ready(void *context) {
    int client=*(const int *)context;
    return send(client,"READY\n",6,MSG_NOSIGNAL)==6 ? 0 : -1;
}
static int64_t refresh_now(void) {
    struct timespec now;
    if(clock_gettime(CLOCK_BOOTTIME,&now)) return -1;
    return (int64_t)now.tv_sec*1000+now.tv_nsec/1000000;
}
static void refresh_wait(unsigned int milliseconds) {
    struct timespec delay={.tv_sec=0,.tv_nsec=(long)milliseconds*1000000};
    /* An interrupted wait need not be resumed: the iteration count is bounded. */
    nanosleep(&delay,NULL);
}
static const struct refresh_ops refresh_callbacks={refresh_read,refresh_write,refresh_ready,refresh_now,refresh_wait};
static int64_t last_refresh=-1;

static void serve_client(int client) {
    struct ucred peer; socklen_t length=sizeof(peer);
    if(getsockopt(client,SOL_SOCKET,SO_PEERCRED,&peer,&length)<0 ||
       length!=sizeof(peer) || !peer_allowed(peer.uid)) return;
    struct timeval timeout={.tv_sec=2,.tv_usec=0};
    if(setsockopt(client,SOL_SOCKET,SO_RCVTIMEO,&timeout,sizeof(timeout)) ||
       setsockopt(client,SOL_SOCKET,SO_SNDTIMEO,&timeout,sizeof(timeout))) return;
    struct frame frame={{0},0};unsigned char buffer[128];unsigned count=0;
    struct timespec started,now;
    if(clock_gettime(CLOCK_MONOTONIC,&started)) return;
    /* Bounded connection lifetime also prevents an idle authorized client
     * from monopolizing the single-purpose server indefinitely. */
    while(count<32) {
        if(clock_gettime(CLOCK_MONOTONIC,&now) || now.tv_sec-started.tv_sec>=3) return;
        ssize_t n=recv(client,buffer,sizeof(buffer),0);
        if(n<0 && errno==EINTR) continue;
        if(n<=0) return;
        for(ssize_t i=0;i<n;++i) {
            int r=feed(&frame,buffer[i]);
            if(r<0) return;
            if(r==1) {
                struct operation op;const char *reply;char queried[160];
                const char *path=query_path(frame.line);
                const char *property=query_property(frame.line);
                if(path) {query_value(path,queried,sizeof(queried));reply=queried;}
                else if(property) {
                    char value[PROP_VALUE_MAX]={0};int n=__system_property_get(property,value);
                    snprintf(queried,sizeof(queried),"VALUE %s\n",n>0?value:"<unset>");reply=queried;
                }
                else if(strcmp(frame.line,"get_clocks")==0) {
                    query_clocks(queried,sizeof(queried));reply=queried;
                }
                else if(strcmp(frame.line,"refresh_once")==0) {
                    reply=refresh_once(&refresh_callbacks,&client,&last_refresh);
                }
                else if(property_command(frame.line,&op)==0) {
                    char value[8];snprintf(value,sizeof(value),"%d",op.value);
                    reply=__system_property_set(op.path,value)==0 ? "OK\n" : "ERR property-write-failed\n";
                }
                else if(decode(frame.line,&op)) reply="ERR unsupported-or-invalid\n";
                else if(apply(&op)) reply="ERR hardware-write-failed\n";
                else reply="OK\n";
                size_t size=strlen(reply);
                if(send(client,reply,size,MSG_NOSIGNAL)!=(ssize_t)size) return;
                if(++count>=32) return;
            }
        }
    }
}

static int serve(void) {
    if(geteuid()!=0) { fputs("Daemon requires root and SELinux integration.\n",stderr);return 1; }
    int server=socket(AF_UNIX,SOCK_STREAM|SOCK_CLOEXEC,0);
    if(server<0) return 1;
    const char *name="a9_eink_socket";
    struct sockaddr_un addr={.sun_family=AF_UNIX};
    memcpy(addr.sun_path+1,name,strlen(name));
    socklen_t size=(socklen_t)(offsetof(struct sockaddr_un,sun_path)+1+strlen(name));
    if(bind(server,(struct sockaddr*)&addr,size) || listen(server,8)) { close(server);return 1; }
    int pending_refresh;
    if(refresh_read(&pending_refresh)==0 && pending_refresh==4)
        fputs(refresh_clear(&refresh_callbacks,"Cleared stale refresh request on daemon restart\n"),stderr);
    signal(SIGPIPE,SIG_IGN);
    for(;;) {
        int client=accept4(server,NULL,NULL,SOCK_CLOEXEC);
        if(client<0) { if(errno==EINTR) continue;close(server);return 1; }
        serve_client(client);close(client);
    }
}
#endif

int main(int argc,char **argv) {
    if(argc==2 && strcmp(argv[1],"--self-test")==0) return self_test();
    if(argc==2 && strcmp(argv[1],"--probe")==0) return probe();
#ifdef __ANDROID__
    if(argc==2 && strcmp(argv[1],"--serve")==0) return serve();
#endif
    fputs("Usage: hibreak-control --self-test | --probe | --serve\n",stderr);
    return 2;
}
