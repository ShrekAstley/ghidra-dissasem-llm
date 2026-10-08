package ghidrallm.ui;

import ghidra.framework.plugintool.util.PluginPackage;
import resources.ResourceManager;

/** Groups the extension in Ghidra's "Configure Tool" dialog. */
public class LocalLlmPluginPackage extends PluginPackage {
	public static final String NAME = "Local LLM RE Assistant";

	public LocalLlmPluginPackage() {
		super(NAME, ResourceManager.loadImage("images/llm_assistant_64.png"),
			"Reverse-engineering assistant backed by a locally hosted LLM (LM Studio). No cloud, no telemetry.",
			FEATURE_PRIORITY);
	}
}
