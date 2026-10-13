#!/usr/bin/env bash
set -euo pipefail

script_dir=$(cd "$(dirname "$0")" && pwd)
script_path="$script_dir/$(basename "$0")"
mode=${1:-}
lock_open=0

acquire_lock() {
  local lock_file=$1
  install -d -m 0755 "$(dirname "$lock_file")"
  if command -v flock >/dev/null 2>&1; then
    exec 9>"$lock_file"
    flock -x 9
    lock_open=1
  elif [ "${COVER_TXN_TEST_MODE:-0}" != 1 ]; then
    echo "flock is required outside the local negative control" >&2
    return 69
  fi
}

release_lock() {
  if [ "$lock_open" -eq 1 ]; then
    flock -u 9
    exec 9>&-
    lock_open=0
  fi
}

read_owner() {
  local owner_file=$1
  if [ -f "$owner_file" ]; then
    IFS= read -r owner < "$owner_file"
    printf '%s' "$owner"
  fi
}

write_owner() {
  local owner_file=$1
  local token=$2
  local temporary="${owner_file}.tmp.$$"
  printf '%s\n' "$token" > "$temporary"
  mv -f "$temporary" "$owner_file"
}

rollback_files() {
  local remote_dir=$1
  local backup_dir=$2
  shift 2
  local file
  for file in "$@"; do
    test -f "$backup_dir/$file" || return 1
    install -m 0644 "$backup_dir/$file" "$remote_dir/$file" || return 1
    cmp "$backup_dir/$file" "$remote_dir/$file" || return 1
  done
}

case "$mode" in
  rollback)
    remote_dir=$2
    backup_dir=$3
    lock_file=$4
    owner_file=$5
    token=$6
    shift 6
    acquire_lock "$lock_file"
    current_owner=$(read_owner "$owner_file")
    if [ "$current_owner" = "$token" ]; then
      rollback_files "$remote_dir" "$backup_dir" "$@"
      install -m 0644 /dev/null "$backup_dir/rolled-back"
      rm -f "$owner_file"
    elif [ -f "$backup_dir/rolled-back" ] && [ -z "$current_owner" ]; then
      : # The remote trap or watchdog already completed this rollback.
    else
      echo "refusing rollback: owner=${current_owner:-none}, token=$token" >&2
      release_lock
      exit 73
    fi
    release_lock
    ;;

  watchdog)
    remote_dir=$2
    backup_dir=$3
    confirm_file=$4
    lock_file=$5
    owner_file=$6
    token=$7
    delay_seconds=$8
    shift 8
    sleep "$delay_seconds"
    acquire_lock "$lock_file"
    current_owner=$(read_owner "$owner_file")
    if [ -z "$current_owner" ] && [ -f "$confirm_file" ]; then
      release_lock
      exit 0
    fi
    if [ "$current_owner" != "$token" ]; then
      release_lock
      exit 0
    fi
    if [ -f "$confirm_file" ]; then
      rm -f "$owner_file"
      release_lock
      exit 0
    fi
    echo "No success confirmation for $token; restoring old template covers"
    rollback_files "$remote_dir" "$backup_dir" "$@"
    install -m 0644 /dev/null "$backup_dir/rolled-back"
    rm -f "$owner_file"
    release_lock
    echo "Watchdog rollback completed for $token"
    ;;

  apply)
    remote_dir=$2
    stage_dir=$3
    backup_dir=$4
    confirm_file=$5
    lock_file=$6
    owner_file=$7
    token=$8
    shift 8
    files=("$@")
    test "${#files[@]}" -gt 0
    [[ "$token" =~ ^[0-9]+-[0-9]+$ ]]
    test -d "$remote_dir"
    test -d "$stage_dir"
    install -d -m 0755 "$backup_dir"

    acquire_lock "$lock_file"
    previous_owner=$(read_owner "$owner_file")
    if [ -n "$previous_owner" ] && [ "$previous_owner" != "$token" ]; then
      previous_backup="$(dirname "$backup_dir")/$previous_owner"
      if [ ! -f "$previous_backup/confirmed" ]; then
        echo "Recovering unfinished transaction $previous_owner before $token"
        rollback_files "$remote_dir" "$previous_backup" "${files[@]}"
        install -m 0644 /dev/null "$previous_backup/rolled-back"
      fi
    elif [ "$previous_owner" = "$token" ]; then
      echo "transaction $token is already active" >&2
      release_lock
      exit 75
    fi

    for file in "${files[@]}"; do
      test -f "$remote_dir/$file"
      test -f "$stage_dir/$file"
      cp -p "$remote_dir/$file" "$backup_dir/$file"
    done
    install -m 0644 /dev/null "$backup_dir/transaction-ready"

    rollback_on_failure() {
      local status=$?
      trap - ERR HUP INT TERM
      release_lock
      bash "$script_path" rollback \
        "$remote_dir" "$backup_dir" "$lock_file" "$owner_file" "$token" \
        "${files[@]}" || exit 70
      if [ "$status" -eq 0 ]; then
        status=1
      fi
      exit "$status"
    }
    trap rollback_on_failure ERR HUP INT TERM

    write_owner "$owner_file" "$token"
    release_lock
    watchdog_seconds=${COVER_TXN_WATCHDOG_SECONDS:-720}
    test "$watchdog_seconds" -ge 1
    nohup bash "$script_path" watchdog \
      "$remote_dir" "$backup_dir" "$confirm_file" "$lock_file" \
      "$owner_file" "$token" "$watchdog_seconds" "${files[@]}" \
      </dev/null >"$backup_dir/watchdog.log" 2>&1 &

    installed=0
    fail_after=${COVER_TXN_FAIL_AFTER_FOR_TESTS:-0}
    for file in "${files[@]}"; do
      install -m 0644 "$stage_dir/$file" "$remote_dir/$file"
      installed=$((installed + 1))
      if [ "$fail_after" -gt 0 ] && [ "$installed" -eq "$fail_after" ]; then
        echo "Injected transaction failure after $installed file(s)" >&2
        false
      fi
    done
    trap - ERR HUP INT TERM
    ;;

  confirm)
    confirm_file=$2
    lock_file=$3
    owner_file=$4
    token=$5
    acquire_lock "$lock_file"
    current_owner=$(read_owner "$owner_file")
    if [ -z "$current_owner" ] && [ -f "$confirm_file" ]; then
      release_lock
      exit 0
    fi
    if [ "$current_owner" != "$token" ]; then
      echo "refusing confirmation: owner=${current_owner:-none}, token=$token" >&2
      release_lock
      exit 73
    fi
    install -m 0644 /dev/null "$confirm_file"
    rm -f "$owner_file"
    release_lock
    ;;

  *)
    echo "usage: $0 {apply|rollback|watchdog|confirm} ..." >&2
    exit 64
    ;;
esac
