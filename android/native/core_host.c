/* Small software-rendered libretro host used by AMretro's Android player. */
#include "core_host.h"
#include "libretro.h"
#include <dlfcn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdarg.h>
#include <limits.h>
#include <unistd.h>

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif
#define MAX_OPTIONS 512
#define AUDIO_SAMPLES 32768
#define MAX_ROM_BYTES (256u * 1024u * 1024u)
#define MAX_STATE_BYTES (64u * 1024u * 1024u)
#define STATE_HEADER_SIZE 32u

static void *handle, *rom_data;
static char error_text[256], system_path[PATH_MAX], save_file[PATH_MAX], save_directory[PATH_MAX];
static bool core_initialized, loaded;
static unsigned pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
static am_video_callback video_sink;
static uint16_t current_buttons;
static int16_t audio_data[AUDIO_SAMPLES];
static size_t audio_count;
static double sample_rate = 32768, frame_rate = 60;
static uint64_t content_signature;
struct option { char *key, *value; };
static struct option options[MAX_OPTIONS];
static size_t option_count;

static void (*p_retro_init)(void), (*p_retro_deinit)(void), (*p_retro_run)(void), (*p_retro_unload_game)(void);
static void (*p_retro_set_environment)(retro_environment_t);
static void (*p_retro_set_video_refresh)(retro_video_refresh_t);
static void (*p_retro_set_audio_sample)(retro_audio_sample_t);
static void (*p_retro_set_audio_sample_batch)(retro_audio_sample_batch_t);
static void (*p_retro_set_input_poll)(retro_input_poll_t);
static void (*p_retro_set_input_state)(retro_input_state_t);
static void (*p_retro_get_system_info)(struct retro_system_info *);
static void (*p_retro_get_system_av_info)(struct retro_system_av_info *);
static bool (*p_retro_load_game)(const struct retro_game_info *);
static void *(*p_retro_get_memory_data)(unsigned);
static size_t (*p_retro_get_memory_size)(unsigned);
static size_t (*p_retro_serialize_size)(void);
static bool (*p_retro_serialize)(void *, size_t), (*p_retro_unserialize)(const void *, size_t);
static void (*p_retro_reset)(void);

static void set_error(const char *text) { snprintf(error_text, sizeof(error_text), "%s", text ? text : "Unknown error"); }
const char *am_core_error(void) { return error_text; }

/* Non-cryptographic fingerprints detect accidental game/core mismatch and damage. */
static uint64_t fingerprint(uint64_t value, const void *data, size_t size) {
    const unsigned char *p = data;
    while (size--) { value ^= *p++; value *= UINT64_C(1099511628211); }
    return value;
}
static bool fingerprint_file(uint64_t *value, const char *path) {
    FILE *f = fopen(path, "rb"); if (!f) return false;
    unsigned char b[65536]; size_t n;
    while ((n = fread(b, 1, sizeof(b), f)) != 0) *value = fingerprint(*value, b, n);
    bool ok = !ferror(f); if (fclose(f) != 0) ok = false;
    return ok;
}
static void put_u64(unsigned char *p, uint64_t n) { for (unsigned i = 0; i < 8; i++) p[i] = (unsigned char)(n >> (i * 8)); }
static uint64_t get_u64(const unsigned char *p) { uint64_t n = 0; for (unsigned i = 0; i < 8; i++) n |= (uint64_t)p[i] << (i * 8); return n; }
static void core_log(enum retro_log_level level, const char *fmt, ...) { (void)level; (void)fmt; }
static void clear_options(void) {
    for (size_t i = 0; i < option_count; i++) { free(options[i].key); free(options[i].value); }
    option_count = 0;
}
static void collect_options(const struct retro_variable *v) {
    clear_options();
    while (v && v->key && v->value && option_count < MAX_OPTIONS) {
        const char *start = strchr(v->value, ';');
        if (!start) { v++; continue; }
        start++;
        while (*start == ' ') start++;
        const char *end = strchr(start, '|');
        size_t len = end ? (size_t)(end - start) : strlen(start);
        while (len && start[len-1] == ' ') len--;
        char *key = strdup(v->key), *value = malloc(len + 1);
        if (!key || !value) { free(key); free(value); return; }
        memcpy(value, start, len); value[len] = 0;
        options[option_count++] = (struct option){key, value};
        v++;
    }
}
static bool environment(unsigned command, void *data) {
    switch (command) {
    case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
        if (!data) return false;
        *(const char **)data = system_path; return true;
    case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
        if (!data) return false;
        *(const char **)data = save_directory; return true;
    case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
        if (!data) return false;
        if (*(const enum retro_pixel_format *)data > RETRO_PIXEL_FORMAT_RGB565) return false;
        pixel_format = *(const enum retro_pixel_format *)data; return true;
    case RETRO_ENVIRONMENT_GET_CAN_DUPE:
        if (!data) return false;
        *(bool *)data = true; return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE:
        if (data) {
            struct retro_variable *var = data; var->value = NULL;
            for (size_t i = 0; i < option_count; i++) if (!strcmp(var->key, options[i].key)) { var->value = options[i].value; return true; }
        }
        return false;
    case RETRO_ENVIRONMENT_SET_VARIABLES: collect_options(data); return true;
    case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE:
        if (!data) return false;
        *(bool *)data = false; return true;
    case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
        if (!data) return false;
        *(unsigned *)data = 0; return true;
    case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
        if (!data) return false;
        ((struct retro_log_callback *)data)->log = core_log; return true;
    case RETRO_ENVIRONMENT_GET_LANGUAGE:
        if (!data) return false;
        *(unsigned *)data = RETRO_LANGUAGE_ENGLISH; return true;
    case RETRO_ENVIRONMENT_GET_INPUT_MAX_USERS:
        if (!data) return false;
        *(unsigned *)data = 1; return true;
    case RETRO_ENVIRONMENT_GET_OVERSCAN:
        if (!data) return false;
        *(bool *)data = false; return true;
    case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
    case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
    case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
    case RETRO_ENVIRONMENT_SET_GEOMETRY:
        return true;
    default: return false;
    }
}
static void video_frame(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (data && data != RETRO_HW_FRAME_BUFFER_VALID && video_sink) video_sink(data, width, height, pitch, pixel_format);
}
static void audio_sample(int16_t left, int16_t right) {
    if (audio_count + 2 <= AUDIO_SAMPLES) { audio_data[audio_count++] = left; audio_data[audio_count++] = right; }
}
static size_t audio_batch(const int16_t *data, size_t frames) {
    size_t count = frames > SIZE_MAX / 2 ? 0 : frames * 2;
    size_t available = AUDIO_SAMPLES - audio_count;
    size_t to_copy = count < available ? count : available;
    to_copy &= ~(size_t)1;
    if (to_copy) { memcpy(audio_data + audio_count, data, to_copy * sizeof(int16_t)); audio_count += to_copy; }
    return frames;
}
static void input_poll(void) {}
static int16_t input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    (void)index;
    if (port || device != RETRO_DEVICE_JOYPAD) return 0;
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t)current_buttons;
    return id < 16 ? (int16_t)((current_buttons >> id) & 1u) : 0;
}
#define LOAD(name) do { *(void **)(&p_##name) = dlsym(handle, #name); if (!p_##name) { set_error("Libretro core is missing " #name); goto fail; } } while (0)

static void read_battery(void) {
    size_t len = p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *ptr = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!len || !ptr || len > MAX_ROM_BYTES) return;
    FILE *file = fopen(save_file, "rb");
    if (file) { (void)fread(ptr, 1, len, file); fclose(file); }
}
static void write_battery(void) {
    size_t len = p_retro_get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void *ptr = p_retro_get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!len || !ptr || len > MAX_ROM_BYTES || !*save_file) return;
    char temp[PATH_MAX];
    if (snprintf(temp, sizeof(temp), "%s.tmp", save_file) >= (int)sizeof(temp)) return;
    FILE *file = fopen(temp, "wb");
    if (!file) return;
    bool ok = fwrite(ptr, 1, len, file) == len && fflush(file) == 0 && fsync(fileno(file)) == 0;
    if (fclose(file) != 0) ok = false;
    if (ok) { if (rename(temp, save_file) != 0) unlink(temp); }
    else unlink(temp);
}

int am_core_open(const char *library, const char *rom, const char *system_dir, const char *save_path, am_video_callback video) {
    if (handle) am_core_close();
    error_text[0] = 0;
    if (!library || !rom || !system_dir || !save_path || strlen(system_dir) >= sizeof(system_path) || strlen(save_path) >= sizeof(save_file)) { set_error("Invalid game path"); return 0; }
    snprintf(system_path, sizeof(system_path), "%s", system_dir);
    snprintf(save_file, sizeof(save_file), "%s", save_path);
    snprintf(save_directory, sizeof(save_directory), "%s", save_path);
    char *slash = strrchr(save_directory, '/');
    if (!slash) snprintf(save_directory, sizeof(save_directory), ".");
    else if (slash == save_directory) slash[1] = 0;
    else *slash = 0;
    video_sink = video; audio_count = 0; pixel_format = RETRO_PIXEL_FORMAT_0RGB1555;
    handle = dlopen(library, RTLD_NOW | RTLD_LOCAL);
    if (!handle) { set_error(dlerror()); return 0; }
    LOAD(retro_init); LOAD(retro_deinit); LOAD(retro_run); LOAD(retro_unload_game);
    LOAD(retro_set_environment); LOAD(retro_set_video_refresh); LOAD(retro_set_audio_sample);
    LOAD(retro_set_audio_sample_batch); LOAD(retro_set_input_poll); LOAD(retro_set_input_state);
    LOAD(retro_get_system_info); LOAD(retro_get_system_av_info); LOAD(retro_load_game);
    LOAD(retro_get_memory_data); LOAD(retro_get_memory_size);
    *(void **)(&p_retro_serialize_size) = dlsym(handle, "retro_serialize_size");
    *(void **)(&p_retro_serialize) = dlsym(handle, "retro_serialize");
    *(void **)(&p_retro_unserialize) = dlsym(handle, "retro_unserialize");
    *(void **)(&p_retro_reset) = dlsym(handle, "retro_reset");
    p_retro_set_environment(environment); p_retro_set_video_refresh(video_frame);
    p_retro_set_audio_sample(audio_sample); p_retro_set_audio_sample_batch(audio_batch);
    p_retro_set_input_poll(input_poll); p_retro_set_input_state(input_state);
    struct retro_system_info info = {0}; p_retro_get_system_info(&info);
    p_retro_init(); core_initialized = true;
    struct retro_game_info game = {0}; game.path = rom;
    if (!info.need_fullpath) {
        FILE *file = fopen(rom, "rb");
        if (!file) { set_error("Game file could not be opened"); goto fail; }
        if (fseek(file, 0, SEEK_END) != 0) { fclose(file); set_error("Cannot read game"); goto fail; }
        long size = ftell(file);
        if (size <= 0 || (unsigned long)size > MAX_ROM_BYTES || fseek(file, 0, SEEK_SET) != 0) { fclose(file); set_error("Game file is empty or too large"); goto fail; }
        rom_data = malloc((size_t)size);
        if (!rom_data) { fclose(file); set_error("Not enough memory"); goto fail; }
        if (fread(rom_data, 1, (size_t)size, file) != (size_t)size) { fclose(file); set_error("Cannot read game"); goto fail; }
        fclose(file); game.data = rom_data; game.size = (size_t)size;
    }
    content_signature = UINT64_C(14695981039346656037);
    if (!fingerprint_file(&content_signature, library)) { set_error("Cannot fingerprint the emulation core"); goto fail; }
    if (game.data) content_signature = fingerprint(content_signature, game.data, game.size);
    else if (!fingerprint_file(&content_signature, rom)) { set_error("Cannot fingerprint the game file"); goto fail; }
    if (!p_retro_load_game(&game)) { set_error("The core cannot load this game file"); goto fail; }
    loaded = true; read_battery();
    struct retro_system_av_info av = {0}; p_retro_get_system_av_info(&av);
    sample_rate = av.timing.sample_rate > 4000 && av.timing.sample_rate < 192001 ? av.timing.sample_rate : 32768;
    frame_rate = av.timing.fps > 1 && av.timing.fps < 241 ? av.timing.fps : 60;
    return 1;
fail:
    am_core_close(); return 0;
}
void am_core_frame(uint16_t buttons) { if (loaded) { current_buttons = buttons; p_retro_run(); } }
size_t am_core_take_audio(int16_t *buffer, size_t max_samples) {
    size_t count = audio_count < max_samples ? audio_count : max_samples;
    if (count) memcpy(buffer, audio_data, count * sizeof(int16_t));
    if (count < audio_count) memmove(audio_data, audio_data + count, (audio_count - count) * sizeof(int16_t));
    audio_count -= count; return count;
}
double am_core_sample_rate(void) { return sample_rate; }
double am_core_fps(void) { return frame_rate; }

static const unsigned char state_magic[8] = {'A','M','R','S','T','0','1',0};
int am_core_save_state(const char *path) {
    if (!loaded || !path || !p_retro_serialize_size || !p_retro_serialize) { set_error("Save states are not supported by this core"); return 0; }
    size_t size = p_retro_serialize_size();
    if (!size || size > MAX_STATE_BYTES) { set_error("This game does not support save states"); return 0; }
    unsigned char *data = malloc(size); if (!data) { set_error("Not enough memory to save"); return 0; }
    if (!p_retro_serialize(data, size)) { free(data); set_error("Core could not create save state"); return 0; }
    unsigned char header[STATE_HEADER_SIZE]; memcpy(header, state_magic, 8);
    put_u64(header + 8, size); put_u64(header + 16, content_signature);
    put_u64(header + 24, fingerprint(UINT64_C(14695981039346656037), data, size));
    char temp[PATH_MAX];
    if (snprintf(temp, sizeof(temp), "%s.tmp", path) >= (int)sizeof(temp)) { free(data); set_error("Save path is too long"); return 0; }
    FILE *f = fopen(temp, "wb");
    if (!f) { free(data); set_error("Cannot create save state"); return 0; }
    bool ok = fwrite(header, 1, sizeof(header), f) == sizeof(header) && fwrite(data, 1, size, f) == size && fflush(f) == 0 && fsync(fileno(f)) == 0;
    if (fclose(f) != 0) ok = false;
    free(data);
    if (ok) ok = rename(temp, path) == 0;
    if (!ok) { unlink(temp); set_error("Cannot write save state"); return 0; }
    error_text[0] = 0; return 1;
}
int am_core_load_state(const char *path) {
    if (!loaded || !path || !p_retro_serialize_size || !p_retro_unserialize) { set_error("Save states are not supported by this core"); return 0; }
    FILE *f = fopen(path, "rb"); if (!f) { set_error("No save state exists in this slot"); return 0; }
    unsigned char header[STATE_HEADER_SIZE];
    if (fread(header, 1, sizeof(header), f) != sizeof(header) || memcmp(header, state_magic, 8) != 0) { fclose(f); set_error("Save state header is invalid"); return 0; }
    uint64_t size64 = get_u64(header + 8), expected_signature = get_u64(header + 16), expected_hash = get_u64(header + 24);
    size_t core_size = p_retro_serialize_size();
    if (!core_size || !size64 || size64 > MAX_STATE_BYTES || expected_signature != content_signature || size64 < core_size) {
        fclose(f); set_error("Save state belongs to another game or core version"); return 0;
    }
    size_t size = (size_t)size64; unsigned char *data = malloc(size);
    if (!data) { fclose(f); set_error("Not enough memory to load"); return 0; }
    bool valid = fread(data, 1, size, f) == size && fgetc(f) == EOF;
    fclose(f);
    if (!valid || fingerprint(UINT64_C(14695981039346656037), data, size) != expected_hash) { free(data); set_error("Save state is damaged"); return 0; }
    size_t backup_size = p_retro_serialize_size();
    unsigned char *backup = backup_size && backup_size <= MAX_STATE_BYTES ? malloc(backup_size) : NULL;
    if (!backup || !p_retro_serialize || !p_retro_serialize(backup, backup_size)) {
        free(data); free(backup); set_error("Cannot protect the current game before loading"); return 0;
    }
    bool ok = p_retro_unserialize(data, size); free(data);
    if (!ok) {
        bool restored = p_retro_unserialize(backup, backup_size); free(backup);
        set_error(restored ? "Core rejected save state; current game was restored" : "Core rejected save state; restart the game"); return 0;
    }
    free(backup); audio_count = 0; error_text[0] = 0; return 1;
}
int am_core_reset(void) {
    if (!loaded || !p_retro_reset) { set_error("This core cannot reset the game"); return 0; }
    p_retro_reset(); audio_count = 0; error_text[0] = 0; return 1;
}
void am_core_close(void) {
    if (loaded) { write_battery(); p_retro_unload_game(); loaded = false; }
    if (core_initialized) { p_retro_deinit(); core_initialized = false; }
    if (handle) { dlclose(handle); handle = NULL; }
    free(rom_data); rom_data = NULL; video_sink = NULL; audio_count = 0; content_signature = 0;
    p_retro_serialize_size = NULL; p_retro_serialize = NULL; p_retro_unserialize = NULL; p_retro_reset = NULL; clear_options();
}
