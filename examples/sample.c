#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct asset_header { unsigned magic; unsigned version; unsigned size; unsigned long long data; };
static struct asset_header *g_current;

static void *xalloc(size_t n) { void *p = malloc(n); if (!p) exit(2); return p; }

struct asset_header *load_asset_file(const char *path) {
    FILE *f = fopen(path, "rb");
    if (!f) return NULL;
    struct asset_header *h = xalloc(sizeof *h);
    if (fread(h, sizeof *h, 1, f) != 1 || h->magic != 0x41535354u) { free(h); fclose(f); return NULL; }
    fclose(f);
    g_current = h;
    return h;
}

int validate(const char *name, int n) {
    char buf[32];
    strcpy(buf, name);      /* intentionally unsafe for security review */
    printf("%s:%d\n", buf, n);
    return n > 0;
}

int main(int argc, char **argv) {
    struct asset_header *h = load_asset_file(argc > 1 ? argv[1] : "default.asset");
    if (!h) { puts("failed to load asset"); return 1; }
    return validate("asset", (int)h->version);
}
