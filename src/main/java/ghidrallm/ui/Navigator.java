package ghidrallm.ui;

/** Navigates Ghidra's listing to a function name or address. Implemented by the plugin. */
public interface Navigator {
	void goTo(String target);
}
