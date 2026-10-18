import sys
# MoonCast (#37 follow-up): once the PS5 session could be freed again, ending a game whose stream the PS5 had already
# closed (Disconnect -> rest mode) crashed the app: kill_decoder() found no input buffer for EOS, stopped the codec and
# deleted it while the output thread was still inside AMediaCodec_dequeueOutputBuffer (MediaCodec CHECK abort).
# Fix: wait for the output thread there too, as the EOS path already does. stop() wakes its dequeue and the thread
# exits on shutdown_output. Run with android/app/src/main/cpp/video-decoder.c as the argument.
p = sys.argv[1]
s = open(p).read()
if "MoonCast (#37 follow-up)" in s:
    print("already patched"); sys.exit(0)
old = """		CHIAKI_LOGE(decoder->log, "Failed to get input buffer for shutting down Video Decoder!");
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
	}"""
new = """		CHIAKI_LOGE(decoder->log, "Failed to get input buffer for shutting down Video Decoder!");
		AMediaCodec_stop(decoder->codec);
		chiaki_mutex_unlock(&decoder->codec_mutex);
		// MoonCast (#37 follow-up): never delete the codec under a running output thread
		chiaki_thread_join(&decoder->output_thread, NULL);
	}"""
assert s.count(old) == 1, "kill_decoder no-input-buffer branch not found"
s = s.replace(old, new, 1)
open(p, "w").write(s)
print("patched")
