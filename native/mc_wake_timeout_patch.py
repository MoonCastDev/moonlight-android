import sys
p = sys.argv[1]  # lib/src/remote/holepunch.c
s = open(p).read()
if "SESSION_START_WAKE_TIMEOUT_SEC" in s:
    print("already patched"); sys.exit(0)
d = "#define SESSION_START_TIMEOUT_SEC 30"
assert s.count(d) == 1
s = s.replace(d, d + """
// MoonCast: a console in rest mode only joins the PSN session (MEMBER_CREATED) once it has woken up, which
// took ~46 s in testing (30 s always failed). Not longer: a PS5 still *entering* rest mode ignores the start request,
// and waiting on the same request never helps - a fresh attempt does (connect() retries).
#define SESSION_START_WAKE_TIMEOUT_SEC 60""", 1)
w = """    int notif_query = NOTIFICATION_TYPE_MEMBER_CREATED
                    | NOTIFICATION_TYPE_CUSTOM_DATA1_UPDATED;
    while (!finished)
    {
        err = wait_for_notification(session, &notif, notif_query, SESSION_START_TIMEOUT_SEC * 1000);"""
assert s.count(w) == 1
s = s.replace(w, w.replace("SESSION_START_TIMEOUT_SEC * 1000", "SESSION_START_WAKE_TIMEOUT_SEC * 1000"), 1)
open(p, "w").write(s)
print("patched")
