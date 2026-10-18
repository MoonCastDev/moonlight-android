import re, sys
# MoonCast (#37): a clean decoder shutdown for chiaki's Android video decoder. Run after mc_surface_lock_patch.py, with
# android/app/src/main/cpp/video-decoder.c as the argument. Replaces mc_decoder_join_patch.py (test176), which only
# fixed part of it.
#
# On Android 9 (the Mi stick) any AMediaCodec dequeue after AMediaCodec_stop() posts a second activity request to
# MediaCodec and it aborts the app (F/MediaCodec CHECK(mActivityNotify == NULL)). chiaki's kill_decoder() stopped the
# codec first and only then let the other threads go: the stream thread, waiting on codec_mutex, fed one more frame
# into the stopped codec, and the output thread's endless dequeue returned after the stop. Ending a PS5 game then
# crashed the app at random (2 of 3 stops in testing).
# Fix: stop feeding and drain first, stop and delete last.
#  - video_sample(): no input while shutting down.
#  - output thread: polls every 50 ms instead of waiting forever, so it sees shutdown_output and exits by itself.
#  - kill_decoder(): flag shutdown, send EOS if a buffer is free, let the output thread end, then stop and delete
#    the codec under the mutex, so no thread can call into it afterwards.
p = sys.argv[1]
s = open(p).read()
if "MoonCast (#37): clean shutdown" in s:
    print("already patched"); sys.exit(0)

kill = re.compile(r"static void kill_decoder\(AndroidChiakiVideoDecoder \*decoder\)\n\{\n.*?\n\tdecoder->shutdown_output = false;\n\}\n", re.S)
assert len(kill.findall(s)) == 1, "kill_decoder() not found"
s = kill.sub(lambda m: """static void kill_decoder(AndroidChiakiVideoDecoder *decoder)
{
	// MoonCast (#37): clean shutdown. No AMediaCodec call may follow AMediaCodec_stop(): on Android 9 that aborts.
	chiaki_mutex_lock(&decoder->codec_mutex);
	decoder->shutdown_output = true; // video_sample() stops feeding, the output thread ends on its next poll
	ssize_t codec_buf_index = AMediaCodec_dequeueInputBuffer(decoder->codec, 1000);
	if(codec_buf_index >= 0)
	{
		CHIAKI_LOGI(decoder->log, "Video Decoder sending EOS buffer");
		AMediaCodec_queueInputBuffer(decoder->codec, (size_t)codec_buf_index, 0, 0, decoder->timestamp_cur++, AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM);
	}
	else
		CHIAKI_LOGE(decoder->log, "Failed to get input buffer for shutting down Video Decoder!");
	chiaki_mutex_unlock(&decoder->codec_mutex);
	chiaki_thread_join(&decoder->output_thread, NULL);
	chiaki_mutex_lock(&decoder->codec_mutex);
	AMediaCodec_stop(decoder->codec);
	AMediaCodec_delete(decoder->codec);
	decoder->codec = NULL;
	decoder->shutdown_output = false;
	chiaki_mutex_unlock(&decoder->codec_mutex);
}
""", s, count=1)

old_feed = """	if(!decoder->codec)
	{
		CHIAKI_LOGE(decoder->log, "Received video data, but decoder is not initialized!");
		goto beach;
	}
"""
new_feed = old_feed + """	if(decoder->shutdown_output)
		goto beach; // MoonCast (#37): shutting down, the codec takes no more input
"""
assert s.count(old_feed) == 1, "video_sample() check not found"
s = s.replace(old_feed, new_feed, 1)

old_out = "ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, -1);"
new_out = "ssize_t status = AMediaCodec_dequeueOutputBuffer(decoder->codec, &info, 50000); // MoonCast (#37): poll, see shutdown_output"
assert s.count(old_out) == 1, "output thread dequeue not found"
s = s.replace(old_out, new_out, 1)

open(p, "w").write(s)
print("patched")
