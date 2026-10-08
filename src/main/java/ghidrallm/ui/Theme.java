package ghidrallm.ui;

import java.awt.Color;

import javax.swing.UIManager;

/** Colors derived from the active Look & Feel so the panel follows Ghidra's light/dark themes. */
final class Theme {
	private Theme() {}

	static Color color(String key, Color fallback) {
		Color c = UIManager.getColor(key);
		return c != null ? c : fallback;
	}

	static Color bg() {
		return color("TextPane.background", Color.WHITE);
	}

	static Color fg() {
		return color("TextPane.foreground", Color.BLACK);
	}

	static boolean dark() {
		Color b = bg();
		return (b.getRed() * 299 + b.getGreen() * 587 + b.getBlue() * 114) / 1000 < 128;
	}

	static String hex(Color c) {
		return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
	}

	static Color mix(Color a, Color b, double t) {
		return new Color((int) (a.getRed() * (1 - t) + b.getRed() * t), (int) (a.getGreen() * (1 - t) + b.getGreen() * t),
			(int) (a.getBlue() * (1 - t) + b.getBlue() * t));
	}

	static Color confirmed() {
		return dark() ? new Color(0x4cc38a) : new Color(0x1a7f4b);
	}

	static Color likely() {
		return dark() ? new Color(0x6cb6ff) : new Color(0x0b5cad);
	}

	static Color possible() {
		return dark() ? new Color(0xe3b341) : new Color(0x946200);
	}

	static Color unknown() {
		return dark() ? new Color(0xa0a7b0) : new Color(0x6a737d);
	}

	static Color ok() {
		return dark() ? new Color(0x4cc38a) : new Color(0x1a7f4b);
	}

	static Color bad() {
		return dark() ? new Color(0xff7b72) : new Color(0xc62828);
	}
}
