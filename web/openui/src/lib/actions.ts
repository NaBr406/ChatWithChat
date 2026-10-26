import { defineAction, type ActionEvent } from "@openuidev/react-lang";
import { z } from "zod/v4";
import { hostAction } from "../host";
export const CopyToClipboard = defineAction({ name: "CopyToClipboard", description: "Copy text to the clipboard, e.g. a shopping list built with @Join", params: z.object({ text: z.string() }) });
export const actions = [CopyToClipboard];
export function runLibraryAction(event: ActionEvent): Promise<boolean> | null {
 if (event.type !== CopyToClipboard.name) return null;
 const text = (event.params as { text?: unknown }).text;
 return Promise.resolve(typeof text === "string" && hostAction("CopyToClipboard", text));
}
