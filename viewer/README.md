# manor transcript viewer

A small, read-only web viewer for manor run directories — the ones under
`old-test-runs/`, `runs/` and `test-runs/`.

`llm.edn` is one printed EDN map per model call and grows to a megabyte or
more, which makes it (and the prompts inside it) awkward in a text editor.
This puts it in a browser: browse the calls of a run, filter them, and read one
call's full prompt, its reasoning, its tool calls and the engine's replies.

It is a separate utility: it lives in `viewer/`, runs on
[babashka](https://babashka.org) (`bb`), needs no build step or extra jars, and
never writes to a run.

## Run it

```sh
bin/manor-viewer                                   # serve ./old-test-runs (else ./runs)
bin/manor-viewer old-test-runs/phase2-real-1       # serve one run
bin/manor-viewer --root runs --port 8899           # another root or port
bin/manor-viewer --no-narrate                      # plain event types, no replay
```

Only `bb` has to be installed. The launcher puts the repo's own `src/` on the
classpath so event pages can reuse `manor.narrate`; if the events predate the
current code, replay fails harmlessly and the events page falls back to plain
event types. Nothing is ever written, and every request is confined to the run
root (paths that escape it are refused).

Tests (fixture run in a temp dir, offline):

```sh
bb --config viewer/bb.edn test      # from the repo root
cd viewer && bb test                # or from here
```

## The pages

| Page | What it is for |
|---|---|
| `/` | every run in the root: rounds, calls, cost, error/rejection counts; a search box that spans all runs |
| `/run?dir=…` | one run: per-agent cost and tokens, tools called, finish reasons, per-round cost, files |
| `/calls?dir=…` | the call log — filter by agent, round, flag or text; each row links to the call |
| `/call?dir=…&n=…` | one call: the model's reasoning, tool calls with arguments and the engine's replies, and the whole prompt it answered |
| `/reasoning?dir=…` | a run's captured reasoning read straight through (filter by agent, round or text) |
| `/events?dir=…` | the event log by round, with visibility and (optionally) raw event data |
| `/search?dir=…` | full-text search of a run's calls |
| `/file?dir=…&name=transcript.md` | the run's `transcript.md`, rendered |
| `/raw?dir=…&n=…`, `/raw-file?…` | plain-text EDN, for copying out |

Filters are query parameters, so a view can be linked to or bookmarked.

Things worth knowing for review:

- **Reasoning** is the model's private thoughts, captured out of band into
  `llm.edn`'s `:thoughts` (the wire `reasoning` field; never sent back to the
  model). `/reasoning` reads a run's deliberations in order, and a call page
  shows its own thread above the tool calls (`#reasoning`).
  Not every run has them: a run made before capture, or one whose model
  returned none, records no `:thoughts` at all, and the pages say so rather
  than showing nothing. A call can also be billed `:thought-tokens` with no
  text recorded — that shows as a red chip (`12 tok, no text`).
- **Flags** on `/calls`: `errors`, `rejections`, `notool` (the model returned no
  tool call), `length` (truncated by `:max-tokens`), `forfeit` (the turn was
  forfeited), `thoughts`.
- **`in=`** on `/search` narrows the field: `thoughts`, `replies`, `tools`, or
  `all` (which also searches prompts — much noisier).
- A call's prompts are shown as the model saw them, per message; the last
  message is the one the call answered. Views of the world arrive as embedded
  JSON and are rendered pretty-printed inside a collapsed `<details>`.
- Empty columns are worth reading too: a call with no tool call and a
  `finish: length` chip is the reasoning-token problem showing up.

## Layout

```
bin/manor-viewer        launcher (bb, no state kept)
viewer/bb.edn           bb paths and tasks (serve, test)
viewer/src/manor/viewer/
  data.clj              read runs (config/events/llm), totals, search, narration hook
  render.clj            pages, markdown-ish rendering, layout
  html.clj              tiny hiccup-style renderer that escapes text content
  web.clj               routes, query strings, ring-style handler and http-kit server
  main.clj              command line
viewer/test/            tests (fixture run; no network, no old-test-runs)
```

`data.clj` reads a run the same way `manor.store/read-lines` writes it (one EDN
form per line), but tolerantly: a line that will not read is counted and
skipped (`:errors`), so a run being written can still be looked at. Runs are
cached in memory keyed on file mtimes, so a live run refreshes.

`html.clj` exists because babashka's bundled hiccup does not escape string
content (it would emit model output as live markup); this renderer escapes
everything except the stylesheet.

## Not in scope

No editing, no re-running, no calling models, no state of its own. It shows what
is already on disk.
