package io.jes.serializer;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import com.fasterxml.jackson.databind.type.TypeFactory;

import io.jes.ex.SerializationException;
import lombok.SneakyThrows;

import static com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES;
import static com.fasterxml.jackson.databind.SerializationFeature.FAIL_ON_EMPTY_BEANS;

public class JacksonSerializer<S> implements Serializer<S, String> {

    private final ObjectMapper mapper;
    private final TypeReference<S> serializationType = new TypeReference<S>() {};

    @SuppressWarnings("WeakerAccess")
    public JacksonSerializer() {
        this(new ObjectMapper());
    }

    @SuppressWarnings("WeakerAccess")
    public JacksonSerializer(ObjectMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "ObjectMapper must not be null");
        configureMapper(this.mapper);
    }

    private void configureMapper(@Nonnull ObjectMapper mapper) {
        mapper.disable(FAIL_ON_EMPTY_BEANS);
        mapper.disable(FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setDefaultTyping(new ObjectMapper.DefaultTypeResolverBuilder(ObjectMapper.DefaultTyping.NON_FINAL)
                .init(JsonTypeInfo.Id.CUSTOM, new TypeIdWithClassNameFallbackResolver())
                .inclusion(JsonTypeInfo.As.PROPERTY)
                .typeProperty("@type"));
        mapper.setVisibility(mapper.getSerializationConfig().getDefaultVisibilityChecker()
                .withFieldVisibility(JsonAutoDetect.Visibility.ANY)
                .withGetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withSetterVisibility(JsonAutoDetect.Visibility.NONE)
                .withCreatorVisibility(JsonAutoDetect.Visibility.NONE));
    }

    @Override
    public String serialize(S toSerialize) {
        try {
            return mapper.writeValueAsString(toSerialize);
        } catch (Exception e) {
            throw new SerializationException(e);
        }
    }

    @Override
    public S deserialize(String toDeserialize) {
        try {
            return mapper.readValue(toDeserialize, serializationType);
        } catch (IOException e) {
            throw new SerializationException(e);
        }
    }

    private static class TypeIdWithClassNameFallbackResolver extends TypeIdResolverBase {

        private final Map<Class<?>, String> serializationAliases;
        private final Map<String, Class<?>> deserializationAliases;
        private final Map<Class<?>, JavaType> typesCache = new ConcurrentHashMap<>();

        @SuppressWarnings("WeakerAccess")
        public TypeIdWithClassNameFallbackResolver() {
            this(new HashMap<>(), new HashMap<>());
        }

        @SuppressWarnings("WeakerAccess")
        public TypeIdWithClassNameFallbackResolver(@Nonnull Map<Class<?>, String> serializationAliases,
                                                   @Nonnull Map<String, Class<?>> deserializationAliases) {
            this.serializationAliases = Objects.requireNonNull(serializationAliases);
            this.deserializationAliases = Objects.requireNonNull(deserializationAliases);
        }

        @Override
        public String idFromValue(Object value) {
            return idFromValueAndType(value, value != null ? value.getClass() : null);
        }

        @Override
        public String idFromValueAndType(Object value, Class<?> suggestedType) {
            final String alias = serializationAliases.get(suggestedType);
            // if alias not registered, store info about class name
            return alias != null ? alias : suggestedType.getName();
        }

        @Override
        @SneakyThrows
        public JavaType typeFromId(DatabindContext context, String id) {
            Class<?> clazz = deserializationAliases.get(id);
            if (clazz == null) {
                // fallback to resolving type by class name
                clazz = Class.forName(id);
            }
            return typesCache.computeIfAbsent(clazz, key -> TypeFactory.defaultInstance().constructType(key));
        }

        @Override
        public JsonTypeInfo.Id getMechanism() {
            return JsonTypeInfo.Id.CUSTOM;
        }
    }

}
