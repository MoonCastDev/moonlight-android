package com.xstreaming.nano;

import org.jcodec.codecs.h264.H264Utils;
import org.jcodec.codecs.h264.io.model.SeqParameterSet;
import org.jcodec.codecs.h264.io.model.VUIParameters;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * MoonCast: the Xbox's H.264 SPS has no bitstream restrictions, so decoders like Amlogic's assume frames may be
 * reordered and hold up to a full DPB (16 frames at 1080p) before showing any: the picture lags and stutters even
 * though 60 fps arrive. Same fix Moonlight applies to GameStream's SPS: say there's no reordering and only as much
 * buffering as there are reference frames, so each frame comes out as soon as it's decoded.
 */
final class MoonCastSpsFix {
    private MoonCastSpsFix() {}

    /** An Annex B access unit with its SPS patched, or the input unchanged if it has no SPS or can't be parsed. */
    static ByteBuffer patch(ByteBuffer au) {
        try {
            byte[] in = new byte[au.remaining()];
            au.duplicate().get(in);
            int sps = -1, spsEnd = -1;
            for (int i = 0; i + 3 < in.length; i++) {
                if (in[i] == 0 && in[i + 1] == 0 && in[i + 2] == 1) {
                    int nal = i + 3;
                    if (sps >= 0 && spsEnd < 0) spsEnd = (i > 0 && in[i - 1] == 0) ? i - 1 : i;
                    if (sps < 0 && (in[nal] & 0x1f) == 7) sps = nal;
                }
            }
            if (sps < 0) return au;
            if (spsEnd < 0) spsEnd = in.length;

            ByteBuffer body = ByteBuffer.wrap(in, sps + 1, spsEnd - sps - 1);
            SeqParameterSet s = H264Utils.readSPS(body);
            if (s.vuiParams == null) s.vuiParams = new VUIParameters();
            if (s.vuiParams.bitstreamRestriction == null) {
                VUIParameters.BitstreamRestriction r = new VUIParameters.BitstreamRestriction();
                r.motionVectorsOverPicBoundariesFlag = true;
                r.maxBytesPerPicDenom = 2;
                r.maxBitsPerMbDenom = 1;
                r.log2MaxMvLengthHorizontal = 16;
                r.log2MaxMvLengthVertical = 16;
                s.vuiParams.bitstreamRestriction = r;
            }
            s.vuiParams.bitstreamRestriction.numReorderFrames = 0;
            s.vuiParams.bitstreamRestriction.maxDecFrameBuffering = Math.max(1, s.numRefFrames);
            ByteBuffer escaped = H264Utils.writeSPS(s, spsEnd - sps + 16);

            ByteArrayOutputStream out = new ByteArrayOutputStream(in.length + 16);
            out.write(in, 0, sps + 1);                       // everything before + the SPS NAL header byte
            byte[] e = new byte[escaped.remaining()];
            escaped.get(e);
            out.write(e, 0, e.length);
            out.write(in, spsEnd, in.length - spsEnd);       // PPS, IDR slice, ...
            byte[] patched = out.toByteArray();
            android.util.Log.i("NanoRustBridge", "SPS patched: refs=" + s.numRefFrames + " reorder=0 size " + in.length + "->" + patched.length);
            ByteBuffer r = ByteBuffer.allocateDirect(patched.length);
            r.put(patched);
            r.flip();
            return r;
        } catch (Throwable t) {
            android.util.Log.w("NanoRustBridge", "SPS patch skipped", t);
            return au;
        }
    }
}
