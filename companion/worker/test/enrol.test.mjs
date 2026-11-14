import assert from "node:assert/strict";
import fs from "node:fs";
import { enrolPayload } from "../src/page/enrol.js";
const v = JSON.parse(fs.readFileSync(new URL("./vectors.json", import.meta.url), "utf8")), e = v.enrolPayload;
const p = await enrolPayload({ host: e.qr.host, token: v.pairing.token, serverKey: v.server.publicKeySpki });
assert.equal(p.qr, e.qr.text); assert.equal(p.address, e.typed.address); assert.equal(p.code, e.typed.code);
console.log("  ok  parent-page enrolment payload matches the shared vectors\n\n1 checks passed");
