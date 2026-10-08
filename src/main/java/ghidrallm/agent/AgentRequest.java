package ghidrallm.agent;

import java.util.Collection;

import ghidrallm.config.Settings;
import ghidrallm.tools.ToolContext;
import ghidrallm.util.CancellationToken;

/**
 * One agent run.
 *
 * @param displayUser  what the user sees in the transcript
 * @param focus        key for session memory (e.g. "FUN_00401000"), may be empty
 * @param userContent  the message actually sent to the model (question + context block)
 * @param contextKeys  keys of context items included in {@code userContent}
 * @param extraSystem  optional extra system text for this run only (e.g. a workflow persona)
 * @param useHistory   false for isolated sub-runs (program analysis batches)
 */
public record AgentRequest(Conversation conversation, String displayUser, String focus, String userContent,
		Collection<String> contextKeys, String extraSystem, boolean useHistory, Settings settings,
		ToolContext toolContext, CancellationToken cancel, AgentListener listener) {}
