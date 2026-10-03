# Progress log

Add an entry after each step. Call counts come from the heavy-query call-count test (exact). RPS comes from local
benchmark runs (relative only; note the machine and settings).

| Date | Commit | Step | Subgraph calls / heavy query | Local RPS (feddi / fusion) | Notes |
|---|---|---|---|---|---|
| 2026-10-03 | `912aeb5` | baseline | ~500 (estimated, not yet measured) | — | Published: 19 RPS (.NET, Synthetic), wrong results with Rust subgraphs, at `5ff8b61` |
