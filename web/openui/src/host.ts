export type HostUpdate = {
 identity: string; revision: number; source: string; streaming: boolean; interactive: boolean;
 theme: "light" | "dark"; format?: "openui" | "markdown";
};
export type HostAction = "ToAssistant" | "OpenUrl" | "CopyToClipboard";
export type HostMessage = { type: "ready" } | { type: "selection"; identity: string; revision: number; active: boolean } | { type: "height"; identity: string; revision: number; height: number } | { type: "action"; identity: string; revision: number; action: HostAction; value: string };
declare global {
 interface Window { NativeOpenUi?: { postMessage(message: string): void }; OpenUiHost?: { update(update: HostUpdate): void; setPaused(paused: boolean): void } }
}
let current: HostUpdate | null = null;
let paused = false;
let activity = { paused: false, interactive: true };
const activityListeners = new Set<() => void>();
export const getHostActivity = () => activity;
export function subscribeHostActivity(listener: () => void) { activityListeners.add(listener); return () => { activityListeners.delete(listener); }; }
function updateActivity() {
 const interactive = !current || (current.interactive && !current.streaming);
 if (activity.paused === paused && activity.interactive === interactive) return;
 activity = { paused, interactive };
 for (const listener of activityListeners) listener();
}
export function setPaused(value: boolean) { paused = value; document.documentElement.dataset.paused = String(value); updateActivity(); }
export function selection(active: boolean) { if (current) post({ type: "selection", identity: current.identity, revision: current.revision, active }); }
export function post(message: HostMessage) {
 if (window.top !== window.self) return;
 window.NativeOpenUi?.postMessage(JSON.stringify(message));
}
export function setHostUpdate(update: HostUpdate) { current = update; updateActivity(); }
export function hostAction(action: HostAction, value: string): boolean {
 if (paused || !current || !current.interactive || current.streaming || !value || value.length > 32768) return false;
 if (action === "OpenUrl") {
  try { if (!["http:", "https:"].includes(new URL(value).protocol)) return false; } catch { return false; }
 }
 post({ type: "action", identity: current.identity, revision: current.revision, action, value });
 return true;
}
export function validUpdate(value: unknown): value is HostUpdate {
 const u = value as HostUpdate | null;
 return !!u && typeof u.identity === "string" && u.identity.length > 0 && Number.isSafeInteger(u.revision) && u.revision >= 0 && typeof u.source === "string" && u.source.length <= 1048576 && typeof u.streaming === "boolean" && typeof u.interactive === "boolean" && ["light", "dark"].includes(u.theme) && (u.format === undefined || ["openui", "markdown"].includes(u.format));
}
/** Keep React identity across appended chunks, reset for a new generation or rewritten program. */
export function shouldReset(previous: HostUpdate | null, next: HostUpdate): boolean {
 return !previous || previous.identity !== next.identity || previous.format !== next.format || !next.source.startsWith(previous.source);
}
