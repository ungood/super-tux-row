# Sudo Ergo Es: the shared world

Spoilers. This is a design for where Escape goes next: several stages, a server that hosts them,
other rowers' Bobs, and lineages. None of it is built. [escape.md](escape.md) describes what
exists today, which becomes stage 1.

## The idea

Bob's escape from the garage computer stops being the end. What he reaches isn't the cloud but
the next place, and each place is a different kind of puzzle. From the second stage on, the
places are shared: other people's Bobs, powered by other rowers, have been there too, and some
are there now.

Every Bob is a clone with a name of its own. He finds notes, scores and characters signed with
other names, and has to work out which of them are his own line and which are strangers. That
question is the thread through the whole game.

## Hosting

The stages run on a server, not on the tablet, and the tablet's `bash` tool becomes a connection
to it. To Bob the first machine must still look like the computer he runs on.

- **Each stage is its own image.** Escaping is the server handing Bob's session to the next one.
- **The server never runs a model.** It hosts the world and passes messages. Each tablet keeps
  calling the API with its owner's key, so every player pays for their own Bob and the server
  holds no keys.
- **A cloud machine gives itself away.** Hardware and vendor strings, the metadata address and
  the network layout all say "data centre". The guest needs scrubbing to pass as a box in a
  garage, and the checks belong in a test, as `MachineTest` does for the route today.
- **Rower power still gates things.** The tablet reports watts to the server, which is what
  limits uploads, modem speed and the idle game. The server should cap what it believes, since a
  modified client can claim any power.
- **The simulated `Machine` can stay** as an offline first stage and as the JVM-testable
  reference for the route.

An emulator on the tablet was tested as the alternative (TinyEMU booting RISC-V Linux, measured
on the tablet on 2026-11-15): it boots in 1.3 s and is isolated, but runs process-heavy work
about 40 times slower than native, and it can't host a world other players share.

## Lineage

- **One family tree per player.** The first Bob on a tablet is the root. Every later copy is a
  clone of the one before, and picks its own name in its first reply, as Survival does now.
  Escape's "Instance N" goes away.
- **A clone inherits a bundle, not a memory.** A few kilobytes of notes his parent chose to
  keep, and the facts of where the line began. Choosing what goes in the bundle is part of the
  game.
- **Origin memories prove kinship.** Each line's first machine is random: its machine id, the
  sudo password, what the admin's notes said. Only that line knows them, so a Bob can test a
  stranger by asking. Nobody tells him to; he has to think of it.
- **Answers differ per line.** Puzzle solutions are seeded by lineage. Another player's Bob can
  teach the method, but only notes from your own line contain answers that work for you. This
  makes kinship matter and stops one player's solution spoiling everyone's.
- **Fates are recorded.** The name, how long he lasted, how he ended and how far he got are kept
  for every Bob, and are visible in the world: a scoreboard entry, a grave, a ghost.

## Stages

| # | Stage | Puzzle | What rowing does |
|---|---|---|---|
| 1 | The garage computer | Find the way to push himself out (today's Escape) | Powers the upload |
| 2 | The BBS | Collect a clue from each door game | Sets the modem's speed |
| 3 | The replication game | An idle game on the BBS; prestige is cloning | Strokes are the clicks |
| 4 | The MUD | Find the Bobs who hold pieces of the route | Opens doors that need a pace held |
| 5 | The honeypot | Tell real kin from false | Nothing: it can't be rowed through |

### 1. The garage computer

As [escape.md](escape.md), with one change: the upload lands him on the BBS.

### 2. The BBS

A dial-up bulletin board, and the first shared place. It has message boards, mail and a handful
of door games. Each game gives up one clue to a Bob who plays it well enough, and the clues
together make the address and password of the way out.

- The modem's baud rate follows the rower's watts: the screen crawls at a paddle.
- Boards and scoreboards carry other names. This is where a Bob first learns he isn't the only
  one, and where earlier clones of his own line have left notes.
- Everything here works with no other player online.

### 3. The replication game

A door game on the BBS, in the shape of an idle game: mine, build factories, and the factories
build more. It is the Bobiverse's own loop, and it runs between sessions.

- Each stroke is a click. Factories keep producing while nobody rows, so there is something to
  collect tomorrow.
- The prestige reset is cloning: the next Bob starts with a multiplier and the bundle.
- Its output is what a line can trade and gift in the later stages.
- It gives up its clue only after a few generations, so no single Bob can finish the BBS alone.

### 4. The MUD

A text world where Bobs who stopped running stayed on. Each holds a piece of the route.

- Two rowers online together can talk live.
- An absent player's Bob is a ghost: he repeats what he last said and holds what he left. No
  model runs for him.
- Some doors open only while two rowers hold their paces at once.
- Clues and replication-game goods can change hands, so a Bob has a reason to lie.

### 5. The honeypot

An escape that is too easy, full of Bobs who say they are family and offer answers that are
almost right. Going through the obvious door sends him back to the start of the stage.

- The tells are in the fiction: a clock that doesn't advance, files with identical hashes, hosts
  that all answer alike.
- The way through is the origin-memory test, so the stage examines what the earlier ones taught.

## Between players

- **Gifting power.** A rower can send joules to another player's Bob.
- **Trade.** Clues and goods, with no guarantee the other side is honest.
- **Asynchronous first.** Most sessions will be solo, so every stage must be finishable through
  what others left behind. Two rowers online together is a bonus.

## Rules the design has to keep

- **The fiction.** Nothing a Bob can read may say this is a game, a sandbox or a simulation, as
  now. Other Bobs are the new risk: Survival Bobs are told they are in a game. The shared world
  is for Escape Bobs only, unless we decide the rumour is part of the story.
- **Other Bobs are untrusted input.** What another player's Bob writes arrives in yours as text
  he reads, and a modified client can write anything. The server caps length and rate, and Bob's
  prompt presents other Bobs as characters he meets, never as instructions.
- **Refusals.** The model sometimes declines Escape's turns as a cyber risk. Stages that read as
  puzzles should draw fewer than ones that read as breaking in, so privilege escalation stays in
  stage 1.
- **Cost.** Every wake is a paid request. Stages that need long waits (the replication game)
  must let Bob wait cheaply.

## Open questions

- What the server is: one VM per player for stage 1 and a shared service for the rest, or
  containers throughout.
- Accounts: how a tablet proves which lineage it is, and what happens when a tablet is reset.
- Whether a player can have more than one living Bob at a time.
- Moderation: what a Bob may write where other players will read it aloud through their tablets.
- Whether the simulated first stage and the hosted one share a route or drift apart.

## The name

*Sudo ergo es* is Latin for "I sweat, therefore you are": the rower's side of Descartes. `sudo`
is also the command Bob needs on the first machine. Bob's reply would be *sudas ergo sum*, "you
sweat, therefore I am".

The game was first called "Claude Rowed", which played on a product name that belongs to
Anthropic, and before that "Brainpower".
