import sys
# MoonCast (#37): when the screen surface goes away under a running decoder, the codec errors out and
# AMediaCodec_getInputBuffer() returns NULL; chiaki then copied the frame into it (SIGSEGV in video_sample, seen on the
# Mi stick in test177). Drop the frame instead. Run after mc_decoder_shutdown_patch.py, with
# android/app/src/main/cpp/video-decoder.c as the argument.
p = sys.argv[1]
s = open(p).read()
if "MoonCast (#37): no input buffer" in s:
    print("already patched"); sys.exit(0)
old = """		uint8_t *codec_buf = AMediaCodec_getInputBuffer(decoder->codec, (size_t)codec_buf_index, &codec_buf_size);
"""
new = old + """		if(!codec_buf) // MoonCast (#37): no input buffer, the codec has failed (e.g. its surface is gone)
		{
			CHIAKI_LOGE(decoder->log, "AMediaCodec_getInputBuffer() returned no buffer");
			r = false;
			goto beach;
		}
"""
assert s.count(old) == 1, "getInputBuffer call not found"
s = s.replace(old, new, 1)
open(p, "w").write(s)
print("patched")
