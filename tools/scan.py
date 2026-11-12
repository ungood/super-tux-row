import serial, time, sys, json
s = serial.Serial("/dev/ttyACM0", 19200, timeout=0.05)
def cmd(c):
    s.write((c + "\r\n").encode())
    end = time.time() + 0.5
    while time.time() < end:
        l = s.readline().decode().strip()
        if l.startswith("ID") or l == "ERROR":
            return l
    return None
cmd("USB"); time.sleep(0.3); s.reset_input_buffer()
out = {}
for a in range(0x000, 0x400):
    r = cmd(f"IRS{a:03X}")
    out[f"{a:03X}"] = r[6:] if r and r.startswith("IDS") else r
s.write(b"EXIT\r\n")
json.dump(out, open(sys.argv[1], "w"), indent=0)
ok = [k for k, v in out.items() if v not in ("ERROR", None)]
print(len(ok), "readable;", sum(v is None for v in out.values()), "no-reply")
# print readable ranges
rng=[]; 
for k in ok:
    i=int(k,16)
    if rng and rng[-1][1]==i-1: rng[-1][1]=i
    else: rng.append([i,i])
print(" ".join(f"{a:03X}-{b:03X}" if a!=b else f"{a:03X}" for a,b in rng))
