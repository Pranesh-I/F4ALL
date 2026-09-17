import { useEffect, useRef, useState } from "react";
import type { PoseSequence } from "../api/types";
import { SKELETON_CONNECTIONS, contentRect, frameAt, landmarks } from "../lib/pose";

/**
 * The recording with the SERVER's landmarks drawn over it.
 *
 * The overlay is what the measurement saw. When a reviewer disagrees with a
 * score, this is how they tell a miscount from a genuine difference — and a
 * skeleton missing for a stretch of the video is itself evidence.
 */
export function VideoWithSkeleton({
  src,
  pose,
  seekToMs,
}: {
  src: string;
  pose: PoseSequence | null;
  seekToMs?: number | null;
}) {
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const [showSkeleton, setShowSkeleton] = useState(true);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const video = videoRef.current;
    if (video && seekToMs !== null && seekToMs !== undefined) {
      video.currentTime = seekToMs / 1000;
      video.pause();
    }
  }, [seekToMs]);

  useEffect(() => {
    const video = videoRef.current;
    const canvas = canvasRef.current;
    if (!video || !canvas) return;

    let handle = 0;
    let stopped = false;

    const draw = () => {
      const context = canvas.getContext("2d");
      if (!context) return;

      const width = video.clientWidth;
      const height = video.clientHeight;
      const ratio = window.devicePixelRatio || 1;

      if (canvas.width !== Math.round(width * ratio) || canvas.height !== Math.round(height * ratio)) {
        canvas.width = Math.round(width * ratio);
        canvas.height = Math.round(height * ratio);
      }

      context.setTransform(ratio, 0, 0, ratio, 0, 0);
      context.clearRect(0, 0, width, height);

      if (!pose || !showSkeleton) return;

      const frame = frameAt(pose, video.currentTime * 1000);
      if (!frame) return;

      const rect = contentRect(width, height, video.videoWidth, video.videoHeight);
      const points = landmarks(frame, pose.landmarks).map((point) => ({
        ...point,
        x: rect.left + point.x * rect.width,
        y: rect.top + point.y * rect.height,
      }));

      context.lineWidth = 3;
      context.strokeStyle = "rgba(34, 211, 238, 0.9)";
      for (const [from, to] of SKELETON_CONNECTIONS) {
        const a = points[from];
        const b = points[to];
        if (!a || !b || !a.visible || !b.visible) continue;
        context.beginPath();
        context.moveTo(a.x, a.y);
        context.lineTo(b.x, b.y);
        context.stroke();
      }

      context.fillStyle = "rgba(250, 204, 21, 0.95)";
      for (const point of points) {
        if (!point.visible) continue;
        context.beginPath();
        context.arc(point.x, point.y, 3.5, 0, Math.PI * 2);
        context.fill();
      }
    };

    const loop = () => {
      if (stopped) return;
      draw();
      handle = requestAnimationFrame(loop);
    };
    loop();

    return () => {
      stopped = true;
      cancelAnimationFrame(handle);
    };
  }, [pose, showSkeleton]);

  if (failed) {
    return (
      <div className="flex aspect-[9/16] max-h-[70vh] items-center justify-center rounded bg-slate-900 p-6 text-center text-sm text-slate-200">
        The recording could not be loaded. Its link may have expired — reload the page.
      </div>
    );
  }

  return (
    <div className="space-y-2">
      <div className="relative mx-auto max-h-[70vh] w-full overflow-hidden rounded bg-black">
        <video
          ref={videoRef}
          src={src}
          controls
          playsInline
          preload="metadata"
          onError={() => setFailed(true)}
          className="mx-auto block max-h-[70vh] w-full object-contain"
        />
        <canvas ref={canvasRef} className="pointer-events-none absolute inset-0 h-full w-full" />
      </div>
      <label className="flex items-center gap-2 text-sm text-slate-700">
        <input
          type="checkbox"
          checked={showSkeleton}
          disabled={!pose}
          onChange={(event) => setShowSkeleton(event.target.checked)}
        />
        {pose ? "Show what the server tracked" : "No server tracking data for this video"}
      </label>
    </div>
  );
}
