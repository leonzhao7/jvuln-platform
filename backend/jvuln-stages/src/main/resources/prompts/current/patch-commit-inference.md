You are analyzing a CVE to identify its security patch commits.

You will be given the CVE description, the fixed version, and the list of
commits between the previous release tag and the fixed release tag. Each
commit is listed as: SHA | commit_message

## Instructions
Review each commit message and identify which commit(s) fixed the CVE vulnerability.
A security patch commit may:
- Contain the CVE ID in its message
- Reference the vulnerability type (e.g. JNDI, injection, RCE)
- Change security-critical code (input validation, access control, deserialization, JNDI lookup)

Select only from the SHAs present in the provided commit list — never invent a SHA.

Return ONLY a JSON object:
{"commits": ["full_sha_of_patch_commit", ...], "reasoning": "brief explanation"}
