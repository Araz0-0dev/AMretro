#include "../native/core_host.h"
#include <assert.h>
#include <stdio.h>
#include <stdint.h>
static int frames;static unsigned maxw,maxh;static unsigned changed;
static void video(const void *data,unsigned w,unsigned h,size_t pitch,unsigned format){(void)format;if(data&&w&&h&&pitch){frames++;maxw=w;maxh=h;const unsigned char *p=data;for(size_t i=1;i<pitch*h;i++)if(p[i]!=p[0]){changed=1;break;}}}
int main(int argc,char **argv){assert(argc==5);if(!am_core_open(argv[1],argv[2],argv[3],argv[4],video)){fprintf(stderr,"open failed: %s\n",am_core_error());return 1;}char state[4096];snprintf(state,sizeof(state),"%s.state",argv[4]);long audio_samples=0;for(int i=0;i<300;i++){am_core_frame(i%60<10 ? 1<<8 : 0);int16_t samples[32768];audio_samples+=(long)am_core_take_audio(samples,32768);if(i==100&&!am_core_save_state(state)){fprintf(stderr,"save failed: %s\n",am_core_error());return 2;}if(i==200&&!am_core_load_state(state)){fprintf(stderr,"load failed: %s\n",am_core_error());return 3;}}printf("mGBA: frames=%d size=%ux%u audio_samples=%ld distinct_pixels=%u save/load=ok\n",frames,maxw,maxh,audio_samples,changed);am_core_close();remove(state);assert(frames>100&&maxw==160&&maxh==144&&audio_samples>10000&&changed);return 0;}
