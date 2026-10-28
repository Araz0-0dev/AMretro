#include "../native/core_host.h"
#include "../native/libretro.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static unsigned frames, width, height;
static uint64_t image_hash;
static long audio_samples;
static void video(const void *data, unsigned w, unsigned h, size_t pitch, unsigned fmt) {
    (void)fmt;
    assert(data && w && h && pitch && h <= 1024 && pitch <= 16384);
    const unsigned char *p = data;
    uint64_t hash = UINT64_C(14695981039346656037);
    for (size_t i = 0; i < pitch * h; i++) hash = (hash ^ p[i]) * UINT64_C(1099511628211);
    image_hash = hash; width = w; height = h; frames++;
}
static void frame(unsigned buttons) {
    int16_t data[32768];
    am_core_frame((uint16_t)buttons);
    audio_samples += (long)am_core_take_audio(data, sizeof(data) / sizeof(data[0]));
}
int main(int argc, char **argv) {
    assert(argc == 5);
    if (!am_core_open(argv[1], argv[2], argv[3], argv[4], video)) {
        fprintf(stderr, "open: %s\n", am_core_error()); return 1;
    }
    char state[4096]; snprintf(state, sizeof(state), "%s.state", argv[4]); remove(state);
    assert(!am_core_load_state(state));
    for (int i = 0; i < 150; i++) frame(i % 30 < 4 ? 1 << RETRO_DEVICE_ID_JOYPAD_START : 0);
    if (!am_core_save_state(state)) { fprintf(stderr, "save: %s\n", am_core_error()); return 2; }
    for (int i = 0; i < 40; i++) frame(i % 10 < 3 ? 1 << RETRO_DEVICE_ID_JOYPAD_A : 0);
    uint64_t expected = image_hash;
    if (!am_core_load_state(state)) { fprintf(stderr, "load: %s\n", am_core_error()); return 3; }
    for (int i = 0; i < 40; i++) frame(i % 10 < 3 ? 1 << RETRO_DEVICE_ID_JOYPAD_A : 0);
    if (image_hash != expected) { fprintf(stderr, "restored frame did not match\n"); return 4; }
    FILE *f = fopen(state, "r+b"); assert(f); assert(fseek(f, -1, SEEK_END) == 0); fputc(0xfe, f); fclose(f);
    assert(!am_core_load_state(state));
    assert(am_core_reset());
    for (int i = 0; i < 10; i++) frame(0);
    am_core_close(); remove(state);
    assert(frames > 200 && width > 0 && height > 0 && audio_samples > 10000);
    printf("REAL_CORE_TEST_OK frames=%u video=%ux%u audio=%ld deterministic_save_load=yes corruption_rejected=yes reset=yes\n", frames, width, height, audio_samples);
    return 0;
}
