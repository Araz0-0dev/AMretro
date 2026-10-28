package com.amretro.mobile;

/** Streaming nearest-neighbour rate conversion for interleaved stereo PCM. */
final class StereoResampler {
    private final int inputRate, outputRate;
    private long phase;
    StereoResampler(int inputRate,int outputRate){if(inputRate<=0||outputRate<=0)throw new IllegalArgumentException();this.inputRate=inputRate;this.outputRate=outputRate;}
    short[] convert(short[] source){
        int frames=source.length/2;
        if(frames==0)return new short[0];
        int max=(int)(((long)frames*outputRate+inputRate-1)/inputRate)+2;
        short[] out=new short[max*2];int n=0;
        while(phase/outputRate<frames){int from=(int)(phase/outputRate)*2;out[n++]=source[from];out[n++]=source[from+1];phase+=inputRate;}
        phase-=(long)frames*outputRate;
        if(n==out.length)return out;
        return java.util.Arrays.copyOf(out,n);
    }
}
