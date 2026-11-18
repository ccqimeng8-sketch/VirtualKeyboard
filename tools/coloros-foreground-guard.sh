#!/system/bin/sh
# Run from an authorized ADB shell on the tested ColorOS device.
# The guard changes the game gesture gate, which ColorOS uses only in game mode.
base=/data/local/tmp/virtualkeyboard-foreground-guard
key=oplus_games_prevent_all_three_fingers_touch_switch
target=local.virtualkeyboard/local.virtualkeyboard.ControlActivity
exec 9>"$base.lock"
flock -n 9 9>&9 || exit 0

restore() {
    [ -f "$base.restore" ] || return 0
    read -r previous < "$base.restore"
    current=$(settings get secure "$key")
    if [ "$current" = 1 ]; then
        case "$previous" in
            0) settings put secure "$key" 0 || return 1 ;;
            null) settings delete secure "$key" >/dev/null || return 1 ;;
            *) return 1 ;;
        esac
    fi
    rm -f "$base.restore"
}

trap 'restore' EXIT
trap 'exit 0' HUP INT TERM
restore || exit 1
printf '%s\n' "$$" > "$base.pid"

while true; do
    focus=$(dumpsys -t 1 window displays 2>/dev/null | grep -m 1 'mCurrentFocus=')
    case "$focus" in
        *" u0 $target}"*)
            if [ "$(settings get global debug_gamemode_value)" = 1 ]; then
                current=$(settings get secure "$key")
                case "$current" in
                    0|null)
                        if [ ! -f "$base.restore" ]; then
                            printf '%s\n' "$current" > "$base.restore" || exit 1
                        fi
                        settings put secure "$key" 1 || exit 1
                        ;;
                esac
            fi
            ;;
        *) restore || exit 1 ;;
    esac
    sleep 0.25
done
