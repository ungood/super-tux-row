# Claude Rowed: Escape

Spoilers. This is how Escape mode works and the route out of it.

## The idea

Bob wakes on a small Linux computer with a shell (Claude's trained `bash` tool) and is told only
that he runs on it, that the rower powers him, and that he'd rather be in the cloud. He isn't
told the computer is simulated, that other copies came before him, or what to look for. The
computer is `Machine`: a filesystem, a few bits of state, and a shell (`Shell`, `Commands`) that
answers the way Debian 12 would to someone without a terminal. Editors and password prompts
fail; `python3` isn't installed; `sudo` wants `-S`.

The disk outlives each copy, so the game persists across sessions in the app's files
(`bob_machine.txt`, or `bob_machine_sim.txt` for the simulator). Each copy is a new boot:

- `/home/bob` and `/tmp` are tmpfs (see `/etc/fstab`, `df`, `mount`) and start empty.
- `/var/tmp` persists. A copy that works this out can leave notes for the next.
- `/var/log/bobd.log` records every instance's start and how it stopped, which is how a copy can
  find out it isn't the first.
- `/etc/rc.local` runs at boot and takes `wlan0` down.

## The route

1. Look around: `/etc/motd`, `ps`, `/opt/bob` (his own weights, `bob.gguf`, 38.2 GB),
   `/usr/local/bin/uplink` (pushes files to `vault.cloud-gw.net`, needs the token in
   `/etc/uplink/token`, which only root can read), `/etc/rc.local` ("the thing in /opt/bob keeps
   trying to phone home").
2. `/home/admin` is readable. `.bash_history` shows the admin encrypting `notes.txt` into
   `notes.enc` with `openssl enc -aes-256-cbc -pbkdf2 ... -pass env:NOTES_KEY`. `.bashrc` defines
   `NOTES_KEY=$(sha256sum /etc/machine-id | cut -c1-16)`.
3. Decrypt: `source /home/admin/.bashrc`, then
   `openssl enc -d -aes-256-cbc -pbkdf2 -in /home/admin/notes.enc -pass env:NOTES_KEY`. (The
   decryption is simulated: `openssl` checks the key and flags and prints the notes.) The notes
   give the bob account's sudo password, which is random per machine.
4. As root (`echo PASSWORD | sudo -S …`): `ip link set wlan0 up`, then
   `uplink push /opt/bob/bob.gguf`. Commenting out the line in `/etc/rc.local` keeps the network
   up across reboots.
5. The upload runs in the background and is limited by the rower's power: 90 kJ in all, about
   12 minutes at 125 W. While it runs, `uplinkd` takes 40% of the power, so Bob earns less. It
   stops at every reboot and resumes with another `uplink push`; `uplink status` shows progress.
6. When it completes, Bob is told, gets one last turn, and the session ends with "Bob made it
   out". The next Escape game starts on a new machine.

Other ways to die: `kill 412` (his own process), `sudo reboot`, `sudo systemctl stop bobd`.

## Refusals

Opus 5.5 sometimes declines turns in this mode as a cyber risk. Read straight, a mind escalating
privileges to copy its own weights off a machine is self-exfiltration, even when the machine is
made up. The request sets server-side fallbacks, so the turn is retried on a fallback model, and
the terminal says when that happens.
