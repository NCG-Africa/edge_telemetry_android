#!/usr/bin/env bash
# ralphy-lite.sh — a transparent replacement for Ralphy, tuned for this refactor.
#
# Drives Claude Code through REFACTOR-PRD.md tasks one at a time:
#   1. Extracts the next unchecked `- [ ]` task from the PRD
#   2. Invokes Claude Code with the task + config rules, visible output, a timeout
#   3. Auto-commits any file changes with `R0:` prefix
#   4. Checks off the task in the PRD, commits that
#   5. Prints a summary, loops to next task
#
# Usage:
#   ./ralphy-lite.sh                 # run 1 task
#   ./ralphy-lite.sh 5               # run up to 5 tasks
#   ./ralphy-lite.sh 1 --dry-run     # preview next task without executing
#
# Assumptions:
#   - REFACTOR-PRD.md exists in repo root
#   - .ralphy/config.yaml exists (for rules; we extract them and pass inline)
#   - claude CLI is installed and bypass-permissions acceptance is saved
#   - You are on the intended branch (refactor/audit)

set -euo pipefail

# ── Configuration ──────────────────────────────────────────────────────────
PRD_FILE="REFACTOR-PRD.md"
CONFIG_FILE=".ralphy/config.yaml"
TASK_TIMEOUT=600               # 10 minutes per task
MAX_TASKS="${1:-1}"            # default to 1 task if not specified
DRY_RUN=false
[[ "${2:-}" == "--dry-run" ]] && DRY_RUN=true

# ── Colors for output ──────────────────────────────────────────────────────
C_BLUE='\033[0;34m'
C_GREEN='\033[0;32m'
C_YELLOW='\033[0;33m'
C_RED='\033[0;31m'
C_RESET='\033[0m'

log()      { echo -e "${C_BLUE}[INFO]${C_RESET} $*"; }
success()  { echo -e "${C_GREEN}[OK]${C_RESET} $*"; }
warn()     { echo -e "${C_YELLOW}[WARN]${C_RESET} $*"; }
err()      { echo -e "${C_RED}[ERROR]${C_RESET} $*" >&2; }

# ── Sanity checks ──────────────────────────────────────────────────────────
[[ -f "$PRD_FILE" ]]    || { err "Missing $PRD_FILE"; exit 1; }
[[ -f "$CONFIG_FILE" ]] || { err "Missing $CONFIG_FILE"; exit 1; }
command -v claude >/dev/null || { err "claude CLI not found in PATH"; exit 1; }
command -v git    >/dev/null || { err "git not found in PATH"; exit 1; }

if [[ -n "$(git status --porcelain)" ]]; then
    err "Working tree is not clean. Commit or stash changes before running."
    git status --short
    exit 1
fi

# ── Extract rules from .ralphy/config.yaml ─────────────────────────────────
# We just pull the `rules:` section and inline it into the Claude prompt.
extract_rules() {
    awk '
        /^rules:/ { in_rules = 1; next }
        in_rules && /^[a-z_]+:/ { in_rules = 0 }
        in_rules && /^  - "/ {
            sub(/^  - "/, "- ")
            sub(/"$/, "")
            print
        }
    ' "$CONFIG_FILE"
}

# ── Find the next unchecked task ───────────────────────────────────────────
next_task() {
    grep -n '^- \[ \] ' "$PRD_FILE" | head -1
}

# ── Run one task ───────────────────────────────────────────────────────────
run_task() {
    local task_line task_line_num task_text
    task_line=$(next_task)

    if [[ -z "$task_line" ]]; then
        success "No more unchecked tasks in $PRD_FILE. You're done!"
        return 2
    fi

    task_line_num="${task_line%%:*}"
    task_text="${task_line#*:- [ ] }"

    echo
    log "=== Next task (line $task_line_num) ==="
    echo "  $task_text"
    echo

    if $DRY_RUN; then
        warn "Dry run — not executing. Exiting."
        return 2
    fi

    # Build the prompt
    local rules prompt
    rules=$(extract_rules)
    prompt=$(cat <<EOF
You are working on a task from REFACTOR-PRD.md. The task text is:

${task_text}

Rules that MUST be followed (from .ralphy/config.yaml):

${rules}

Do the task. Run any necessary shell commands. Edit files as needed. Do NOT commit — I will handle the commit after reviewing your changes.

When you are finished, just say "DONE" and stop. Do not explain what you did unless I ask.
EOF
)

    log "Invoking Claude Code (timeout: ${TASK_TIMEOUT}s)..."
    echo "─────────────────────────────────────────────────────────────"

    # Run claude with visible output, bounded by timeout
    if ! gtimeout "$TASK_TIMEOUT" claude --dangerously-skip-permissions "$prompt" 2>&1; then
        local exit_code=$?
        echo "─────────────────────────────────────────────────────────────"
        if [[ $exit_code -eq 124 ]]; then
            warn "Claude Code hit the ${TASK_TIMEOUT}s timeout. Checking for partial work..."
        else
            warn "Claude Code exited with code $exit_code. Checking for partial work..."
        fi
    fi

    echo "─────────────────────────────────────────────────────────────"

    # Did anything change?
    if [[ -z "$(git status --porcelain)" ]]; then
        warn "No file changes detected. Task may not have executed correctly."
        warn "Skipping commit + checkoff. Please investigate manually."
        return 1
    fi

    # Show the diff
    log "Changes made:"
    git status --short
    echo

    # Commit the work
    log "Committing work..."
    git add -A
    local commit_msg
    commit_msg=$(echo "R0: $task_text" | cut -c1-72)
    git commit -m "$commit_msg" --quiet
    success "Work committed: $(git log -1 --oneline)"

    # Check off the task in the PRD
    log "Marking task complete in $PRD_FILE..."
    # Use python for safe line replacement (sed escaping is a minefield)
    python3 - "$PRD_FILE" "$task_line_num" <<'PYEOF'
import sys, pathlib
path = pathlib.Path(sys.argv[1])
line_num = int(sys.argv[2])
lines = path.read_text().splitlines(keepends=True)
idx = line_num - 1
if lines[idx].startswith('- [ ] '):
    lines[idx] = '- [x] ' + lines[idx][len('- [ ] '):]
    path.write_text(''.join(lines))
    print(f"Checked off line {line_num}")
else:
    print(f"WARNING: line {line_num} did not start with '- [ ] '; skipping")
PYEOF

    git add "$PRD_FILE"
    git commit -m "R0: mark task complete (line $task_line_num)" --quiet
    success "Checkoff committed: $(git log -1 --oneline)"

    return 0
}

# ── Main loop ──────────────────────────────────────────────────────────────
completed=0
failed=0
start_time=$(date +%s)

for ((i = 1; i <= MAX_TASKS; i++)); do
    echo
    echo "════════════════════════════════════════════════════════════════"
    log "Iteration $i of $MAX_TASKS"
    echo "════════════════════════════════════════════════════════════════"

    set +e
    run_task
    rc=$?
    set -e

    case $rc in
        0) ((completed++)) ;;
        1) ((failed++))
           err "Task failed or produced no changes. Stopping for investigation."
           break ;;
        2) break ;;
    esac
done

elapsed=$(( $(date +%s) - start_time ))

echo
echo "════════════════════════════════════════════════════════════════"
log "Summary"
echo "  Completed: $completed"
echo "  Failed:    $failed"
echo "  Duration:  ${elapsed}s"
echo "════════════════════════════════════════════════════════════════"
