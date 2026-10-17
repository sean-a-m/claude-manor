# Turns, stage 1: fewer wasted calls

Instructions for cutting the calls agents waste walking and searching. Read `AGENTS.md` first.
The user agreed this on 2026-10-17, after the analysis of the full-day run
(`runs/claudeworth-20261016-194011`): 45% of agent calls only walked or looked, agents searched
room by room for each other, and about 8% of thoughts counted the per-turn call budget. Stage 2
(simultaneous decide/resolve/react rounds and conversations as exchanges) is not part of this
brief. A researcher is writing `docs/turn-resolution-research.md` for it.

## Status

- [x] T1 last seen: the engine records where each agent last saw each other agent
- [x] T2 `walk` replies with the new place
- [x] T3 `call` across the area (Claudeworth)
- [x] T4 reminders stop counting calls
- [x] T5 docs, mock day and tests
- [ ] T6 one short real run, measured against the full-day run

## Decisions

1. **Last seen (engine, `manor.space`).** When two people part, each remembers where they last
   saw the other: the place, the time, and for the one who stayed, where the other went. They
   part when one of them walks, moves, sets off on a journey or is placed elsewhere by the
   operator. Nothing else is recorded: arriving somewhere doesn't need recording, because the
   people there are shown under `people-here`. The table is kept in the world under `:seen`,
   written only by the movement reducers. The view shows it as `:location :last-seen`, for
   active people who aren't here, most recent first. Every world with locations gets it.
2. **`walk` replies with the new place** (Claudeworth): its name and description, opening hours
   where it has them, who is there and what they're doing or carrying, and the items there. That
   is what the view would show, so agents needn't `look` on arrival. `look` stays for detail.
3. **`call {text, to?}`** (Claudeworth, free): you raise your voice, and everyone in the area
   (every place within walking distance, e.g. the whole house) hears it, wherever they are. It
   shares the one-utterance-a-turn limit with `say` and `whisper`, and is at most 200 characters.
   If it names someone (`to`) who is busy but awake, they're interrupted as by `rouse`: they get
   a turn and decide whether to stop. Someone asleep isn't woken by a call; that needs `rouse` in
   person. Whether calling someone is proper is for the characters to judge from who they are:
   the rules say so; the engine doesn't enforce it.
4. **Reminders stop counting calls.** The early reminder no longer says "each uses one of your
   10 calls (2 used)"; it only says the turn isn't over and names the turn-ending actions. The
   hard limit and the last-call warning stay.

## Measure (T6)

One run in the foreground: `:test? true`, the housemaid in, 10 rounds from 09:00,
`:max-cost 0.20`. Compare it with the full-day run and the containers run (156 calls,
34 `look`, 2 forfeits from searching). Report: calls per agent turn, the share of calls that
only walk or look, `look` calls, `call` uses and whether the person called came, forfeits, and
thoughts that count calls or search for people.
