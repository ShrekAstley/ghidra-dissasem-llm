package ghidrallm.tools;

import java.util.EnumSet;
import java.util.Set;

import ghidrallm.config.Settings;
import ghidrallm.tools.impl.*;

/** Builds the registry of every tool the agent can ever call. */
public final class Tools {
	private Tools() {}

	public static ToolRegistry create(Settings s) {
		return create(s.allowProposalTools, s.allowKnowledgeTools);
	}

	/** Registry with READ_PROGRAM always, plus the optional permission classes. */
	public static ToolRegistry create(boolean proposals, boolean knowledge) {
		Set<ToolPermission> granted = EnumSet.of(ToolPermission.READ_PROGRAM);
		if (proposals) {
			granted.add(ToolPermission.PROPOSE_CHANGE);
		}
		if (knowledge) {
			granted.add(ToolPermission.LOCAL_KNOWLEDGE);
		}
		ToolRegistry r = new ToolRegistry(granted);
		FunctionTools.register(r);
		ReferenceTools.register(r);
		DataTools.register(r);
		ProgramTools.register(r);
		PackingTools.register(r);
		TraceTools.register(r);
		ProposalTools.register(r);
		KnowledgeTools.register(r);
		return r;
	}
}
