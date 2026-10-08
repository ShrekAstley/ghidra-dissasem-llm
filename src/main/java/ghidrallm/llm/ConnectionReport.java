package ghidrallm.llm;

import java.util.ArrayList;
import java.util.List;

/** Result of "Test Connection": reachable / model available / chat completion works. */
public class ConnectionReport {
	public record Step(String name, boolean ok, String detail) {}

	private final List<Step> steps = new ArrayList<>();
	private List<String> models = List.of();

	public void add(String name, boolean ok, String detail) {
		steps.add(new Step(name, ok, detail));
	}

	public List<Step> steps() {
		return steps;
	}

	public void setModels(List<String> m) {
		models = List.copyOf(m);
	}

	public List<String> models() {
		return models;
	}

	public boolean allOk() {
		return !steps.isEmpty() && steps.stream().allMatch(Step::ok);
	}

	public boolean reachable() {
		return !steps.isEmpty() && steps.get(0).ok();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		for (Step s : steps) {
			sb.append(s.ok() ? "[ OK ] " : "[FAIL] ").append(s.name());
			if (s.detail() != null && !s.detail().isEmpty()) {
				sb.append(" - ").append(s.detail());
			}
			sb.append('\n');
		}
		return sb.toString();
	}
}
