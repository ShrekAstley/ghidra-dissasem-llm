package ghidrallm.ui;

import java.awt.Window;
import java.util.function.Supplier;

import javax.swing.Icon;
import javax.swing.JComponent;

import docking.ComponentProvider;
import docking.WindowPosition;
import ghidra.framework.plugintool.Plugin;
import ghidrallm.agent.AssistantService;
import resources.ResourceManager;

/** Dockable window hosting {@link AssistantPanel}. */
public class AssistantProvider extends ComponentProvider {

	private final AssistantPanel panel;

	public AssistantProvider(Plugin plugin, String owner, AssistantService service, Navigator navigator,
			Supplier<Window> window) {
		super(plugin.getTool(), "Local LLM RE Assistant", owner);
		panel = new AssistantPanel(service, navigator, window);
		Icon icon = ResourceManager.loadImage("images/llm_assistant.png");
		setIcon(icon);
		setTitle("Local LLM RE Assistant");
		setWindowMenuGroup("Local LLM");
		setWindowGroup("Local LLM");
		setDefaultWindowPosition(WindowPosition.RIGHT);
		addToTool();
	}

	public AssistantPanel panel() {
		return panel;
	}

	@Override
	public JComponent getComponent() {
		return panel;
	}
}
