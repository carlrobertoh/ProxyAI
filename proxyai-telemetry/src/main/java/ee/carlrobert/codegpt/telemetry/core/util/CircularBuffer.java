/**
 * MIT License
 * <p>
 * Copyright (c) 2017 Eugen Paraschiv
 * <p>
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * <p>
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * <p>
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 * <p>
 * Based on work by Eugen Paraschiv for Baeldung.com at
 * https://github.com/eugenp/tutorials/blob/master/data-structures/src/main/java/com/baeldung/circularbuffer/CircularBuffer.java
 */
package ee.carlrobert.codegpt.telemetry.core.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class CircularBuffer<E> {

    private static final int DEFAULT_CAPACITY = 8;

    private final int capacity;
    private final E[] data;
    private volatile int writeSequence = -1;
    private volatile int readSequence = 0;

    @SuppressWarnings("unchecked")
    public CircularBuffer(int capacity) {
        this.capacity = (capacity < 1) ? DEFAULT_CAPACITY : capacity;
        this.data = (E[]) new Object[capacity];
    }

    public boolean offer(E element) {
        if (!isFull()) {
            int nextWriteSeq = writeSequence + 1;
            data[nextWriteSeq % capacity] = element;

            writeSequence++;
            return true;
        }
        return false;
    }

    public E poll() {
        if (!isEmpty()) {
            E nextValue = data[readSequence % capacity];
            readSequence++;
            return nextValue;
        }

        return null;
    }

    public List<E> pollAll() {
        List<E> values = new ArrayList<>();
        for(E value = poll(); value != null; value = poll()) {
            values.add(value);
        }
        return values;
    }

    public int capacity() {
        return capacity;
    }

    public int size() {
        return (writeSequence - readSequence) + 1;
    }

    public boolean isEmpty() {
        return writeSequence < readSequence;
    }

    public boolean isFull() {
        return size() >= capacity;
    }

    public void clear() {
        readSequence = 0;
        writeSequence = -1;
        Arrays.fill(data, null);
    }
}
