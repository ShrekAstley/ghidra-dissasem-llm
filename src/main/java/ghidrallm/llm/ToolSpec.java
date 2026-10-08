package ghidrallm.llm;

import com.google.gson.JsonObject;

/** Provider-neutral description of a callable tool (JSON-schema parameters). */
public record ToolSpec(String name, String description, JsonObject parametersSchema) {}
