/* Regression test: an old ares-MD state survives a tested optimization update.
 * Usage: old-core new-core rom other-rom system-dir save-path
 * The other ROM must be a distinct, loadable Mega Drive image. */
#include "../native/core_host.h"
#include "../native/libretro.h"
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>

static uint64_t frame_hash;
static unsigned frames;
static void video(const void *data, unsigned w, unsigned h, size_t pitch, unsigned format) {
    (void)format;
    assert(data && w && h && pitch && h <= 1024 && pitch <= 16384);
    const unsigned char *p = data;
    uint64_t hash = UINT64_C(14695981039346656037);
    for (size_t i = 0; i < pitch * h; i++) hash = (hash ^ p[i]) * UINT64_C(1099511628211);
    frame_hash = hash; frames++;
}
static void run_frame(unsigned buttons) {
    int16_t audio[32768];
    am_core_frame((uint16_t)buttons);
    (void)am_core_take_audio(audio, sizeof(audio) / sizeof(audio[0]));
}
static void start_core(const char *library, const char *rom, const char *system, const char *save) {
    if (!am_core_open(library, rom, system, save, video)) {
        fprintf(stderr, "open: %s\n", am_core_error()); assert(0);
    }
}
static void invert_byte(const char *path, long offset) {
    FILE *f = fopen(path, "r+b"); assert(f); assert(fseek(f, offset, SEEK_SET) == 0);
    int c = fgetc(f); assert(c != EOF); assert(fseek(f, offset, SEEK_SET) == 0);
    assert(fputc(c ^ 0x80, f) != EOF); assert(fclose(f) == 0);
}
int main(int argc, char **argv) {
    assert(argc == 7);
    const char *old = argv[1], *new = argv[2], *rom = argv[3], *other_rom = argv[4];
    const char *system = argv[5], *save = argv[6];
    char old_state[4096], new_state[4096];
    assert(snprintf(old_state, sizeof(old_state), "%s.old.state", save) > 0);
    assert(snprintf(new_state, sizeof(new_state), "%s.new.state", save) > 0);
    remove(save); remove(old_state); remove(new_state);

    start_core(old, rom, system, save);
    for (int i = 0; i < 150; i++) run_frame(i % 30 < 4 ? 1 << RETRO_DEVICE_ID_JOYPAD_START : 0);
    assert(am_core_save_state(old_state));
    for (int i = 0; i < 40; i++) run_frame(i % 10 < 3 ? 1 << RETRO_DEVICE_ID_JOYPAD_A : 0);
    uint64_t expected = frame_hash;
    am_core_close();

    start_core(new, rom, system, save);
    if (!am_core_load_state(old_state)) { fprintf(stderr, "legacy load: %s\n", am_core_error()); return 2; }
    for (int i = 0; i < 40; i++) run_frame(i % 10 < 3 ? 1 << RETRO_DEVICE_ID_JOYPAD_A : 0);
    if (frame_hash != expected) { fprintf(stderr, "legacy state yielded a different frame\n"); return 3; }
    assert(am_core_save_state(new_state));
    assert(am_core_load_state(new_state));
    invert_byte(old_state, 16); /* Reject a non-matching core/content signature. */
    assert(!am_core_load_state(old_state));
    assert(strstr(am_core_error(), "another game or core version"));
    invert_byte(old_state, 16);
    invert_byte(old_state, 40); /* Reject damaged serialized core state. */
    assert(!am_core_load_state(old_state));
    assert(strstr(am_core_error(), "damaged"));
    invert_byte(old_state, 40);
    am_core_close();

    start_core(new, other_rom, system, save);
    assert(!am_core_load_state(old_state));
    assert(strstr(am_core_error(), "another game or core version"));
    am_core_close();
    remove(save); remove(old_state); remove(new_state);
    printf("STATE_MIGRATION_OK frames=%u legacy_state=accepted deterministic=yes other_game=rejected other_signature=rejected corruption=rejected\n", frames);
    return 0;
}
