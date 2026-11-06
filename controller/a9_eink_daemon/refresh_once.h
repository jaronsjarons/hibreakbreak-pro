/* Bounded stock one-shot refresh protocol; no mode selection or arbitrary writes.
 * READY asks the authenticated client to schedule one compositor pass.
 * A successful reset by HWC is an acknowledgement, not optical proof.
 */
#ifndef HIBREAK_REFRESH_ONCE_H
#define HIBREAK_REFRESH_ONCE_H
#include <stdint.h>
struct refresh_ops {
    int (*read)(int *value);
    int (*write)(int value);
    int (*ready)(void *client);
    int64_t (*now_ms)(void);
    void (*wait_ms)(unsigned int delay);
};
static const char *refresh_clear(const struct refresh_ops *ops, const char *reason) {
    int value;
    if (ops->write(-1) || ops->read(&value) || value != -1)
        return "ERR refresh-cleanup-failed\n";
    return reason;
}
static const char *refresh_once(const struct refresh_ops *ops, void *client, int64_t *last) {
    int value;
    int64_t now = ops->now_ms();
    if (now < 0) return "ERR refresh-clock\n";
    if (*last >= 0 && (now < *last || now - *last < 1500))
        return "ERR refresh-rate-limit\n";
    /* The prepared image must initialize this to -1 under the exact HWC label.
     * Missing state or a pending request is never silently overwritten here. */
    if (ops->read(&value) || value != -1) return "ERR refresh-not-ready\n";
    if (ops->write(4)) return "ERR refresh-arm-failed\n";
    *last = now;
    if (ops->ready(client)) return refresh_clear(ops,"ERR refresh-client-lost\n");
    for (unsigned int i=0;i<40;i++) {
        if (ops->read(&value)) return refresh_clear(ops,"ERR refresh-read-failed\n");
        if (value == -1) return "OK\n";
        if (value != 4) return refresh_clear(ops,"ERR refresh-unexpected-state\n");
        ops->wait_ms(10);
    }
    return refresh_clear(ops,"ERR refresh-timeout-cleared\n");
}
#endif
