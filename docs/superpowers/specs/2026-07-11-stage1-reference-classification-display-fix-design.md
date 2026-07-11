# Stage 1 Reference Classification and Display Fix

## Problem

Stage 1 fails for `CVE-2014-125087` with
`INVALID_LLM_RESPONSE: LLM response must be a JSON array`.

The classification request enables the OpenAI-compatible `json_object`
response format while the classifier prompt and parser require a top-level
JSON array. Models that honor `json_object` return a top-level object, which
the classifier rejects before validating any decisions.

NVD reference records also expose a `source` field such as
`cna@vuldb.com`. This is record provenance, not a human-readable link title.
`NvdSource` currently stores it in `Article.title`, and the frontend prefers
that title over the URL. As a result, unrelated reference links all appear
with the same CNA identifier even though their targets are correct.

## Design

### Classification Response

The canonical classifier response will be a JSON object:

```json
{
  "classifications": [
    {
      "referenceId": "REF-0001",
      "category": "analysis",
      "reason": "concise basis",
      "confidence": 0.8
    }
  ]
}
```

The prompt will request this shape so it agrees with `json_object` mode.
The parser will accept the canonical object and the previous top-level array
for compatibility with existing providers and tests. Object responses must
contain exactly one `classifications` array. All existing fail-closed checks
remain in force: every expected stable ID must appear exactly once, unknown
IDs are rejected, categories are enumerated, reasons are required and
bounded, and confidence must be between zero and one.

### NVD Reference Mapping

`NvdSource` will no longer map NVD `reference.source` into `Article.title`.
The article title will be empty because NVD does not provide a display title
for these records. Collection provenance remains `NVD` through
`Article.source` and `Article.discoveredFrom`.

### Frontend Display

Every Stage 1 reference category will render the complete `article.url` as
the link text. `article.title` will not affect the visible label, including
for historical data and references collected from GHSA or OSV. The existing
source badge may continue to show collection provenance such as `NVD` or
`GHSA`.

## Error Handling

Malformed objects, missing or non-array `classifications`, extra wrapper
fields, incomplete decisions, and invalid decision values continue to fail
Stage 1 with `INVALID_LLM_RESPONSE`. The existing partial rule-classified
references remain available in the persisted failure record.

## Tests

- Add a classifier test proving the canonical object response is accepted.
- Retain a direct-array test to prove backward compatibility.
- Add invalid wrapper cases to prove strict fail-closed parsing.
- Add an NVD parsing test proving CNA identifiers are not used as titles and
  collection provenance remains `NVD`.
- Update or add frontend coverage where available; otherwise verify the
  template renders `article.url` in every reference category.
- Run the complete backend test suite and the frontend build or test command.

## Scope

This change does not alter reference URLs, category definitions, source
collection order, evidence fetching, description adjudication, or Stage 1
persistence semantics.
