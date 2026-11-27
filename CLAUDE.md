@AGENTS.md

## Claude Code

- `.claude/hooks/session-end.sh` stops this folder's preview (`<folder>-preview`) and cleans up anything a killed `verify.sh` (`<folder>-verify`) left behind when a Claude Code session ends.
- In Claude Code cloud sessions, `.claude/hooks/session-start.sh` starts Docker, and makes the base images trust the sandbox's TLS proxy. It also sets `DOCKER_BUILDKIT=0`, which the scripts override: BuildKit uses those local images as long as the Dockerfile has no `# syntax=` line, so don't add one. The scripts then work as above. If Docker isn't running, run the hook again (`CLAUDE_CODE_REMOTE=true CLAUDE_PROJECT_DIR=$PWD .claude/hooks/session-start.sh`).
