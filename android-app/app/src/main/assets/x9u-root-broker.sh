#!/system/bin/sh

# X9 Ultra restricted temporary-root broker.
# It intentionally exposes only fixed operations and never a root shell.

HOME_DIR="${X9U_HOME:-/data/user/0/dev.koaan.x9uflasher/files}"
READY="$HOME_DIR/.x9u_root_ready"
REQUEST="$HOME_DIR/.x9u_root_request"
RESPONSE="$HOME_DIR/.x9u_root_response"
OPLOG="$HOME_DIR/.x9u_root_operation.log"
ABL="$HOME_DIR/flash-stage/abl.img"
EFI="$HOME_DIR/flash-stage/installed-mode2.efi"
EMPTY_EFISP="$HOME_DIR/flash-stage/efisp.img"
INSTALL_READY="$HOME_DIR/.x9u_install_ready"
UNINSTALL_READY="$HOME_DIR/.x9u_uninstall_ready"
VERIFY_A="$HOME_DIR/.x9u_verify_abl_a.img"
VERIFY_B="$HOME_DIR/.x9u_verify_abl_b.img"
VERIFY_EFI="$HOME_DIR/.x9u_verify_efisp.img"
VERIFY_EMPTY="$HOME_DIR/.x9u_verify_empty_efisp.img"
ABL_SIZE=278328
EFI_SIZE=1495576
EMPTY_EFISP_SIZE=3145728
IDLE_TICKS=0
MAX_IDLE_TICKS=4500

if [ "$(id -u)" -ne 0 ]; then
  echo "Refusing to start the root broker without uid 0."
  exit 1
fi

stop_legacy_su() {
  for PROC in /proc/[0-9]*; do
    EXE=$(readlink "$PROC/exe" 2>/dev/null || true)
    if [ "$EXE" = "/data/local/tmp/su" ]; then
      kill "${PROC##*/}" 2>/dev/null || true
    fi
  done
}

remove_global_su() {
  stop_legacy_su
  rm -f /data/local/tmp/su \
    /data/local/tmp/temp_su.sock \
    /data/local/tmp/su_daemon.log
}

restore_selinux() {
  if [ -e /sys/fs/selinux/enforce ]; then
    printf '1\n' >/sys/fs/selinux/enforce 2>/dev/null || \
      /system/bin/setenforce 1 2>/dev/null || true
  fi
}

finish() {
  rm -f "$READY" "$REQUEST" "$REQUEST.pending" \
    "$VERIFY_A" "$VERIFY_B" "$VERIFY_EFI" "$VERIFY_EMPTY"
  remove_global_su
  restore_selinux
}
trap finish EXIT INT TERM

respond() {
  TOKEN="$1"
  RC="$2"
  MESSAGE="$3"
  TMP="$RESPONSE.tmp.$$"
  {
    printf '%s\n%s\n%s\n' "$TOKEN" "$RC" "$MESSAGE"
    if [ -f "$OPLOG" ]; then cat "$OPLOG"; fi
  } >"$TMP"
  chmod 0644 "$TMP"
  mv -f "$TMP" "$RESPONSE"
}

rm -f "$REQUEST" "$RESPONSE" "$OPLOG" \
  "$VERIFY_A" "$VERIFY_B" "$VERIFY_EFI" "$VERIFY_EMPTY"
printf '%s\n' "$$" >"$READY.tmp"
chmod 0644 "$READY.tmp"
mv -f "$READY.tmp" "$READY"

# The preload starts its legacy shell-only daemon before this broker. Once the
# app-private broker is live, remove the globally named files it no longer uses.
remove_global_su

while true; do
  if [ ! -f "$REQUEST" ]; then
    sleep 0.2
    IDLE_TICKS=$((IDLE_TICKS + 1))
    if [ "$IDLE_TICKS" -ge "$MAX_IDLE_TICKS" ]; then
      exit 0
    fi
    continue
  fi

  IDLE_TICKS=0
  TOKEN=$(sed -n '1p' "$REQUEST")
  OP=$(sed -n '2p' "$REQUEST")
  rm -f "$REQUEST" "$OPLOG"

  case "$OP" in
    PING)
      {
        id
        grep '^Seccomp:' /proc/self/status 2>/dev/null || true
        test "$(id -u)" -eq 0
      } >"$OPLOG" 2>&1
      RC=$?
      chmod 0644 "$OPLOG" 2>/dev/null
      if [ "$RC" -eq 0 ]; then
        respond "$TOKEN" 0 "Temporary root is available."
      else
        respond "$TOKEN" "$RC" "Root broker is not running as uid 0."
      fi
      ;;

    FLASH)
      (
        set -e
        cleanup_verify() {
          rm -f "$VERIFY_A" "$VERIFY_B" "$VERIFY_EFI"
        }
        trap cleanup_verify EXIT

        test "$(id -u)" -eq 0
        test -f "$ABL"
        test -f "$EFI"
        test "$(wc -c <"$ABL")" -eq "$ABL_SIZE"
        test "$(wc -c <"$EFI")" -eq "$EFI_SIZE"
        test -b /dev/block/by-name/abl_a
        test -b /dev/block/by-name/abl_b
        test -b /dev/block/by-name/efisp

        echo "Writing abl.img to abl_a."
        dd if="$ABL" of=/dev/block/by-name/abl_a bs=1048576 conv=fsync
        echo "Writing abl.img to abl_b."
        dd if="$ABL" of=/dev/block/by-name/abl_b bs=1048576 conv=fsync
        echo "Writing installed-mode2.efi to efisp."
        dd if="$EFI" of=/dev/block/by-name/efisp bs=1048576 conv=fsync
        sync

        echo "Reading all written bytes back for verification."
        dd if=/dev/block/by-name/abl_a of="$VERIFY_A" bs="$ABL_SIZE" count=1
        dd if=/dev/block/by-name/abl_b of="$VERIFY_B" bs="$ABL_SIZE" count=1
        dd if=/dev/block/by-name/efisp of="$VERIFY_EFI" bs="$EFI_SIZE" count=1
        cmp "$ABL" "$VERIFY_A"
        cmp "$ABL" "$VERIFY_B"
        cmp "$EFI" "$VERIFY_EFI"
        rm -f "$UNINSTALL_READY"
        printf 'INSTALL_VERIFY_OK\n' >"$INSTALL_READY.tmp.$$"
        chmod 0644 "$INSTALL_READY.tmp.$$"
        mv -f "$INSTALL_READY.tmp.$$" "$INSTALL_READY"
        echo FLASH_VERIFY_OK
      ) >"$OPLOG" 2>&1
      RC=$?
      chmod 0644 "$OPLOG" 2>/dev/null
      if [ "$RC" -eq 0 ]; then
        respond "$TOKEN" 0 "All three partition writes were read back and verified."
      else
        respond "$TOKEN" "$RC" "A partition write or read-back verification failed."
      fi
      ;;

    UNINSTALL)
      (
        set -e
        cleanup_verify() {
          rm -f "$VERIFY_EMPTY"
        }
        trap cleanup_verify EXIT

        test "$(id -u)" -eq 0
        test -f "$EMPTY_EFISP"
        test "$(wc -c <"$EMPTY_EFISP")" -eq "$EMPTY_EFISP_SIZE"
        test -b /dev/block/by-name/efisp

        echo "Writing the verified empty efisp.img to efisp."
        dd if="$EMPTY_EFISP" of=/dev/block/by-name/efisp bs=1048576 conv=fsync
        sync

        echo "Reading the complete empty image back for verification."
        dd if=/dev/block/by-name/efisp of="$VERIFY_EMPTY" \
          bs="$EMPTY_EFISP_SIZE" count=1
        cmp "$EMPTY_EFISP" "$VERIFY_EMPTY"
        rm -f "$INSTALL_READY"
        printf 'UNINSTALL_VERIFY_OK\n' >"$UNINSTALL_READY.tmp.$$"
        chmod 0644 "$UNINSTALL_READY.tmp.$$"
        mv -f "$UNINSTALL_READY.tmp.$$" "$UNINSTALL_READY"
        echo UNINSTALL_VERIFY_OK
      ) >"$OPLOG" 2>&1
      RC=$?
      chmod 0644 "$OPLOG" 2>/dev/null
      if [ "$RC" -eq 0 ]; then
        respond "$TOKEN" 0 "The empty EFISP image was written and read-back verified."
      else
        respond "$TOKEN" "$RC" "The EFISP erase or read-back verification failed."
      fi
      ;;

    REBOOT_FASTBOOT)
      INSTALL_OK=0
      UNINSTALL_OK=0
      if [ -f "$INSTALL_READY" ] && \
          [ "$(sed -n '1p' "$INSTALL_READY")" = "INSTALL_VERIFY_OK" ]; then
        INSTALL_OK=1
      fi
      if [ -f "$UNINSTALL_READY" ] && \
          [ "$(sed -n '1p' "$UNINSTALL_READY")" = "UNINSTALL_VERIFY_OK" ]; then
        UNINSTALL_OK=1
      fi
      if [ "$INSTALL_OK" -ne 1 ] && [ "$UNINSTALL_OK" -ne 1 ]; then
        respond "$TOKEN" 3 "No verified install or uninstall operation authorizes this reboot."
        continue
      fi
      {
        echo "Requesting reboot to recovery / fastbootd."
        sync
        /system/bin/svc power reboot fastboot
        sleep 5
        /system/bin/setprop sys.powerctl reboot,fastboot
        sleep 5
        /system/bin/reboot fastboot
        sleep 5
        echo "All reboot methods returned and Android is still running."
      } >"$OPLOG" 2>&1
      chmod 0644 "$OPLOG" 2>/dev/null
      respond "$TOKEN" 1 "Could not reboot to recovery / fastbootd."
      ;;

    STOP)
      respond "$TOKEN" 0 "Temporary root service stopped."
      sleep 0.2
      exit 0
      ;;

    *)
      respond "$TOKEN" 2 "Unsupported root operation."
      ;;
  esac
done
