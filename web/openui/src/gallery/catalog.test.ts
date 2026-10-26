import { describe, expect, it } from "vitest";
import spec from "../generated/spec.json";
import { library } from "../lib/library";
import { programs } from "./programs";
import { toolPrograms } from "./tool-programs";
import { compositionPrograms } from "./composition-programs";
describe("fixed upstream catalog", () => {
 it("registers exactly all55 component names and ordered parameters", () => {
  expect(Object.keys(library.components)).toEqual(Object.keys(spec.components));
  expect(Object.keys(library.components)).toHaveLength(55);
  for (const [name, component] of Object.entries(library.components)) {
   const signature = (spec.components as any)[name].signature as string;
   let position = name.length;
   for (const parameter of Object.keys(component.props.shape)) {
    const match = new RegExp(`\\b${parameter}\\??:`).exec(signature.slice(position));
    const next = match ? position + match.index : -1;
    expect(next, `${name}.${parameter} ordered signature`).toBeGreaterThan(position);
    position = next;
   }
  }
 });
 it("the upstream gallery exercises every catalog component", () => {
  const source = [...programs,...toolPrograms,...compositionPrograms].map(p=>p.code).join("\n");
  for (const name of Object.keys(spec.components)) expect(source, name).toMatch(new RegExp(`\\b${name}\\(`));
 });
});
