"use client";

import { defineComponent, useIsStreaming } from "@openuidev/react-lang";
import { useEffect, useMemo, useRef, useState, useSyncExternalStore } from "react";
import { getHostActivity, subscribeHostActivity } from "../../host";
import { z } from "zod/v4";
import { useColorScheme } from "./scheme";

// Adapted: separately navigated sandbox.html avoids the parent CSP inherited by srcdoc.
// The page runs in a sandboxed iframe: scripts only, an opaque origin (no
// allow-same-origin, so no access to this app, its cookies or storage), no
// forms, popups or navigation of the app. Inline tool JS works; only local
// data/blob images are allowed.

const CSP = [
  "default-src 'none'",
  "script-src 'unsafe-inline'",
  "style-src 'unsafe-inline'",
  "font-src data:",
  "img-src data: blob:",
  "media-src data: blob:",
  "connect-src 'none'",
  "frame-src 'none'",
  "form-action 'none'",
  "base-uri 'none'",
].join("; ");

const MIN_HEIGHT = 80;
const MAX_HEIGHT = 2400;
const AUTO_START = 360;

// Reports the content height to the parent, so the view fits the page. The
// parent can ask again ("iui-html-view-measure"): a server-rendered frame may
// report before the app is listening.
// Timer wrappers preserve callbacks and remaining delays across this frame's lifecycle.
// They are local to this opaque frame; other WebViews and answer state are unaffected.
const REPORTER = `<script>(function(){
var paused=__IUI_PAUSED__,last=0,next=0,jobs=new Map(),nativeTimeout=window.setTimeout.bind(window),nativeClear=window.clearTimeout.bind(window),nativeRaf=window.requestAnimationFrame.bind(window),nativeCancel=window.cancelAnimationFrame.bind(window);
var realDateNow=Date.now.bind(Date),pauseStart=paused?realDateNow():0,pauseTotal=0;Date.now=function(){return realDateNow()-pauseTotal-(paused?realDateNow()-pauseStart:0)};
function arm(id,job){job.start=performance.now();if(job.raf)job.native=nativeRaf(function(now){jobs.delete(id);job.fn(now)});else job.native=nativeTimeout(function(){if(!job.repeat)jobs.delete(id);job.fn.apply(window,job.args);if(job.repeat&&jobs.has(id)){job.remaining=job.delay;if(!paused)arm(id,job)}},job.remaining)}
function schedule(fn,delay,args,repeat,raf){if(typeof fn!=="function")return 0;var id=++next,job={fn:fn,args:args,repeat:repeat,raf:raf,delay:Math.max(0,Number(delay)||0),remaining:Math.max(0,Number(delay)||0)};jobs.set(id,job);if(!paused)arm(id,job);return id}
function clear(id){var job=jobs.get(id);if(job){(job.raf?nativeCancel:nativeClear)(job.native);jobs.delete(id)}}
window.setTimeout=function(fn,delay){return schedule(fn,delay,Array.prototype.slice.call(arguments,2),false,false)};window.setInterval=function(fn,delay){return schedule(fn,delay,Array.prototype.slice.call(arguments,2),true,false)};window.clearTimeout=window.clearInterval=window.cancelAnimationFrame=clear;window.requestAnimationFrame=function(fn){return schedule(fn,0,[],false,true)};
function suspend(value){if(value===paused)return;if(value)pauseStart=realDateNow();else pauseTotal+=realDateNow()-pauseStart;paused=value;document.documentElement.dataset.paused=String(value);jobs.forEach(function(job,id){if(value){(job.raf?nativeCancel:nativeClear)(job.native);if(!job.raf)job.remaining=Math.max(0,job.remaining-(performance.now()-job.start))}else arm(id,job)})}
function send(){var h=Math.ceil(document.documentElement.getBoundingClientRect().height);if(Math.abs(h-last)>1){last=h;parent.postMessage({type:"iui-html-view-height",height:h},"*")}}
addEventListener("message",function(e){if(e.source!==parent)return;if(e.data==="iui-html-view-measure"){last=0;send()}else if(e.data&&e.data.type==="iui-html-pause")suspend(e.data.paused===true)});addEventListener("load",send);new ResizeObserver(send).observe(document.documentElement);send()
})()</script>`;

function documentFor(html: string, scheme: "light" | "dark") {
  // The policy comes first, so it covers everything in the model's markup.
  return `<!doctype html><meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="${CSP}"><meta name="viewport" content="width=device-width,initial-scale=1"><style>:root{color-scheme:${scheme}}html,body{margin:0}body{font:14px/1.5 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif}</style>${REPORTER}${html}`;
}

export const HtmlView = defineComponent({
  name: "HtmlView",
  props: z.object({
    html: z.string(),
    height: z.number().optional(),
    title: z.string().optional(),
  }),
  description: "Sandboxed HTML page with inline JS, for games and canvas. No network or external scripts; data/blob images only. Fits its content unless height is set.",
  component: function HtmlViewView({ props }) {
    const activity = useSyncExternalStore(subscribeHostActivity, getHostActivity);
    const suspended = activity.paused || !activity.interactive;
    const streaming = useIsStreaming();
    const frame = useRef<HTMLIFrameElement>(null);
    const box = useRef<HTMLDivElement>(null);
    const scheme = useColorScheme(box);
    const fixed = typeof props.height === "number" && props.height > 0 ? Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, props.height)) : null;
    const [measured, setMeasured] = useState<number | null>(null);
    const html = typeof props.html === "string" ? props.html : "";
    const srcDoc = useMemo(() => documentFor(html, scheme), [html, scheme]);
    useEffect(() => { frame.current?.contentWindow?.postMessage({ type: "iui-html-pause", paused: suspended }, "*"); }, [suspended]);

    useEffect(() => {
      if (fixed) return;
      const onMessage = (event: MessageEvent) => {
        if (event.source !== frame.current?.contentWindow) return;
        const data = event.data as { type?: unknown; height?: unknown };
        if (data?.type === "iui-html-view-height" && typeof data.height === "number" && Number.isFinite(data.height)) {
          setMeasured(Math.min(MAX_HEIGHT, Math.max(MIN_HEIGHT, data.height)));
        }
      };
      window.addEventListener("message", onMessage);
      frame.current?.contentWindow?.postMessage("iui-html-view-measure", "*");
      return () => window.removeEventListener("message", onMessage);
    }, [fixed]);

    // A page that is still streaming would reload on every chunk; it runs once complete.
    const height = fixed ?? measured ?? AUTO_START;
    return (
      <div ref={box} className="iui-html-view" style={{ height }}>
        {streaming ? (
          <div className="iui-html-view-wait">Preparing the interactive view…</div>
        ) : (
          <iframe ref={frame} inert={suspended} title={props.title || "Interactive view"} key={srcDoc} sandbox="allow-scripts" src="./sandbox.html" onLoad={() => frame.current?.contentWindow?.postMessage({ type: "iui-html-render", document: srcDoc.replace("__IUI_PAUSED__", String(suspended)) }, "*")} referrerPolicy="no-referrer" loading="lazy" />
        )}
      </div>
    );
  },
});
