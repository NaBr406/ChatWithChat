import { describe, expect, it } from "vitest";
import { shouldReset, validUpdate, type HostUpdate } from "./host";
const base: HostUpdate = { identity: "a", revision: 1, source: 'root = Col([Text("a")])', streaming: true, interactive: false, theme: "light" };
describe("host generations", () => {
 it("keeps generation across prefix appends and completion", () => { expect(shouldReset(base, { ...base, revision: 2, source: base.source + "\n", streaming: false })).toBe(false); });
 it("resets for rollback, equal-length replacement and new identity", () => { for (const next of [{...base, source: base.source.slice(0,-2)}, {...base, source: base.source.replace('"a"','"b"')}, {...base,identity:"b"}]) expect(shouldReset(base,next)).toBe(true); });
 it("rejects malformed, unbounded and stale-typed updates", () => { expect(validUpdate(base)).toBe(true); for (const next of [null, {}, {...base,source:"x".repeat(1048577)}, {...base,revision:-1}, {...base,revision:1.1}, {...base,streaming:"false"}]) expect(validUpdate(next)).toBe(false); });
});
