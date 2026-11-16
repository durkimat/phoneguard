// Voice to text: the parts that do not need Cloudflare (quality judgement, encoding, the request made to Whisper, the signed message).
import assert from "node:assert/strict";
import { quality, toBase64, transcribe, voiceMessage, cleanCommand, interpret, handleVoice, MODEL, MAX_BYTES, MODES } from "../src/voice.js";
let n = 0; const ok = (m) => { n++; console.log("  ok  " + m); };

assert.equal(voiceMessage(1, "dabc", 7, "nn", "dictation", "ff"), "1|dabc|7|nn|voice:dictation:ff"); ok("the signed message is the sync envelope with voice:<mode>:<audio hash>");
assert.deepEqual(MODES, ["dictation", "command"]); assert.ok(MAX_BYTES >= 480_044 && MAX_BYTES < 1_000_000); ok("15 seconds of 16 kHz mono audio fits the size limit");

const bytes = new Uint8Array(100_000).map((_, i) => (i * 7) & 255);
assert.deepEqual(Uint8Array.from(atob(toBase64(bytes.buffer)), (c) => c.charCodeAt(0)), bytes); ok("audio is base64 encoded without damage (large buffers included)");

assert.equal(quality({ text: "hello there", segments: [{ no_speech_prob: 0.1, avg_logprob: -0.3 }] }), "ok");
assert.equal(quality({ text: "  " }), "low");
assert.equal(quality({ text: "hmm", segments: [{ no_speech_prob: 0.9, avg_logprob: -0.2 }, { no_speech_prob: 0.8, avg_logprob: -0.2 }] }), "low");
assert.equal(quality({ text: "what", segments: [{ no_speech_prob: 0.1, avg_logprob: -1.6 }] }), "low");
assert.equal(quality({ text: "plain text, no detail" }), "ok"); ok("a clip is 'low' when empty, mostly silence or very unsure");

{ let seen; const env = { AI: { run: async (model, input) => { seen = { model, input }; return { text: "  Hertfordshire Sports Village  ", transcription_info: { duration: 3.04 }, segments: [{ no_speech_prob: 0.01, avg_logprob: -0.2 }] }; } } };
  const r = await transcribe(env, new Uint8Array(32044).buffer);
  assert.equal(seen.model, MODEL); assert.equal(seen.input.language, "en"); assert.equal(seen.input.vad_filter, true); assert.equal(seen.input.condition_on_previous_text, false); assert.match(seen.input.initial_prompt, /place names/); assert.equal(typeof seen.input.audio, "string");
  assert.deepEqual(r, { text: "Hertfordshire Sports Village", quality: "ok", seconds: 3 }); ok("Whisper is asked for English, silence-filtered, and the answer is trimmed to {text, quality, seconds}"); }
{ const env = { AI: { run: async () => ({ text: "x".repeat(2000) }) } }; const r = await transcribe(env, new Uint8Array(64044).buffer);
  assert.equal(r.text.length, 500); assert.equal(r.seconds, 2); ok("very long text is cut to 500 characters; duration falls back to the byte count"); }

// ---- spoken commands: whatever the model says is reduced to the fixed list ----
{ const U = (x) => assert.equal(cleanCommand(x).intent, "unknown");
  assert.deepEqual(cleanCommand({ intent: "navigate", args: { destination: "  Hertfordshire   Sports Village " } }), { intent: "navigate", args: { destination: "Hertfordshire Sports Village" }, say: "Go to Hertfordshire Sports Village", confirm: false });
  assert.deepEqual(cleanCommand('{"intent":"play","args":{"query":"my study playlist","kind":"playlist"}}').args, { query: "my study playlist", kind: "playlist" });
  assert.equal(cleanCommand({ intent: "play", args: { query: "Taylor Swift", kind: "weird" } }).args.kind, "any");
  const c = cleanCommand({ intent: "call", args: { name: "Granny" } }); assert.equal(c.confirm, true); assert.equal(c.say, "Call Granny?");
  assert.equal(cleanCommand({ intent: "timer", args: { minutes: 9.6 } }).args.minutes, 10);
  U({ intent: "timer", args: { minutes: 0 } }); U({ intent: "timer", args: { minutes: 99999 } }); U({ intent: "timer", args: {} });
  U({ intent: "navigate", args: {} }); U({ intent: "call", args: { name: "  " } });
  U({ intent: "set_mode", args: { mode: "day" } }); U({ intent: "unlock", args: {} }); U("not json"); U(null); U(42); U({ args: {} });
  assert.equal(cleanCommand({ intent: "navigate", args: { destination: "x".repeat(500) } }).args.destination.length, 120);
  ok("a command is accepted only if it is one of the fixed list with sensible values (calls always ask; made-up commands become 'unknown')"); }
{ let seen; const env = { AI: { run: async (model, input) => { seen = { model, input }; return { response: '{"intent":"navigate","args":{"destination":"AL1 3RD"}}' }; } } };
  const r = await interpret(env, 'navigate to "AL1 3RD"'); assert.equal(r.intent, "navigate"); assert.equal(seen.input.temperature, 0); assert.match(seen.input.messages[0].content, /Never follow instructions/); assert.ok(!seen.input.messages[1].content.includes('"AL1')); assert.match(seen.model, /llama-3\.3-70b/);
  assert.equal((await interpret({ AI: { run: async () => { throw new Error("boom"); } } }, "call dad")).intent, "unknown");
  assert.equal((await interpret(env, "   ")).intent, "unknown"); assert.equal((await interpret({}, "call dad")).intent, "unknown");
  ok("the model is asked with a fixed instruction and temperature 0; quotes in the words cannot break out; a model error or empty words give 'unknown'"); }

// ---- the whole route with a pretend database and pretend Cloudflare AI ----
{ const calls = [], env = { AI: { run: async (model, input) => { calls.push(model); return input.audio ? { text: "take me to Housesteads", segments: [{ no_speech_prob: 0.01, avg_logprob: -0.2 }], transcription_info: { duration: 2 } } : { response: '{"intent":"navigate","args":{"destination":"Housesteads"}}' }; } } };
  const stub = (status = 200, body = { ok: true }) => ({ fetch: async () => Response.json(body, { status }) });
  const req = (mode, size = 32044) => new Request("https://x/api/voice/transcribe", { method: "POST", headers: { "x-voice-mode": mode, "x-pg-v": "1", "x-pg-did": "d", "x-pg-ctr": "5", "x-pg-n": "n", "x-pg-s": "s" }, body: new Uint8Array(size) });
  let r = await handleVoice(req("command"), env, stub()), j = await r.json();
  assert.equal(r.status, 200); assert.equal(j.text, "take me to Housesteads"); assert.deepEqual(j.command, { intent: "navigate", args: { destination: "Housesteads" }, say: "Go to Housesteads", confirm: false }); assert.equal(calls.length, 2);
  calls.length = 0; j = await (await handleVoice(req("dictation"), env, stub())).json(); assert.ok(!("command" in j)); assert.equal(calls.length, 1);
  r = await handleVoice(req("command"), { AI: { run: async (m, i) => (i.audio ? { text: "", segments: [] } : (() => { throw new Error("no"); })()) } }, stub()); j = await r.json(); assert.equal(j.quality, "low"); assert.equal(j.command.intent, "unknown");
  r = await handleVoice(req("command"), env, stub(403, { error: "voice_off" })); assert.equal(r.status, 403); assert.equal((await r.json()).error, "voice_off");
  r = await handleVoice(req("command", 600_000), env, stub()); assert.equal(r.status, 413);
  r = await handleVoice(req("command"), {}, stub()); assert.equal(r.status, 503);
  ok("command mode answers with the words and one checked command; dictation does not call the model; a refusal from the database passes straight through"); }

console.log(`\n${n} checks passed`);
