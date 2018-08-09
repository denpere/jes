package io.jes;

import java.util.stream.Stream;
import javax.annotation.Nonnull;

@SuppressWarnings("unused")
public interface JEventStore {

    Stream<Event> readFrom(long offset);

    Stream<Event> readBy(@Nonnull String stream);

    void write(@Nonnull Event event);

}
