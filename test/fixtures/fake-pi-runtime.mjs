// A stand-in for pi-coding-agent's ModelRuntime in tests: free, offline,
// deterministic. Loaded by mcp-servers/pi-llm/server.mjs via PI_LLM_MODULE.
//
// Models: any "fake/<id>", and "openrouter/<anything>". Every reply has a
// thinking block, a short text and a `walk` tool call; the text is JSON
// describing what the runtime was sent, so tests can check the conversion.
// Model "fake/error" fails like a provider error.

const usage = (input) => ({ input, output: 20, cacheRead: 100, cacheWrite: 0, reasoning: 5, totalTokens: input + 120,
                            cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0, total: 0.0021 } });

// A stand-in for pi's extension loader: it pretends the named path is an
// extension registering the "fakeext" provider, so the helper's
// PI_LLM_EXTENSIONS path can be checked offline (see manor.pi-llm-test).
export async function discoverAndLoadExtensions(paths, _cwd) {
  return {
    extensions: paths,
    errors: [],
    runtime: {
      pendingNativeProviderRegistrations: [{ provider: { id: "fakeext" }, extensionPath: paths[0] }],
      pendingProviderRegistrations: [],
    },
  };
}

let calls = 0;

export class ModelRuntime {
  constructor() { this.registered = new Set(); }
  static async create() { return new ModelRuntime(); }

  async setRuntimeApiKey() {}

  registerNativeProvider(provider) { this.registered.add(provider.id); }
  registerProvider(name) { this.registered.add(name); }

  getModel(provider, id) {
    return provider === "fake" || provider === "openrouter" || this.registered.has(provider)
      ? { provider, id, api: "fake-api" } : undefined;
  }

  async completeSimple(model, context, options) {
    calls++;
    const base = { role: "assistant", api: model.api, provider: model.provider, model: model.id, timestamp: Date.now() };
    if (model.id === "error") return { ...base, content: [], usage: usage(0), stopReason: "error", errorMessage: "boom" };
    const seen = {
      model: `${model.provider}/${model.id}`,
      systemPrompt: context.systemPrompt,
      roles: context.messages.map((m) => m.role),
      tools: context.tools.map((t) => t.name),
      toolResultNames: context.messages.filter((m) => m.role === "toolResult").map((m) => m.toolName),
      thinkingSentBack: context.messages.filter((m) => m.role === "assistant")
        .flatMap((m) => m.content.filter((b) => b.type === "thinking").map((b) => b.thinking)),
      reasoning: options?.reasoning ?? null,
      maxTokens: options?.maxTokens ?? null,
      toolChoice: options?.toolChoice ?? null,
      sessionId: options?.sessionId ?? null,
    };
    return {
      ...base,
      content: [{ type: "thinking", thinking: `thinking ${calls}`, thinkingSignature: `sig ${calls}` },
                { type: "text", text: JSON.stringify(seen) },
                { type: "toolCall", id: `call-${calls}`, name: "walk", arguments: { to: "study" } }],
      usage: usage(1000),
      stopReason: "toolUse",
      responseId: `resp-${calls}`,
    };
  }
}
