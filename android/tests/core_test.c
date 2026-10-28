#include "../native/core_host.h"
#include "../native/libretro.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <string.h>
static int frames;static unsigned pixel;
static void video(const void *data,unsigned w,unsigned h,size_t pitch,unsigned format){assert(data&&w==2&&h==2&&pitch==4&&format==RETRO_PIXEL_FORMAT_RGB565);pixel=((const uint16_t*)data)[0];frames++;}
int main(int argc,char **argv){
    assert(argc==5);remove(argv[4]);
    assert(!am_core_open("/nonexistent/core.so",argv[2],argv[3],argv[4],video));
    assert(am_core_error()[0]);
    assert(am_core_open(argv[1],argv[2],argv[3],argv[4],video));assert(am_core_sample_rate()==44100);assert(am_core_fps()==60);
    am_core_frame(1u<<RETRO_DEVICE_ID_JOYPAD_A);assert(frames==1&&pixel==42);
    int16_t audio[16];assert(am_core_take_audio(audio,16)==4);assert(audio[0]==100&&audio[1]==-100);
    am_core_close();FILE *save=fopen(argv[4],"rb");assert(save);unsigned char b[8];assert(fread(b,1,8,save)==8);fclose(save);assert(b[0]==42&&b[1]==0);
    assert(am_core_open(argv[1],argv[2],argv[3],argv[4],video));am_core_frame(0);assert(pixel==42);am_core_close();puts("HOST_TEST_OK video audio input options SRAM persistence");return 0;
}
