package com.limelight.nvstream.av.video;

public abstract class VideoDecoderRenderer {
    public abstract int setup(int format, int width, int height, int redrawRate);

    public abstract void start();

    public abstract void stop();

    // This is called once for each frame-start NALU. This means it will be called several times
    // for an IDR frame which contains several parameter sets and the I-frame data.
    public abstract int submitDecodeUnit(byte[] decodeUnitData, int decodeUnitLength, int decodeUnitType,
                                         int frameNumber, int frameType, char frameHostProcessingLatency,
                                         long receiveTimeUs, long enqueueTimeUs);
    
    // MoonCast: PyroWave frames that lost packets (see MoonBridge.bridgeDrSubmitPartialFrame). Only renderers that ask
    // for CAPABILITY_PARTIAL_FRAMES get them.
    public int submitPartialFrame(byte[] decodeUnitData, int decodeUnitLength, int frameNumber, int frameType,
                                  char frameHostProcessingLatency, long receiveTimeUs, long enqueueTimeUs,
                                  int[] missingRanges, int[] recordStarts, int partialKind) {
        return 0;
    }

    public abstract void cleanup();

    public abstract int getCapabilities();

    public abstract void setHdrMode(boolean enabled, byte[] hdrMetadata);
}
