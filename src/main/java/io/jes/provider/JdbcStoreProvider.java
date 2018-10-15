package io.jes.provider;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collection;
import java.util.Objects;
import java.util.Spliterators.AbstractSpliterator;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import javax.annotation.Nonnull;
import javax.sql.DataSource;

import io.jes.Event;
import io.jes.ex.BrokenStoreException;
import io.jes.ex.VersionMismatchException;
import io.jes.provider.jdbc.DDLFactory;
import io.jes.provider.jdbc.DDLProducer;
import io.jes.serializer.EventSerializer;
import io.jes.serializer.SerializerFactory;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import static java.lang.Long.MAX_VALUE;
import static java.util.Objects.requireNonNull;
import static java.util.Spliterator.ORDERED;

@Slf4j
public class JdbcStoreProvider<T> implements StoreProvider {

    private final DataSource dataSource;
    private final DDLProducer ddlProducer;
    private final EventSerializer<T> serializer;

    @SuppressWarnings("WeakerAccess")
    public JdbcStoreProvider(@Nonnull DataSource dataSource, @Nonnull Class<T> serializationType) {
        try {
            this.dataSource = requireNonNull(dataSource);
            this.serializer = SerializerFactory.newEventSerializer(serializationType);

            try (final Connection connection = dataSource.getConnection()) {

                final String schema = Objects.requireNonNull(connection.getSchema(), "Schema must not be null");
                final DatabaseMetaData metaData = connection.getMetaData();
                final String databaseName = metaData.getDatabaseProductName();
                this.ddlProducer = DDLFactory.newDataSourceSyntax(requireNonNull(databaseName), schema);
                createEventStore(connection, ddlProducer.createStore(serializationType));
            }
        } catch (Exception e) {
            throw new BrokenStoreException(e);
        }
    }

    @SneakyThrows
    private void createEventStore(@Nonnull Connection connection, @Nonnull String ddl) {
        try (PreparedStatement statement = connection.prepareStatement(ddl)) {
            final int code = statement.executeUpdate();
            if (code == 0) {
                log.info("JEventStore successfully created");
            }
        }
    }

    @Override
    public Stream<Event> readFrom(long offset) {
        return readBy(offset, ddlProducer.queryEvents());
    }

    @Override
    public Collection<Event> readBy(@Nonnull UUID uuid) {
        return readBy(uuid, ddlProducer.queryEventsByStream()).collect(Collectors.toList());
    }

    @SuppressWarnings("squid:S2095")
    private Stream<Event> readBy(@Nonnull Object value, @Nonnull String from) {
        try {
            final Connection connection = dataSource.getConnection();
            final PreparedStatement statement = connection.prepareStatement(from);

            statement.setObject(1, value);
            final ResultSet set = statement.executeQuery();

            return resultSetToStream(connection, statement, set);
        } catch (Exception e) {
            throw new BrokenStoreException(e);
        }
    }

    private Stream<Event> resultSetToStream(Connection connection, Statement statement, ResultSet set) {
        return StreamSupport.stream(new AbstractSpliterator<Event>(MAX_VALUE, ORDERED) {

            @Override
            public boolean tryAdvance(Consumer<? super Event> action) {
                try {
                    if (!set.next()) {
                        return false;
                    }
                    //noinspection unchecked
                    T data = (T) set.getObject(ddlProducer.eventContentName());
                    action.accept(serializer.deserialize(data));
                } catch (Exception e) {
                    throw new BrokenStoreException(e);
                }
                return true;
            }
        }, false).onClose(() -> closeQuietly(set, statement, connection));
    }

    @Override
    public void write(@Nonnull Event event) {
        writeTo(event, ddlProducer.insertEvents());
    }

    private void writeTo(Event event, String where) {
        try (final Connection connection = dataSource.getConnection();
             final PreparedStatement statement = connection.prepareStatement(where)) {

            final UUID uuid = event.uuid();
            verifyStreamVersion(event, connection);

            final T data = serializer.serialize(event);

            statement.setObject(1, uuid);
            statement.setObject(2, data);

            statement.executeUpdate();
        } catch (BrokenStoreException | VersionMismatchException e) {
            throw e;
        } catch (Exception e) {
            throw new BrokenStoreException(e);
        }
    }

    @SneakyThrows
    private void verifyStreamVersion(Event event, Connection connection) {
        final UUID uuid = event.uuid();
        final long expectedVersion = event.expectedStreamVersion();
        if (uuid != null && expectedVersion != -1) {
            try (PreparedStatement statement = connection.prepareStatement(ddlProducer.queryEventsStreamVersion())) {
                statement.setObject(1, uuid);
                try (final ResultSet query = statement.executeQuery()) {
                    if (!query.next()) {
                        throw new BrokenStoreException("Can't read uuid [" + uuid + "] version");
                    }
                    final long actualVersion = query.getLong(1);
                    if (expectedVersion != actualVersion) {
                        throw new VersionMismatchException(expectedVersion, actualVersion);
                    }
                }
            }
        }
    }

    @Override
    public void deleteBy(@Nonnull UUID uuid) {
        log.warn("Prepare to remove {} event stream", uuid);
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(ddlProducer.deleteEvents())) {
            statement.setObject(1, uuid);
            final int affectedEvents = statement.executeUpdate();
            log.warn("{} events successfully removed", affectedEvents);
        } catch (Exception e) {
            throw new BrokenStoreException(e);
        }
    }

    private void closeQuietly(AutoCloseable... resources) {
        for (AutoCloseable resource : resources) {
            if (resource != null) {
                try {
                    resource.close();
                } catch (Exception e) {
                    log.error("Exception during resource #close", e);
                }
            }
        }
    }
}
