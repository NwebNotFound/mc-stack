/**
 * Shared MCP tool response helpers: every answer that leaves hermes-mcp is
 * redacted (value-only) before it is serialised.
 */
import { GatewayError } from "./hermes.js";
import { redactStrings } from "./redact.js";

export function json(payload: unknown): { content: Array<{ type: "text"; text: string }> } {
  return { content: [{ type: "text", text: JSON.stringify(redactStrings(payload), null, 2) }] };
}

export function fail(error: unknown): { isError: true; content: Array<{ type: "text"; text: string }> } {
  if (error instanceof GatewayError) {
    return {
      isError: true,
      content: [
        {
          type: "text",
          text: JSON.stringify(
            redactStrings({ error: error.message, code: error.code, status: error.status }),
            null,
            2,
          ),
        },
      ],
    };
  }
  return {
    isError: true,
    content: [
      {
        type: "text",
        text: JSON.stringify(redactStrings({ error: (error as Error).message ?? "unknown error" }), null, 2),
      },
    ],
  };
}
