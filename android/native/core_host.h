#ifndef AMRETRO_CORE_HOST_H
#define AMRETRO_CORE_HOST_H
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef void (*am_video_callback)(const void *pixels, unsigned width, unsigned height, size_t pitch, unsigned pixel_format);
int am_core_open(const char *library, const char *rom, const char *system_dir, const char *save_path, am_video_callback video);
void am_core_frame(uint16_t buttons);
size_t am_core_take_audio(int16_t *buffer, size_t max_samples);
double am_core_sample_rate(void);
double am_core_fps(void);
/* Call state/reset operations on the same thread as am_core_frame. */
int am_core_save_state(const char *path);
int am_core_load_state(const char *path);
int am_core_reset(void);
void am_core_close(void);
const char *am_core_error(void);
#endif
