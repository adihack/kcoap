package dev.kcoap.coap.stack

/**
 * A doubly linked list whose [Node]s the caller keeps, so that removing any element is O(1) and the oldest is
 * always at the head, on every target. The bounded caches that evict their oldest entries use it instead of a
 * `LinkedHashMap`, whose head moves past tombstones on Kotlin/Native and JS after many removals at the front.
 * Not thread safe.
 */
internal class LinkedChain<T : Any> {
    /** One element's place in a chain; [isLinked] while it is in one. */
    class Node<T : Any>(val value: T) {
        internal var previous: Node<T>? = null
        internal var next: Node<T>? = null
        internal var owner: LinkedChain<T>? = null

        val isLinked: Boolean get() = owner != null
    }

    private var head: Node<T>? = null
    private var tail: Node<T>? = null

    /** How many elements are linked. */
    var size: Int = 0
        private set

    /** The oldest element, or null when the chain is empty. */
    fun first(): T? = head?.value

    /** Appends [value] and returns its node, for [remove]. */
    fun addLast(value: T): Node<T> {
        val node = Node(value)
        node.owner = this
        node.previous = tail
        tail?.next = node
        tail = node
        if (head == null) head = node
        size++
        return node
    }

    /** Unlinks [node] if it is in this chain; returns whether it was. */
    fun remove(node: Node<T>): Boolean {
        if (node.owner !== this) return false
        node.previous?.let { it.next = node.next } ?: run { head = node.next }
        node.next?.let { it.previous = node.previous } ?: run { tail = node.previous }
        node.previous = null
        node.next = null
        node.owner = null
        size--
        return true
    }

    /** Unlinks and returns the oldest element, or null when the chain is empty. */
    fun removeFirst(): T? {
        val node = head ?: return null
        remove(node)
        return node.value
    }

    /** The elements, oldest first, as a list; for the rare walks over all of them. */
    fun toList(): List<T> {
        val list = ArrayList<T>(size)
        var node = head
        while (node != null) {
            list += node.value
            node = node.next
        }
        return list
    }
}
