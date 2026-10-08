package ghidrallm.ui;

import java.awt.Window;
import java.io.File;
import java.nio.file.Path;

import javax.swing.SwingUtilities;

import docking.ActionContext;
import docking.action.DockingAction;
import docking.action.MenuData;
import ghidra.app.context.ListingActionContext;
import ghidra.app.context.ListingContextAction;
import ghidra.app.plugin.PluginCategoryNames;
import ghidra.app.plugin.ProgramPlugin;
import ghidra.app.services.GoToService;
import ghidra.framework.Application;
import ghidra.framework.plugintool.PluginInfo;
import ghidra.framework.plugintool.PluginTool;
import ghidra.framework.plugintool.util.PluginStatus;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidra.program.util.ProgramLocation;
import ghidra.program.util.ProgramSelection;
import ghidrallm.agent.AssistantService;
import ghidrallm.config.SettingsStore;
import ghidrallm.ghidra.ProgramAccess;
import ghidrallm.ghidra.Resolver;
import ghidrallm.tools.ToolException;

/**
 * Local LLM RE Assistant. Talks only to a locally hosted LM Studio server; all analysis data stays on
 * this machine. The model can inspect the program through a fixed set of read-only tools and can only
 * <em>propose</em> changes, which the user must approve.
 */
//@formatter:off
@PluginInfo(
	status = PluginStatus.RELEASED,
	packageName = LocalLlmPluginPackage.NAME,
	category = PluginCategoryNames.ANALYSIS,
	shortDescription = "Local LLM reverse-engineering assistant",
	description = "Chat with a locally hosted LLM (LM Studio) that inspects the current program through " +
		"controlled Ghidra tools, explains functions with evidence, and proposes names, comments, " +
		"signatures and data types for your approval. Nothing leaves your machine.",
	servicesRequired = { GoToService.class }
)
//@formatter:on
public class LlmAssistantPlugin extends ProgramPlugin implements ProgramAccess, Navigator {

	private AssistantService service;
	private AssistantProvider provider;

	public LlmAssistantPlugin(PluginTool tool) {
		super(tool);
	}

	@Override
	protected void init() {
		super.init();
		Path dir = settingsDir();
		service = new AssistantService(this, new SettingsStore(dir), dir);
		provider = new AssistantProvider(this, getName(), service, this, () -> {
			Window w = tool.getToolFrame();
			return w;
		});
		createActions();
		tool.showComponentProvider(provider, true);
	}

	static Path settingsDir() {
		File base;
		try {
			base = Application.getUserSettingsDirectory();
		}
		catch (RuntimeException e) {
			base = new File(System.getProperty("user.home"), ".ghidra-local-llm");
		}
		return new File(base, "local-llm-re").toPath();
	}

	private void createActions() {
		String owner = getName();
		String[] group = {"Local LLM RE Assistant"};
		addListingAction(owner, "Explain Function", "Explain function", () -> provider.panel().workflowExplain());
		addListingAction(owner, "Analyze Function", "Analyze function", () -> provider.panel().workflowAnalyze());
		addListingAction(owner, "Security Review Function", "Security review", () -> provider.panel().workflowSecurity());
		addListingAction(owner, "Trace Value", "Trace a variable…", () -> provider.panel().workflowTrace());
	}

	private void addListingAction(String owner, String name, String menuText, Runnable r) {
		DockingAction a = new ListingContextAction(name, owner) {
			@Override
			protected void actionPerformed(ListingActionContext context) {
				tool.showComponentProvider(provider, true);
				r.run();
			}

			@Override
			protected boolean isEnabledForContext(ListingActionContext context) {
				return context.getProgram() != null;
			}
		};
		a.setPopupMenuData(new MenuData(new String[] {"Local LLM", menuText}, "LocalLLM"));
		a.markHelpUnnecessary();
		tool.addAction(a);
	}

	// ---- ProgramAccess ------------------------------------------------------------------------

	@Override
	public Program program() {
		Program p = currentProgram;
		return p == null || p.isClosed() ? null : p;
	}

	@Override
	public Address currentAddress() {
		ProgramLocation l = currentLocation;
		return l == null ? null : l.getAddress();
	}

	@Override
	public String selectionDescription() {
		ProgramSelection s = currentSelection;
		if (s == null || s.isEmpty()) {
			return null;
		}
		return s.getMinAddress() + "-" + s.getMaxAddress();
	}

	// ---- Navigator ----------------------------------------------------------------------------

	@Override
	public void goTo(String target) {
		Program p = program();
		GoToService gts = tool.getService(GoToService.class);
		if (p == null || gts == null) {
			return;
		}
		try {
			gts.goTo(Resolver.addressOrSymbol(p, target), p);
		}
		catch (ToolException e) {
			// Not a resolvable location; ignore clicks on unknown tokens.
		}
	}

	// ---- program events -----------------------------------------------------------------------

	@Override
	protected void programActivated(Program program) {
		if (provider != null) {
			SwingUtilities.invokeLater(() -> provider.panel().programChanged(program));
		}
	}

	@Override
	protected void programClosed(Program program) {
		if (service != null) {
			service.proposals().clearAll();
			service.decompiler().invalidate();
		}
		if (provider != null) {
			SwingUtilities.invokeLater(() -> provider.panel().contextChanged());
		}
	}

	@Override
	protected void locationChanged(ProgramLocation loc) {
		if (provider != null) {
			SwingUtilities.invokeLater(() -> provider.panel().contextChanged());
		}
	}

	@Override
	protected void dispose() {
		if (provider != null) {
			provider.panel().dispose();
			tool.removeComponentProvider(provider);
		}
		if (service != null) {
			service.close();
		}
		super.dispose();
	}
}
