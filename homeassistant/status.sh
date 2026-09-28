#!/system/bin/sh
# Optional: prints the dimmer state as one JSON line, for Home Assistant's androidtv.adb_command.
# Copy to the device (e.g. /data/local/tmp/overlay-dimmer-status.sh).
s=$(dumpsys activity service dev.overlaydimmer/.DimService 2>/dev/null | grep -m1 "red=")
red=$(echo "$s" | sed -n 's/.*red=\([0-9]*\).*/\1/p')
bright=$(echo "$s" | sed -n 's/.*bright=\([0-9]*\).*/\1/p')
temp=$(echo "$s" | sed -n 's/.*temp=\([0-9]*\).*/\1/p')
on=$(echo "$s" | grep -c "visible=true")
echo "{\"dimmer_red\":${red:-0},\"dimmer_bright\":${bright:-100},\"dimmer_temp\":${temp:-0},\"dimmer_on\":$([ "$on" -gt 0 ] && echo true || echo false)}"
