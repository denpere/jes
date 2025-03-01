package io.jes.serializer;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nonnull;

import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static java.util.Objects.requireNonNull;

@Slf4j
@NoArgsConstructor
public class TypeRegistry implements SerializationOption {

    private final Map<Class<?>, String> aliases = new ConcurrentHashMap<>();

    /**
     * Register type  with given alias for serialization/deserialization.
     *
     * @param clazz type to register.
     * @param alias alias name to register with given type.
     * @return alias name previosly assosiated with type, or null.
     */
    @SuppressWarnings("UnusedReturnValue")
    public String addAlias(@Nonnull Class<?> clazz, @Nonnull String alias) {
        log.trace("Prepare to add type {} with alias {} into {}", clazz, alias, this.getClass().getName());
        return aliases.putIfAbsent(
                requireNonNull(clazz, "Type class must be provided"),
                requireNonNull(alias, "Alias name must be provided")
        );
    }

    Map<Class<?>, String> getAliases() {
        return new HashMap<>(aliases);
    }

}
