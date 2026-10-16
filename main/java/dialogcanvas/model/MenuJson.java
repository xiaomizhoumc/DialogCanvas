package dialogcanvas.model;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** Shared JSON policy for model documents; Jackson 3 otherwise ignores unknown fields. */
public final class MenuJson {
    private MenuJson() {
    }

    public static JsonMapper createMapper() {
        return JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(64).maxNumberLength(256).maxStringLength(1024 * 1024).build()).build())
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
                .build();
    }
}
