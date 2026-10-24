package dev.sidebike.core.util

import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicLong

/** Input stream that counts every byte handed to the caller. */
class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    private val counter = AtomicLong(0)

    val count: Long get() = counter.get()

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) counter.incrementAndGet()
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) counter.addAndGet(n.toLong())
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) counter.addAndGet(skipped)
        return skipped
    }
}

/** Output stream that counts every byte written. */
class CountingOutputStream(output: OutputStream) : FilterOutputStream(output) {
    private val counter = AtomicLong(0)

    val count: Long get() = counter.get()

    override fun write(b: Int) {
        out.write(b)
        counter.incrementAndGet()
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        out.write(b, off, len)
        if (len > 0) counter.addAndGet(len.toLong())
    }

    override fun flush() = out.flush()

    override fun close() = out.close()
}
