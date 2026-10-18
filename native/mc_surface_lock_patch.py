import sys
# MoonCast (#37): removing the surface froze the PS5 decoder for good. set_surface(NULL) holds codec_mutex and then
# calls kill_decoder(), which takes the same (non-recursive) mutex again, so the thread deadlocks. It also returned
# with the mutex still held. The session could then never be freed, so every PS5 game kept one hardware decoder
# until the app was killed, and PC streams on the stick failed (-5) once they ran out.
# Fix: release the mutex before kill_decoder() (it locks it itself), and release the old window too.
# Run with android/app/src/main/cpp/video-decoder.c as the argument.
p = sys.argv[1]
s = open(p).read()
if "MoonCast (#37)" in s:
    print("already patched"); sys.exit(0)
old = """	if(!surface)
	{
		if(decoder->codec)
		{
			kill_decoder(decoder);
			CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
		}
		return;
	}"""
new = """	if(!surface)
	{
		// MoonCast (#37): kill_decoder() takes codec_mutex itself and it isn't recursive, so release it first
		chiaki_mutex_unlock(&decoder->codec_mutex);
		if(decoder->codec)
		{
			kill_decoder(decoder);
			CHIAKI_LOGI(decoder->log, "Decoder shut down after surface was removed");
		}
		if(decoder->window)
		{
			ANativeWindow_release(decoder->window);
			decoder->window = NULL;
		}
		return;
	}"""
assert s.count(old) == 1, "set_surface(NULL) block not found"
s = s.replace(old, new, 1)
open(p, "w").write(s)
print("patched")
