#include "../native/libretro.h"
#include <stdbool.h>
#include <string.h>
static retro_environment_t env; static retro_video_refresh_t video; static retro_audio_sample_t sample; static retro_audio_sample_batch_t batch; static retro_input_state_t input; static retro_input_poll_t poll; static unsigned char sram[8];
unsigned retro_api_version(void){return 1;}
void retro_set_environment(retro_environment_t f){env=f;}
void retro_set_video_refresh(retro_video_refresh_t f){video=f;}
void retro_set_audio_sample(retro_audio_sample_t f){sample=f;}
void retro_set_audio_sample_batch(retro_audio_sample_batch_t f){batch=f;}
void retro_set_input_poll(retro_input_poll_t f){poll=f;}
void retro_set_input_state(retro_input_state_t f){input=f;}
void retro_init(void){memset(sram,0,sizeof(sram));enum retro_pixel_format fmt=RETRO_PIXEL_FORMAT_RGB565;env(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT,&fmt);const struct retro_variable v[]={{"test_option","Test; enabled|disabled"},{NULL,NULL}};env(RETRO_ENVIRONMENT_SET_VARIABLES,(void*)v);}
void retro_deinit(void){}
void retro_get_system_info(struct retro_system_info *i){memset(i,0,sizeof(*i));i->library_name="AMretro test";i->valid_extensions="bin";}
void retro_get_system_av_info(struct retro_system_av_info *i){memset(i,0,sizeof(*i));i->geometry.base_width=2;i->geometry.base_height=2;i->timing.fps=60;i->timing.sample_rate=44100;}
bool retro_load_game(const struct retro_game_info *i){return i && i->data && i->size>0;}
void retro_unload_game(void){}
void *retro_get_memory_data(unsigned id){return id==RETRO_MEMORY_SAVE_RAM?sram:NULL;}
size_t retro_get_memory_size(unsigned id){return id==RETRO_MEMORY_SAVE_RAM?sizeof(sram):0;}
void retro_run(void){
    poll();if(input(0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_A))sram[0]=42;
    struct retro_variable v={"test_option",NULL};if(!env(RETRO_ENVIRONMENT_GET_VARIABLE,&v)||strcmp(v.value,"enabled"))sram[1]=99;
    uint16_t pixels[]={sram[0],0,0,0};video(pixels,2,2,4);const int16_t pcm[]={100,-100,50,-50};batch(pcm,2);(void)sample;
}
