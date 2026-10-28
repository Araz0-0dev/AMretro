/* Minimal, original libretro core for end-to-end control testing. No game or
   copyrighted BIOS is included. It records input states in AMRETRO_PROBE_LOG. */
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct retro_system_info { const char *library_name, *library_version, *valid_extensions; bool need_fullpath, block_extract; };
struct retro_game_geometry { unsigned base_width,base_height,max_width,max_height; float aspect_ratio; };
struct retro_system_timing { double fps,sample_rate; };
struct retro_system_av_info { struct retro_game_geometry geometry; struct retro_system_timing timing; };
struct retro_game_info { const char *path; const void *data; size_t size; const char *meta; };
typedef bool (*environment_t)(unsigned,void *);
typedef void (*video_t)(const void *,unsigned,unsigned,size_t);
typedef void (*audio_t)(int16_t,int16_t);
typedef size_t (*audio_batch_t)(const int16_t *,size_t);
typedef void (*input_poll_t)(void);
typedef int16_t (*input_state_t)(unsigned,unsigned,unsigned,unsigned);
static video_t video; static input_poll_t poll; static input_state_t input; static FILE *logfile; static unsigned count;
unsigned retro_api_version(void){return 1;}
void retro_set_environment(environment_t f){(void)f;}
void retro_set_video_refresh(video_t f){video=f;}
void retro_set_audio_sample(audio_t f){(void)f;}
void retro_set_audio_sample_batch(audio_batch_t f){(void)f;}
void retro_set_input_poll(input_poll_t f){poll=f;}
void retro_set_input_state(input_state_t f){input=f;}
void retro_init(void){const char *p=getenv("AMRETRO_PROBE_LOG");if(p) logfile=fopen(p,"a");}
void retro_deinit(void){if(logfile) fclose(logfile);logfile=NULL;}
void retro_get_system_info(struct retro_system_info *i){i->library_name="AMretro Probe";i->library_version="1";i->valid_extensions="bin";i->need_fullpath=false;i->block_extract=false;}
void retro_get_system_av_info(struct retro_system_av_info *i){memset(i,0,sizeof(*i));i->geometry.base_width=2;i->geometry.base_height=2;i->geometry.max_width=2;i->geometry.max_height=2;i->geometry.aspect_ratio=1;i->timing.fps=60;i->timing.sample_rate=44100;}
void retro_set_controller_port_device(unsigned p,unsigned d){(void)p;(void)d;}
void retro_reset(void){}
bool retro_load_game(const struct retro_game_info *g){(void)g;return true;}
bool retro_load_game_special(unsigned type,const struct retro_game_info *g,size_t n){(void)type;(void)g;(void)n;return false;}
void retro_unload_game(void){}
unsigned retro_get_region(void){return 0;}
size_t retro_serialize_size(void){return 0;}
bool retro_serialize(void *p,size_t n){(void)p;(void)n;return false;}
bool retro_unserialize(const void *p,size_t n){(void)p;(void)n;return false;}
void retro_cheat_reset(void){}
void retro_cheat_set(unsigned i,bool e,const char *c){(void)i;(void)e;(void)c;}
void *retro_get_memory_data(unsigned id){(void)id;return NULL;}
size_t retro_get_memory_size(unsigned id){(void)id;return 0;}
void retro_run(void){
    static const uint16_t pixel[4]={0,0x7fff,0x7fff,0};
    poll();int b=input(0,1,0,0),a=input(0,1,0,8);
    if(logfile && (b || a) && count++ < 200){fprintf(logfile,"B=%d A=%d\n",b,a);fflush(logfile);}
    video(pixel,2,2,2*sizeof(uint16_t));
}
