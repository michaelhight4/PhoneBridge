# Bot workflow (how the OpenCode lanes build PhoneBridge)

## Lanes
| Lane | Worktree | Primary model | Fallbacks (same session resumed) | Pool |
| --- | --- | --- | --- | --- |
| ds1 | `C:\Users\seeth\oc_runs\pb_ds1` | `deepseek/deepseek-flash` (paid DeepSeek API) | `opencode-go/deepseek-v4.1-flash` | core |
| ds2 | `C:\Users\seeth\oc_runs\pb_ds2` | `deepseek/deepseek-flash` | `opencode-go/deepseek-v4.1-flash` | core |
| ds3 | `C:\Users\seeth\oc_runs\pb_ds3` | `deepseek/deepseek-flash` | `opencode-go/deepseek-v4.1-flash` | core |
| bigpickle | `C:\Users\seeth\oc_runs\pb_bigpickle` | `opencode/big-pickle` | `opencode/iceman-free`, `opencode-go/deepseek-v4.1-flash` | support |
| iceman | `C:\Users\seeth\oc_runs\pb_iceman` | `opencode/iceman-free` | `opencode-go/iceman-contributor-free`, `opencode-go/deepseek-v4.1-flash` | support |

Queue: `C:\Users\seeth\PhoneBridge_queue\{todo,doing,done,failed,logs,lanes}`.
Task files are named `NN_<task>.<pool>.txt` (`pool` = `core`, `support` or `any`). Core lanes take
`core` then `any`; support lanes take `support` then `any`. If a lane's own pool is empty it may take
the other pool's oldest task once it has sat in `todo` for 30+ minutes.

Each task runs in the lane's worktree on branch `pb/<task>` with the OpenCode session
`ses_pb_<task>` so a different model, or a different lane, can resume the exact same session.

## Rules (every task)
- Never force-push. No AI tags or Co-authored-by trailers. No secrets. No files over 100 MB.
- Commit early and often; **push after every commit** whenever `origin` exists.
- Never touch the Lifeline or Kilumen repos, SOTE_Unity, ProjectSettings; never kill Unity.
- Free-model limits are per IP and must never be bypassed. When a model hits its limit the runner
  resumes the same session on the next model in the lane's list.
- Claim work in `CLAIMS.md` (one line: lane, task, branch, time) and record results in `HANDOFF.md`.
- Merge to `main` only after your module builds and its tests pass:
  1. `git merge main` into your branch, rebuild, rerun tests.
  2. `git -C C:\Users\seeth\PhoneBridge merge --ff-only pb/<task>` (retry step 1 if main moved).
  3. `git -C C:\Users\seeth\PhoneBridge push origin main` if `origin` exists.
  Never edit files directly in `C:\Users\seeth\PhoneBridge`; it is the main checkout.

## Failover rule (2 hours)
A bot that hasn't responded in **2 hours** is assumed to be out of usage.
- The runner watchdog kills a run whose log has not changed for 2 hours and resumes the same session
  on the lane's next model.
- If a lane itself goes silent for 2 hours (its heartbeat in `lanes\<lane>.json` is stale), any other
  lane moves that task from `doing` back to `todo` (pool `any`) so another bot reroutes it, continuing
  the same branch `pb/<task>` and session.
- If the task can't progress (blocked on another task), the bot writes the blocker in HANDOFF.md and
  moves to other unblocked areas. Work stops only if nothing can progress.
