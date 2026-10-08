package ghidrallm.ui;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.*;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Program;
import ghidrallm.agent.AssistantService;
import ghidrallm.config.Settings;
import ghidrallm.config.SettingsStore;
import ghidrallm.ghidra.ProgramAccess;
import ghidrallm.testutil.MockLmStudio;
import ghidrallm.testutil.TestPrograms;

/**
 * Developer tool (not a unit test): renders the real assistant panel against a mock LM Studio and a
 * real headless Ghidra program, and writes PNG screenshots. Run with: ./gradlew previewPanel
 * (needs a display, e.g. xvfb-run).
 */
public class PanelPreview {

	public static void main(String[] args) throws Exception {
		File outDir = new File(args.length > 0 ? args[0] : "build/preview");
		outDir.mkdirs();
		for (boolean dark : new boolean[] {false, true}) {
			if (dark) {
				FlatDarkLaf.setup();
			}
			else {
				FlatLightLaf.setup();
			}
			render(outDir, dark ? "dark" : "light");
		}
		System.exit(0);
	}

	static void render(File outDir, String theme) throws Exception {
		Path tmp = Files.createTempDirectory("llm-preview");
		try (MockLmStudio server = new MockLmStudio()) {
			Program program = TestPrograms.build(PanelPreview.class);
			Address cursor = program.getAddressFactory().getDefaultAddressSpace().getAddress(0x401004);
			SettingsStore store = new SettingsStore(tmp);
			Settings s = new Settings();
			s.endpoint = server.endpoint();
			s.expertMode = theme.equals("dark");
			store.save(s);
			AssistantService svc = new AssistantService(new ProgramAccess() {
				public Program program() {
					return program;
				}

				public Address currentAddress() {
					return cursor;
				}
			}, store, tmp);
			server.then(MockLmStudio.nativeToolCall("c1", "get_callers", "{\"function\":\"FUN_00401000\"}"))
					.then(MockLmStudio.nativeToolCall("c2", "propose_rename_function",
						"{\"function\":\"FUN_00401000\",\"new_name\":\"load_config_file\",\"reason\":\"Passes the string \\\"config.dat\\\" to FUN_00401040 and stores the result in global 0x403000\",\"confidence\":\"LIKELY\"}"))
					.then(MockLmStudio.nativeToolCall("c3", "propose_function_comment",
						"{\"function\":\"FUN_00401000\",\"comment\":\"Loads config.dat via FUN_00401040 and caches the result in a global.\",\"reason\":\"string + global write\"}"))
					.then(MockLmStudio.chatText("""
							## Summary
							`FUN_00401000` **appears to be a configuration loader**. It passes the path string `"config.dat"` and its first argument to `FUN_00401040`, then stores the returned value in the global at `0x00403000`.

							### Evidence
							- CONFIRMED: references the string "config.dat" (0x00402000)
							- CONFIRMED: called twice from FUN_00401060 with constants 0 and 5
							- LIKELY: the second argument is a flags value (it is forwarded unchanged to FUN_00401040)
							- POSSIBLE: FUN_00401040 is a path-join helper (it returns `path + flags`)
							- UNKNOWN: whether the global is consumed elsewhere

							The decompiler shows no parameters, but the assembly reads `ECX` first, so the signature is probably wrong.

							I queued a rename and a function comment; review them in the Proposals tab.
							""", 2100, 180));
			server.fallback(req -> {
				String u = req.toString();
				if (u.contains("Summarize function")) {
					return MockLmStudio.chatText("SUBSYSTEM: Configuration loading\nSUMMARY: Loads config.dat and caches the result.\nCONFIDENCE: LIKELY");
				}
				return MockLmStudio.chatText("The program loads configuration, then runs its main routine.\n```json\n{\"overview\":\"main -> load config (x2) -> read cached state\",\"subsystems\":[{\"name\":\"Initialization\",\"description\":\"Start-up work\",\"confidence\":\"LIKELY\",\"functions\":[\"FUN_00401060@00401060\"],\"children\":[{\"name\":\"Configuration\",\"description\":\"Loads config.dat into a global\",\"confidence\":\"LIKELY\",\"functions\":[\"FUN_00401000@00401000\",\"FUN_00401040@00401040\"]}]},{\"name\":\"File I/O\",\"description\":\"Path handling\",\"confidence\":\"POSSIBLE\",\"functions\":[\"FUN_00401040@00401040\"]}]}\n```");
			});

			JFrame[] frame = new JFrame[1];
			AssistantPanel[] panel = new AssistantPanel[1];
			SwingUtilities.invokeAndWait(() -> {
				frame[0] = new JFrame("Local LLM RE Assistant (" + theme + ")");
				panel[0] = new AssistantPanel(svc, t -> {}, () -> frame[0]);
				frame[0].setContentPane(panel[0]);
				frame[0].setSize(900, 820);
				frame[0].setVisible(true);
			});
			Thread.sleep(1500);
			shot(frame[0], new File(outDir, theme + "-1-connected-empty.png"));

			SwingUtilities.invokeAndWait(() -> panel[0].send("What does this function do?", "What does this function do? Then propose a better name and a comment.", true));
			long t0 = System.currentTimeMillis();
			while (svc.isBusy() || svc.conversation().isEmpty()) {
				if (System.currentTimeMillis() - t0 > 60000) {
					throw new IllegalStateException("timeout");
				}
				Thread.sleep(200);
			}
			Thread.sleep(800);
			SwingUtilities.invokeAndWait(() -> {
			});
			// go back to chat tab to show transcript
			SwingUtilities.invokeAndWait(() -> selectTab(panel[0], 0));
			Thread.sleep(300);
			shot(frame[0], new File(outDir, theme + "-2-chat.png"));
			SwingUtilities.invokeAndWait(() -> selectTab(panel[0], 1));
			Thread.sleep(300);
			shot(frame[0], new File(outDir, theme + "-3-proposals.png"));

			SwingUtilities.invokeAndWait(() -> selectTab(panel[0], 2));
			SwingUtilities.invokeAndWait(() -> {
				for (Component c : panel[0].getComponents()) {
					// trigger Analyze Program through the popup path
				}
			});
			svc.analyzeProgram((c, t, m) -> {}, r -> {}).get(90, TimeUnit.SECONDS);
			SwingUtilities.invokeAndWait(() -> {
				try {
					var f = AssistantPanel.class.getDeclaredField("architecturePanel");
					f.setAccessible(true);
					((ArchitecturePanel) f.get(panel[0])).showReport(svc.lastReport());
				}
				catch (ReflectiveOperationException e) {
					throw new RuntimeException(e);
				}
			});
			Thread.sleep(300);
			shot(frame[0], new File(outDir, theme + "-4-architecture.png"));
			SwingUtilities.invokeAndWait(() -> selectTab(panel[0], 4));
			Thread.sleep(300);
			shot(frame[0], new File(outDir, theme + "-5-debug.png"));

			// settings dialog
			SwingUtilities.invokeAndWait(() -> {
				SettingsDialog d = new SettingsDialog(frame[0], svc);
				BufferedImage img = new BufferedImage(d.getWidth(), d.getHeight(), BufferedImage.TYPE_INT_RGB);
				d.getContentPane().setSize(d.getSize());
				d.getContentPane().doLayout();
				d.addNotify();
				d.validate();
				Graphics2D g = img.createGraphics();
				d.getContentPane().paint(g);
				g.dispose();
				try {
					ImageIO.write(img, "png", new File(outDir, theme + "-6-settings.png"));
				}
				catch (java.io.IOException e) {
					throw new RuntimeException(e);
				}
				d.dispose();
			});
			Thread.sleep(300);
			SwingUtilities.invokeAndWait(() -> {
			});   // let the settings dialog's windowClosed handler run first
			// disconnected state
			Settings off = svc.settings().copy();
			off.endpoint = "http://127.0.0.1:1/v1";
			svc.applyTransient(off);
			SwingUtilities.invokeAndWait(() -> {
				selectTab(panel[0], 0);
				panel[0].checkConnectionNow();
			});
			Thread.sleep(1500);
			shot(frame[0], new File(outDir, theme + "-7-disconnected.png"));
			SwingUtilities.invokeAndWait(() -> frame[0].dispose());
			svc.close();
			program.release(PanelPreview.class);
		}
	}

	static void selectTab(AssistantPanel p, int idx) {
		for (Component c : p.getComponents()) {
			if (c instanceof JTabbedPane tp) {
				tp.setSelectedIndex(idx);
			}
		}
	}

	static void shot(JFrame f, File out) throws Exception {
		BufferedImage img = new BufferedImage(f.getWidth(), f.getHeight(), BufferedImage.TYPE_INT_RGB);
		SwingUtilities.invokeAndWait(() -> {
			Graphics2D g = img.createGraphics();
			f.getContentPane().paint(g);
			g.dispose();
		});
		ImageIO.write(img, "png", out);
		System.out.println("wrote " + out);
	}
}
