#include <assert.h>
#include <stdio.h>
#include <string.h>
#include "../a9_eink_daemon/refresh_once.h"
static int value,reads,writes,waits,notifies,consume_after,missing,fail_arm,fail_clear,disconnect,read_fail_at;
static int64_t now;
static int rd(int *out) {
    reads++;
    if(missing || reads==read_fail_at) return -1;
    if(value==4 && consume_after>=0 && waits>=consume_after) value=-1;
    *out=value;return 0;
}
static int wr(int v) {
    assert(v==4 || v==-1);writes++;
    if((v==4 && fail_arm) || (v==-1 && fail_clear)) return -1;
    value=v;return 0;
}
static int ready(void *unused) {(void)unused;notifies++;return disconnect?-1:0;}
static int64_t clock_ms(void) {return now;}
static void wait_ms(unsigned int delay) {assert(delay==10);waits++;now+=delay;}
static struct refresh_ops ops={rd,wr,ready,clock_ms,wait_ms};
static int64_t last;
static void reset(void) {
    value=-1;reads=writes=waits=notifies=0;consume_after=-1;
    missing=fail_arm=fail_clear=disconnect=read_fail_at=0;now=10000;last=-1;
}
static void expect(const char *reply) {assert(strcmp(refresh_once(&ops,NULL,&last),reply)==0);}
int main(void) {
    reset();consume_after=3;expect("OK\n");assert(value==-1 && writes==1 && waits==3 && notifies==1);
    expect("ERR refresh-rate-limit\n");assert(writes==1);
    now+=1500;consume_after=0;expect("OK\n");assert(writes==2);
    reset();missing=1;expect("ERR refresh-not-ready\n");assert(writes==0 && notifies==0);
    reset();value=4;expect("ERR refresh-not-ready\n");assert(writes==0);
    reset();fail_arm=1;expect("ERR refresh-arm-failed\n");assert(value==-1 && notifies==0);
    reset();expect("ERR refresh-timeout-cleared\n");assert(waits==40 && writes==2 && value==-1);
    reset();disconnect=1;expect("ERR refresh-client-lost\n");assert(waits==0 && value==-1);
    reset();read_fail_at=2;expect("ERR refresh-read-failed\n");assert(writes==2 && value==-1);
    reset();fail_clear=1;expect("ERR refresh-cleanup-failed\n");assert(waits==40 && value==4);
    reset();now=-1;expect("ERR refresh-clock\n");assert(writes==0);
    puts("PASS: consumed request, rate limit, absent/busy property, failed arm, timeout cleanup, disconnect, read failure, cleanup failure, clock failure");
}
