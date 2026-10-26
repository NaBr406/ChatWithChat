import { Renderer, type ActionEvent, type OpenUIError } from "@openuidev/react-lang";
import { Component, useCallback, useLayoutEffect, useState, type ReactNode } from "react";
import { createRoot } from "react-dom/client";
import { flushSync } from "react-dom";
import { library } from "./lib/library";
import { MarkdownBlocks } from "./lib/components/markdown";
import { getHostActivity, subscribeHostActivity, hostAction, post, setHostUpdate, setPaused, selection, shouldReset, validUpdate, type HostUpdate } from "./host";
import "./host.css";

class RenderBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
 state = { failed: false };
 static getDerivedStateFromError() { return { failed: true }; }
 render() { return this.state.failed ? <p role="alert">This interactive answer could not be displayed.</p> : this.props.children; }
}
function onAction(event: ActionEvent) {
 const params = event.params as Record<string, unknown> | undefined;
 if (event.type === "CopyToClipboard") hostAction("CopyToClipboard", String(params?.text ?? ""));
 else if (event.type === "open_url") hostAction("OpenUrl", String(params?.url ?? ""));
 else if (event.type === "continue_conversation") {
  const message = event.humanFriendlyMessage || String(params?.message ?? "");
  const context = params?.context;
  const form = event.formState;
  hostAction("ToAssistant", message + (context ? "\n" + JSON.stringify(context) : "") + (form && Object.keys(form).length ? "\n" + JSON.stringify(form) : ""));
 }
}
function Answer({ update }: { update: HostUpdate }) {
 const [errors, setErrors] = useState<OpenUIError[]>([]);
 const onError = useCallback((next: OpenUIError[]) => setErrors(next), []);
 useLayoutEffect(() => {
  let animation = 0;
  let last = -1;
  const measure = () => {
   cancelAnimationFrame(animation);
   animation = requestAnimationFrame(() => {
    const height = Math.max(1, Math.ceil(document.getElementById("root")!.getBoundingClientRect().height));
    if (height !== last) { last = height; post({ type: "height", identity: update.identity, revision: update.revision, height }); }
   });
  };
  const observer = new ResizeObserver(measure);
  observer.observe(document.getElementById("root")!);
  document.addEventListener("load", measure, true);
  document.fonts.ready.then(measure);
  measure();
  return () => { observer.disconnect(); document.removeEventListener("load", measure, true); cancelAnimationFrame(animation); };
 }, [update.identity, update.revision]);
 return <main data-theme={update.theme} aria-busy={update.streaming}>
  {update.format === "markdown" ? <div className="iui-markdown"><MarkdownBlocks text={update.source} /></div> : <Renderer publishObservability={false} response={update.source} library={library} isStreaming={update.streaming} onAction={onAction} onError={onError} />}
  {!update.streaming && errors.length > 0 && <p role="status" className="render-error">Some interactive content could not be displayed.</p>}
 </main>;
}
const root = createRoot(document.getElementById("root")!);
function syncInertControls() {
 const activity = getHostActivity();
 for (const element of document.querySelectorAll<HTMLElement>("button,input,textarea,select,[role=button],a,iframe,canvas,.maplibregl-marker,summary")) element.inert = activity.paused || !activity.interactive;
}
subscribeHostActivity(syncInertControls);
new MutationObserver(syncInertControls).observe(document.body, { childList: true, subtree: true });
let previous: HostUpdate | null = null;
let generation = 0;
window.OpenUiHost = Object.freeze({ update(value: HostUpdate) {
 if (!validUpdate(value)) return;
 if (previous?.identity === value.identity && value.revision < previous.revision) return;
 if (shouldReset(previous, value)) generation++;
 previous = { ...value };
 setHostUpdate(previous);
 document.documentElement.dataset.theme = value.theme;
 document.documentElement.dataset.interactive = String(value.interactive && !value.streaming);
 flushSync(() => root.render(<RenderBoundary key={generation}><Answer update={previous!} /></RenderBoundary>));
}, setPaused });
// All links, including Markdown and map attributions, pass through the native URL policy.
document.addEventListener("click", event => {
 const anchor = (event.target as Element | null)?.closest?.("a[href]");
 if (anchor) { event.preventDefault(); event.stopPropagation(); hostAction("OpenUrl", anchor.getAttribute("href") || ""); }
}, true);
post({ type: "ready" });

// Block keyboard, pointer and synthetic activation from disabled controls at capture phase.
for (const type of ["click", "keydown", "beforeinput", "input", "change", "submit"]) document.addEventListener(type, event => {
 if (document.documentElement.dataset.interactive === "false" || document.documentElement.dataset.paused === "true") {
  const target = event.target as Element | null;
  if (target?.closest?.("button,input,textarea,select,[role=button],a,iframe")) { event.preventDefault(); event.stopImmediatePropagation(); }
 }
}, true);
window.open = ((url?: string | URL) => { if (url) hostAction("OpenUrl", String(url)); return null; }) as typeof window.open;
let selectionActive = false;
document.addEventListener("selectionchange", () => { const active = !!window.getSelection()?.toString(); if (active !== selectionActive) { selectionActive = active; selection(active); } });
