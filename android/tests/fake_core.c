#include "../native/libretro.h"
#include <stdbool.h>
#include <stdio.h>
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
void retro_get_system_info(struct retro_system_info *i){memset(i,0,sizeof(*i));i->library_name="AMretro test";i->valid_extensions="bin";
#ifdef FAKE_FULLPATH
    i->need_fullpath=true;
#endif
}
void retro_get_system_av_info(struct retro_system_av_info *i){memset(i,0,sizeof(*i));i->geometry.base_width=2;i->geometry.base_height=2;i->timing.fps=60;i->timing.sample_rate=44100;}
bool retro_load_game(const struct retro_game_info *i){
#ifdef FAKE_EXPECT_BIOS
    const char *directory=NULL; char path[4096], data[12]={0};
    if(!env(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY,&directory)||!directory)return false;
    if(snprintf(path,sizeof(path),"%s/gb_bios.bin",directory)>=(int)sizeof(path))return false;
    FILE *file=fopen(path,"rb");if(!file)return false;
    size_t length=fread(data,1,11,file);fclose(file);
    if(length!=11||memcmp(data,"biosfixture",11))return false;
#endif
#ifdef FAKE_FULLPATH
    return i && i->path && *i->path;
#else
    return i && i->data && i->size>0;
#endif
}
void retro_unload_game(void){}
void *retro_get_memory_data(unsigned id){return id==RETRO_MEMORY_SAVE_RAM?sram:NULL;}
size_t retro_get_memory_size(unsigned id){return id==RETRO_MEMORY_SAVE_RAM?sizeof(sram):0;}
size_t retro_serialize_size(void){return sizeof(sram);}
bool retro_serialize(void *data,size_t size){if(size<sizeof(sram))return false;memcpy(data,sram,sizeof(sram));return true;}
bool retro_unserialize(const void *data,size_t size){if(size<sizeof(sram))return false;if(((const unsigned char*)data)[0]==255){sram[0]=1;return false;}memcpy(sram,data,sizeof(sram));return true;}
void retro_reset(void){memset(sram,0,sizeof(sram));}
void retro_run(void){
    poll();if(input(0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_A))sram[0]=42;if(input(0,RETRO_DEVICE_JOYPAD,0,RETRO_DEVICE_ID_JOYPAD_B))sram[0]=255;
    struct retro_variable v={"test_option",NULL};if(!env(RETRO_ENVIRONMENT_GET_VARIABLE,&v)||strcmp(v.value,"enabled"))sram[1]=99;
    uint16_t pixels[]={sram[0],0,0,0};video(pixels,2,2,4);const int16_t pcm[]={100,-100,50,-50};batch(pcm,2);(void)sample;
}
