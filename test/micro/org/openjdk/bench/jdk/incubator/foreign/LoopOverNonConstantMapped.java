/*
 * Copyright (c) 2020, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.bench.jdk.incubator.foreign;

import jdk.incubator.foreign.MemoryLayout;
import jdk.incubator.foreign.MemorySegment;
import jdk.incubator.foreign.ResourceScope;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import sun.misc.Unsafe;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.VarHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.TimeUnit;

import static jdk.incubator.foreign.MemoryLayout.PathElement.sequenceElement;
import static jdk.incubator.foreign.ValueLayout.JAVA_INT;

@BenchmarkMode(Mode.AverageTime)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@State(org.openjdk.jmh.annotations.Scope.Thread)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgsAppend = { "--add-modules=jdk.incubator.foreign" })
public class LoopOverNonConstantMapped {

    static final Unsafe unsafe = Utils.unsafe;

    static final int ELEM_SIZE = 1_000_000;
    static final int CARRIER_SIZE = (int)JAVA_INT.byteSize();
    static final int ALLOC_SIZE = ELEM_SIZE * CARRIER_SIZE;

    static final Path tempPath;

    static {
        try {
            File file = File.createTempFile("buffer", "txt");
            file.deleteOnExit();
            tempPath = file.toPath();
            Files.write(file.toPath(), new byte[ALLOC_SIZE], StandardOpenOption.WRITE);

        } catch (IOException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    static final VarHandle VH_int = MemoryLayout.sequenceLayout(JAVA_INT).varHandle(sequenceElement());
    MemorySegment segment;
    long unsafe_addr;

    ByteBuffer byteBuffer;

    @Setup
    public void setup() throws IOException {
        try (FileChannel channel = FileChannel.open(tempPath, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            byteBuffer = channel.map(FileChannel.MapMode.READ_WRITE, 0, ALLOC_SIZE).order(ByteOrder.nativeOrder());
            for (int i = 0; i < ELEM_SIZE; i++) {
                byteBuffer.putInt(i * CARRIER_SIZE, i);
            }
            ((MappedByteBuffer)byteBuffer).force();
        }
        segment = MemorySegment.mapFile(tempPath, 0L, ALLOC_SIZE, FileChannel.MapMode.READ_WRITE, ResourceScope.newConfinedScope());
        unsafe_addr = segment.address().toRawLongValue();
    }

    @TearDown
    public void tearDown() {
        segment.scope().close();
        unsafe.invokeCleaner(byteBuffer);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int unsafe_get() {
        return unsafe.getInt(unsafe_addr);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int segment_get() {
        return (int) VH_int.get(segment, 0L);
    }

    @Benchmark
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    public int BB_get() {
        return byteBuffer.getInt(0);
    }

    @Benchmark
    public int unsafe_loop() {
        int res = 0;
        for (int i = 0; i < ELEM_SIZE; i ++) {
            res += unsafe.getInt(unsafe_addr + (i * CARRIER_SIZE));
        }
        return res;
    }

    @Benchmark
    public int segment_loop() {
        int sum = 0;
        for (int i = 0; i < ELEM_SIZE; i++) {
            sum += (int) VH_int.get(segment, (long) i);
        }
        return sum;
    }

    @Benchmark
    public int segment_loop_instance() {
        int res = 0;
        for (int i = 0; i < ELEM_SIZE; i ++) {
            res += segment.get(JAVA_INT, i * CARRIER_SIZE);
        }
        return res;
    }

    @Benchmark
    public int segment_loop_instance_address() {
        int res = 0;
        for (int i = 0; i < ELEM_SIZE; i ++) {
            res += segment.address().get(JAVA_INT, i * CARRIER_SIZE);
        }
        return res;
    }

    @Benchmark
    public int segment_loop_slice() {
        int sum = 0;
        MemorySegment base = segment.asSlice(0, segment.byteSize());
        for (int i = 0; i < ELEM_SIZE; i++) {
            sum += (int) VH_int.get(base, (long) i);
        }
        return sum;
    }

    @Benchmark
    public int segment_loop_readonly() {
        int sum = 0;
        MemorySegment base = segment.asReadOnly();
        for (int i = 0; i < ELEM_SIZE; i++) {
            sum += (int) VH_int.get(base, (long) i);
        }
        return sum;
    }

    @Benchmark
    public int BB_loop() {
        int sum = 0;
        ByteBuffer bb = byteBuffer;
        for (int i = 0; i < ELEM_SIZE; i++) {
            sum += bb.getInt(i * CARRIER_SIZE);
        }
        return sum;
    }

}
