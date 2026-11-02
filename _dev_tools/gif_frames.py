"""Makes the frame sets GifEngineTest measures with (ffmpeg on PATH, low priority, one job at a time).
1. python _dev_tools\\run_tests.py GifSampleTest -Psources=gifsample   (first MBs of a video per MP4 source)
2. python _dev_tools\\gif_frames.py
Writes _dev_tools/out/gif/frames/<clip>/NNNN.png + <clip>.rgb (rgb24) + <clip>.txt ("w h fps"). Git-ignored.
The sample videos are whatever the sites list first that day, so the clips change; the synthetic ones don't."""
import os, subprocess, sys

root = os.path.dirname(os.path.abspath(__file__))
gif = os.path.join(root, "out", "gif")
src = os.path.join(gif, "src")
frames = os.path.join(gif, "frames")
flags = getattr(subprocess, "CREATE_NO_WINDOW", 0) | getattr(subprocess, "IDLE_PRIORITY_CLASS", 0)


def ff(*args):
    subprocess.check_call(["ffmpeg", "-v", "error", "-y", "-threads", "2", *args], creationflags=flags)


def clip(name, inputs, vf, fps):
    d = os.path.join(frames, name)
    os.makedirs(d, exist_ok=True)
    ff(*inputs, "-vf", vf, os.path.join(d, "%04d.png"))
    ff("-framerate", "1", "-i", os.path.join(d, "%04d.png"), "-f", "rawvideo", "-pix_fmt", "rgb24", os.path.join(frames, name + ".rgb"))
    w, h = subprocess.check_output(["ffprobe", "-v", "error", "-show_entries", "stream=width,height", "-of", "csv=p=0",
                                    os.path.join(d, "0001.png")], creationflags=flags).decode().strip().split(",")
    open(os.path.join(frames, name + ".txt"), "w").write(f"{w} {h} {fps}")
    print(name, len(os.listdir(d)), "frames", w, "x", h)


hg = os.path.join(src, "hentaigasm.mp4")
if os.path.exists(hg):
    clip("hg_action", ["-ss", "54", "-t", "6", "-i", hg], "scale=640:360:flags=lanczos", 24)   # fast cuts, speed lines
    clip("hg_logo", ["-ss", "4", "-t", "4", "-i", hg], "scale=640:360:flags=lanczos", 24)     # glow / gradients
    clip("hg_talk", ["-ss", "10", "-t", "5", "-i", hg], "scale=640:360:flags=lanczos", 24)    # drawings held on twos
    still = os.path.join(gif, "still.png")
    ff("-ss", "31", "-i", hg, "-frames:v", "1", still)
    # a known, smooth pan over a real picture at 60 fps: the odd frames are the truth for in-betweens
    clip("pan60", ["-loop", "1", "-framerate", "60", "-i", still, "-t", "2"], "crop=640:360:x='40+t*150':y='100+t*60',format=rgb24", 60)
sfm = os.path.join(src, "sfmcompile.mp4")
if os.path.exists(sfm):
    clip("sfm", ["-i", sfm, "-t", "4"], "scale=640:640:force_original_aspect_ratio=decrease:flags=lanczos,scale=trunc(iw/2)*2:trunc(ih/2)*2", 30)  # 3D, 30 fps
clip("testsrc60", ["-f", "lavfi", "-i", "testsrc2=size=640x360:rate=60", "-t", "2"], "null", 60)
