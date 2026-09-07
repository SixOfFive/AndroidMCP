#!/usr/bin/env bash
# stack.sh - multi-frame stack / super-resolution from a video (or image folder).
#
# Pipeline: extract frames -> (trim start/end) -> pick the SHARPEST frames
# -> align (hugin align_image_stack) -> combine (mean/median/enfuse)
# -> optional AI upscale (Real-ESRGAN) -> sharpen.
#
# Best on a STATIC scene with slight handheld jitter (sub-pixel shifts are what
# enable super-resolution). Moving subjects ghost - use -m median for those.
#
# Usage:
#   ./stack.sh [options] <video-file | image-folder>
# Options:
#   -n N     frames to keep: a number, 'auto' (default), or 'all'
#            auto = keep the sharpest ~120 of what's available (lucky imaging)
#   -t H,T   trim H seconds off the start and T off the end (default 0.5,0.5;
#            use -t 0 to disable). Video only. Kills the shaky start/stop.
#   -m M     combine: mean | median | enfuse | all   (default mean)
#   -u U     upscale result: 0(off) | 2 | 3 | 4 | ai  (default 0)
#            ai = Real-ESRGAN x4 (GPU); anything else = Lanczos
#   -o FILE  output image (default: <input>_stacked.png beside the input)
#   -k       keep temp working folder
#   -x       skip final unsharp-mask sharpening
#   -h       help
#
# Method notes:
#   mean   - best noise reduction (~sqrt(N)); moving objects leave faint ghosts
#   median - rejects moving objects / outliers; a touch less smoothing
#   enfuse - Hugin fusion (good when frames vary in focus / exposure)
#
# Env:
#   RESRGAN_GPU  Vulkan device for AI upscale (default 1=NVIDIA; 0=Intel iGPU).
#
# Max detail tip: this phone films at 1080p (2.1 MP/frame). For the best result,
# feed a FOLDER of full-res 13 MP burst stills instead of a video.
set -euo pipefail

MAXF=auto; TRIM="0.5,0.5"; METHOD=mean; UPSCALE=0; OUT=""; KEEP=0; SHARP=1
AUTO_CAP=120; POOL=300
while getopts ":n:t:m:u:o:kxh" opt; do
  case "$opt" in
    n) MAXF="$OPTARG";;
    t) TRIM="$OPTARG";;
    m) METHOD="$OPTARG";;
    u) UPSCALE="$OPTARG";;
    o) OUT="$OPTARG";;
    k) KEEP=1;;
    x) SHARP=0;;
    h) grep '^#' "$0" | sed 's/^#\{1,\} \{0,1\}//'; exit 0;;
    \?) echo "unknown option -$OPTARG (use -h)" >&2; exit 2;;
    :)  echo "option -$OPTARG needs an argument" >&2; exit 2;;
  esac
done
shift $((OPTIND-1))
[ $# -ge 1 ] || { echo "error: need a video file or image folder (use -h)" >&2; exit 2; }
INPUT="$1"
[ -e "$INPUT" ] || { echo "error: '$INPUT' not found" >&2; exit 2; }
INPUT="$(cd "$(dirname "$INPUT")" && pwd)/$(basename "$INPUT")"

# --- deps ---
MISS=0
need(){ command -v "$1" >/dev/null || { echo "MISSING: $1  ($2)"; MISS=1; }; }
need align_image_stack "sudo apt install hugin-tools"
need convert           "sudo apt install imagemagick"
if [ "$METHOD" = enfuse ] || [ "$METHOD" = all ]; then need enfuse "sudo apt install enfuse"; fi
if [ ! -d "$INPUT" ]; then need ffmpeg "sudo apt install ffmpeg"; need ffprobe "sudo apt install ffmpeg"; fi
[ "$MISS" = 0 ] || { echo "install the missing tool(s) above, then re-run." >&2; exit 3; }

# Real-ESRGAN location (installed under ~/.local/share/realesrgan)
RESRGAN_DIR="$HOME/.local/share/realesrgan"
RESRGAN_BIN="$(command -v realesrgan-ncnn-vulkan || true)"
if [ -z "$RESRGAN_BIN" ] && [ -x "$RESRGAN_DIR/realesrgan-ncnn-vulkan" ]; then RESRGAN_BIN="$RESRGAN_DIR/realesrgan-ncnn-vulkan"; fi
RESRGAN_GPU="${RESRGAN_GPU:-1}"

base="$(basename "$INPUT")"; base="${base%.*}"; pdir="$(dirname "$INPUT")"
[ -n "$OUT" ] || OUT="$pdir/${base}_stacked.png"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/stack.XXXXXX")"; CAND="$WORK/cand"; AL="$WORK/aligned"
mkdir -p "$CAND" "$AL"
cleanup(){ if [ "$KEEP" = 1 ]; then echo "kept working dir: $WORK"; else rm -rf "$WORK"; fi; }
trap cleanup EXIT
echo "==> input: $INPUT"

# --- 1. gather candidate frames ---
if [ -d "$INPUT" ]; then
  echo "==> folder input (stills) - no extraction/trim"
  i=0
  while IFS= read -r f; do
    i=$((i+1)); ln -s "$f" "$(printf '%s/f_%05d.%s' "$CAND" "$i" "${f##*.}")"
  done < <(find "$INPUT" -maxdepth 1 \( -type f -o -type l \) \( -iname '*.png' -o -iname '*.jpg' \
             -o -iname '*.jpeg' -o -iname '*.tif' -o -iname '*.tiff' -o -iname '*.dng' \) | sort)
else
  dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$INPUT" 2>/dev/null || echo 0)
  fr=$(ffprobe -v error -select_streams v:0 -show_entries stream=avg_frame_rate -of csv=p=0 "$INPUT" 2>/dev/null || echo 30/1)
  srcfps=$(awk -F/ '{ if(NF==2 && $2>0) o=$1/$2; else o=$1 } END{ if(o<=0)o=30; printf "%.4f",o }' <<<"$fr")
  # Some phones (Samsung) tag video with invalid 'reserved' color metadata, which
  # makes ffmpeg refuse the filter graph ("Invalid color space"). Detect and fix
  # by rewriting the tags to bt709 via a stream-copy bitstream filter (no re-encode).
  SRCV="$INPUT"
  cs=$(ffprobe -v error -select_streams v:0 -show_entries stream=color_space -of default=nokey=1:noprint_wrappers=1 "$INPUT" 2>/dev/null | head -1 | tr -d ' \r,')
  codec=$(ffprobe -v error -select_streams v:0 -show_entries stream=codec_name -of default=nokey=1:noprint_wrappers=1 "$INPUT" 2>/dev/null | head -1 | tr -d ' \r,')
  case "$cs" in
    reserved|unknown|unspecified|"")
      case "$codec" in h264) BSF=h264_metadata;; hevc|h265) BSF=hevc_metadata;; *) BSF="";; esac
      if [ -n "$BSF" ]; then
        echo "==> fixing invalid color tags ('$cs') via $BSF (stream copy, no re-encode)..."
        if ffmpeg -hide_banner -loglevel error -i "$INPUT" -c copy -bsf:v "$BSF=colour_primaries=1:transfer_characteristics=1:matrix_coefficients=1" -y "$WORK/fixed.mp4" 2>/dev/null; then SRCV="$WORK/fixed.mp4"; else echo "   (re-tag failed; using original)"; fi
      fi;;
  esac
  H=$(awk -F, '{print ($1==""?0:$1)}' <<<"$TRIM"); T=$(awk -F, '{print ($2==""?0:$2)}' <<<"$TRIM")
  seg_start=$(awk -v h="$H" -v d="$dur" -v t="$T" 'BEGIN{ if(d>h+t+0.4) printf "%.3f",h; else printf "0" }')
  seg_dur=$(awk   -v h="$H" -v d="$dur" -v t="$T" 'BEGIN{ if(d>h+t+0.4) printf "%.3f",d-h-t; else printf "%.3f",d }')
  if awk -v s="$seg_start" 'BEGIN{exit !(s>0)}'; then echo "==> trimming ${H}s head / ${T}s tail -> using ${seg_dur}s of ${dur}s"; else echo "==> clip too short to trim; using all ${dur}s"; fi
  cfps=$(awk -v p="$POOL" -v d="$seg_dur" -v s="$srcfps" 'BEGIN{ if(d<=0){printf "%.4f",s; exit} f=p/d; if(f>s)f=s; if(f<=0)f=s; printf "%.4f",f }')
  echo "==> extracting candidate frames (fps=$cfps from ${srcfps}fps source)..."
  ffmpeg -hide_banner -loglevel error -ss "$seg_start" -t "$seg_dur" -i "$SRCV" -vf "fps=$cfps" "$CAND/f_%05d.png"
fi

C=$(find "$CAND" -maxdepth 1 \( -type f -o -type l \) | wc -l)
echo "==> $C candidate frames"
[ "$C" -ge 2 ] || { echo "need at least 2 frames" >&2; exit 4; }

# --- 2. pick sharpest frames (lucky imaging) ---
if [ "$MAXF" = auto ]; then TARGET=$(( C<AUTO_CAP ? C : AUTO_CAP )); elif [ "$MAXF" = all ]; then TARGET=$C; else TARGET=$MAXF; fi
if [ "$TARGET" -lt "$C" ]; then
  echo "==> scoring sharpness, keeping sharpest $TARGET of $C..."
  : > "$WORK/scores"
  for f in "$CAND"/f_*; do
    s=$(convert "$f" -resize 640x -colorspace Gray -morphology Convolve Laplacian:0 -format '%[fx:standard_deviation]' info: 2>/dev/null || echo 0)
    printf '%s\t%s\n' "$s" "$f" >> "$WORK/scores"
  done
  sort -rn "$WORK/scores" | head -n "$TARGET" | cut -f2- > "$WORK/keep.list"
  for f in "$CAND"/f_*; do grep -qxF "$f" "$WORK/keep.list" || rm -f "$f"; done
  echo "==> kept $(find "$CAND" -maxdepth 1 \( -type f -o -type l \) | wc -l) sharpest frames"
else
  echo "==> using all $C frames"
fi

# --- 3. align ---
echo "==> aligning (slow part)..."
if align_image_stack -a "$AL/al_" -C -c 24 -s 2 "$CAND"/f_*.* >/dev/null 2>&1 && ls "$AL"/al_*.tif >/dev/null 2>&1; then
  SRC=("$AL"/al_*.tif); echo "==> aligned ${#SRC[@]} frames"
else
  echo "!! alignment failed (low detail / too much motion) - stacking UNALIGNED"
  SRC=("$CAND"/f_*.*)
fi

# --- 4. combine ---
combine(){ # $1=method $2=out
  case "$1" in
    mean)   convert "${SRC[@]}" -evaluate-sequence mean   "$2";;
    median) convert "${SRC[@]}" -evaluate-sequence median "$2";;
    enfuse) enfuse --output="$2" "${SRC[@]}" >/dev/null 2>&1;;
  esac
  if [ "$SHARP" = 1 ]; then convert "$2" -unsharp 0x1.0+1.0+0.02 "$2"; fi
  echo "   wrote $2"
}
echo "==> combining ($METHOD)..."
case "$METHOD" in
  mean|median|enfuse) combine "$METHOD" "$OUT";;
  all) combine mean "${OUT%.*}_mean.png"; combine median "${OUT%.*}_median.png"
       if command -v enfuse >/dev/null; then combine enfuse "${OUT%.*}_enfuse.png"; fi
       OUT="${OUT%.*}_mean.png";;
  *) echo "unknown method: $METHOD" >&2; exit 2;;
esac

# --- 5. optional upscale ---
if [ "$UPSCALE" != 0 ]; then
  up="${OUT%.*}_up.png"
  if [ "$UPSCALE" = ai ] && [ -n "$RESRGAN_BIN" ]; then
    echo "==> Real-ESRGAN x4 (GPU $RESRGAN_GPU)..."
    margs=(); [ -d "$RESRGAN_DIR/models" ] && margs=(-m "$RESRGAN_DIR/models")
    if "$RESRGAN_BIN" -i "$OUT" -o "$up" -n realesrgan-x4plus -g "$RESRGAN_GPU" "${margs[@]}" >"$WORK/resrgan.log" 2>&1; then
      echo "   wrote $up ($(identify -format '%wx%h' "$up"))"
    else
      echo "   !! AI upscale failed (see $WORK/resrgan.log); using Lanczos x4"; convert "$OUT" -filter Lanczos -resize 400% "$up"; echo "   wrote $up"
    fi
  else
    f="$UPSCALE"; if [ "$f" = ai ]; then f=4; echo "   (Real-ESRGAN not found; Lanczos fallback)"; fi
    echo "==> Lanczos upscale x$f..."; convert "$OUT" -filter Lanczos -resize "$((f*100))%" "$up"; echo "   wrote $up"
  fi
fi

echo "==> DONE. Result: $OUT"
