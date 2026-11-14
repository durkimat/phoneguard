// Precompiles the JSON Schema into plain JavaScript (Workers forbid the runtime code generation Ajv normally uses).
import Ajv from "ajv/dist/2020.js";
import addFormats from "ajv-formats";
import standaloneCode from "ajv/dist/standalone/index.js";
import fs from "node:fs";
const schema = JSON.parse(fs.readFileSync(new URL("../schema/profile.schema.json", import.meta.url), "utf8"));
const ajv = new Ajv({ code: { source: true, esm: true }, allErrors: true, strict: false });
addFormats(ajv);
const validate = ajv.compile(schema);
fs.writeFileSync(new URL("../src/profile-validator.js", import.meta.url), standaloneCode(ajv, validate));
console.log("src/profile-validator.js written");
