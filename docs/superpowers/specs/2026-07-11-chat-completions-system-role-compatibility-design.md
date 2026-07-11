# Chat Completions System Role Compatibility Design

## Problem

Some OpenAI-compatible `/v1/chat/completions` services reject the
`developer` message role and accept only `system`, `assistant`, `user`,
`tool`, and legacy `function` roles. `ChatCaller` currently sends the global
prompt as `system` and the stage prompt as `developer`, causing requests to
fail before inference with `invalid_parameter_error`.

## Design

`ChatCaller` will serialize prompts in this order:

1. global prompt as a `system` message;
2. stage prompt as a second, independent `system` message;
3. task prompt as a `user` message;
4. request history using its existing `user`, `assistant`, and `tool`
   mappings.

Empty or null stage prompts continue to be omitted by the existing text
message helper. Global and stage prompts remain separate so their boundaries
and ordering are preserved without relying on the unsupported `developer`
role.

The synchronous and streaming Chat Completions paths already share the same
request-body builder, so one mapping change covers both paths.

## Protocol Scope

- `/v1/chat/completions`: use only `system`, `user`, `assistant`, and `tool`
  for generated requests.
- `/v1/responses`: retain the native `developer` input item.
- `/v1/messages`: retain the existing top-level system blocks.

No endpoint detection, fallback retry, model-name heuristic, database field,
or frontend setting will be added.

## Error Handling

HTTP error parsing, retries, streaming event handling, tool-call
serialization, and response normalization remain unchanged. The client will
not retry a rejected request with a different role mapping because the Chat
mapping is made compatible before the first request.

## Tests

- Change the Chat caller request-body test to require two ordered `system`
  messages followed by the task `user` message.
- Assert that no Chat request message uses the `developer` role.
- Keep the streaming Chat test to verify it uses the same compatible builder.
- Keep the Responses caller test requiring its `developer` item, proving the
  compatibility change is isolated to Chat Completions.
- Run the complete backend test suite, rebuild the executable JAR, and smoke
  test application startup.

## Scope

This change does not alter prompt contents, prompt resolution, endpoint
selection, LLM configuration persistence, tool schemas, token settings, or
response formats.
