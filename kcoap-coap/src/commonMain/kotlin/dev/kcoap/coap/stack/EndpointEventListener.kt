package dev.kcoap.coap.stack

/**
 * Receives [EndpointEvent]s. Called synchronously on the endpoint's coroutines, outside its locks, so it must be
 * quick and must not throw; exceptions are caught and logged. Hand the work to a queue or count with atomics; for
 * several listeners, combine them with [plus].
 *
 * ```
 * val listener = EndpointEventListener { event -> if (event is EndpointEvent.RequestCompleted) metrics.record(event) } +
 *     EndpointEventListener { event -> if (event is EndpointEvent.LimitReached) alerts.raise(event.limit) }
 * ```
 */
public fun interface EndpointEventListener {
    /** Called for every event. */
    public fun onEvent(event: EndpointEvent)

    public companion object {
        /** Ignores all events. */
        public val None: EndpointEventListener = EndpointEventListener { }
    }
}

/**
 * Sends each event to this listener and then to [other], as `LogSink.plus` does for log records. A listener that
 * throws does not keep the event from the others: each is called, and the first exception is thrown afterwards,
 * for the endpoint to log. [EndpointEventListener.None] adds nothing.
 */
public operator fun EndpointEventListener.plus(other: EndpointEventListener): EndpointEventListener = when {
    this === EndpointEventListener.None -> other
    other === EndpointEventListener.None -> this
    else -> CompositeEventListener(listOf(this, other))
}

/** Calls [listeners] in order, each even when one before it threw; flattens nested composites. */
private class CompositeEventListener(listeners: List<EndpointEventListener>) : EndpointEventListener {
    private val listeners: List<EndpointEventListener> =
        listeners.flatMap { if (it is CompositeEventListener) it.listeners else listOf(it) }

    override fun onEvent(event: EndpointEvent) {
        var failure: Exception? = null
        for (listener in listeners) {
            try {
                listener.onEvent(event)
            } catch (e: Exception) {
                if (failure == null) failure = e else failure.addSuppressed(e)
            }
        }
        failure?.let { throw it }
    }

    override fun toString(): String = "EndpointEventListener(${listeners.size} listeners)"
}
