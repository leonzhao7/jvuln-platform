You are analyzing a CVE to identify its security patch commits.

## CVE Description
{{cve_description}}

## Affected Component
{{artifact_coordinate}}

## Fixed Version
{{fixed_version}}

## Commits Between prevTag and fixedTag
Each commit is listed as: SHA | commit_message

{{commit_log}}

## Instructions
Review each commit message and identify which commit(s) fixed the CVE vulnerability.
A security patch commit may:
- Contain the CVE ID in its message
- Reference the vulnerability type (e.g. JNDI, injection, RCE)
- Change security-critical code (input validation, access control, deserialization, JNDI lookup)

Return ONLY a JSON object:
{"commits": ["full_sha_of_patch_commit", ...], "reasoning": "brief explanation"}
