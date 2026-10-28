#include "../native/core_host.h"
#include <assert.h>
#include <stdio.h>

int main(int argc,char **argv) {
    assert(argc==5); /* fake core, ROM, system directory, save path */
    char bios[4096];snprintf(bios,sizeof(bios),"%s/gb_bios.bin",argv[3]);remove(bios);
    assert(!am_core_open(argv[1],argv[2],argv[3],argv[4],NULL));
    FILE *f=fopen(bios,"wb");assert(f);assert(fwrite("biosfixture",1,11,f)==11);fclose(f);
    assert(am_core_open(argv[1],argv[2],argv[3],argv[4],NULL));am_core_close();remove(bios);
    puts("BIOS_ROUTING_TEST_OK user system directory passed to core");
    return 0;
}
