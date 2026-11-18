import type { InferenceProvider } from "./types";
import { buildApiUrl } from "../../../config/constants";
import { getGroqBase } from "../../../utils/whisperashEngine";
import logger from "../../../utils/logger";

export const groqProvider: InferenceProvider = {
  id: "groq",
  async call({ text, model, agentName, config, ctx }) {
    logger.logReasoning("GROQ_START", { model, agentName });
    const apiKey = await ctx.getApiKey("groq");
    // WhisperAsh: the account gateway in account mode (same API), api.groq.com otherwise.
    const endpoint = buildApiUrl(getGroqBase(), "/chat/completions");
    return ctx.callChatCompletionsApi(endpoint, apiKey, model, text, agentName, config, "Groq");
  },
};
