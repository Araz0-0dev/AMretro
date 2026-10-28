#include "../native/core_host.h"
#include <assert.h>
#include <stdio.h>
#include <string.h>

int main(int argc,char **argv) {
    assert(argc==4); /* fake fullpath .so, writable directory, save path */
    char first[4096],second[4096],state[4096];
    snprintf(first,sizeof(first),"%s/fullpath-a.bin",argv[2]);
    snprintf(second,sizeof(second),"%s/fullpath-b.bin",argv[2]);
    snprintf(state,sizeof(state),"%s/fullpath.state",argv[2]);
    FILE *a=fopen(first,"wb"),*b=fopen(second,"wb");assert(a&&b);
    for(int i=0;i<200000;i++) {fputc('x',a);fputc(i==100000?'y':'x',b);}
    fclose(a);fclose(b);
    assert(am_core_open(argv[1],first,argv[2],argv[3],NULL));
    assert(am_core_save_state(state));am_core_close();
    assert(am_core_open(argv[1],second,argv[2],argv[3],NULL));
    assert(!am_core_load_state(state));
    assert(strstr(am_core_error(),"another game"));
    am_core_close();remove(first);remove(second);remove(state);
    puts("FULLPATH_SIGNATURE_TEST_OK same first/last 64KiB but differing middle rejected");
    return 0;
}
