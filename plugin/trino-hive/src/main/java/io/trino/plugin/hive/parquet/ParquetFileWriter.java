/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.hive.parquet;

import com.google.common.collect.ImmutableList;
import io.trino.parquet.ParquetDataSource;
import io.trino.parquet.writer.ParquetWriter;
import io.trino.parquet.writer.ParquetWriterOptions;
import io.trino.plugin.hive.FileWriter;
import io.trino.spi.Page;
import io.trino.spi.TrinoException;
import io.trino.spi.block.Block;
import io.trino.spi.block.BlockBuilder;
import io.trino.spi.block.RunLengthEncodedBlock;
import io.trino.spi.type.Type;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.schema.MessageType;
import org.joda.time.DateTimeZone;
import org.openjdk.jol.info.ClassLayout;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Supplier;

import static com.google.common.base.MoreObjects.toStringHelper;
import static io.trino.parquet.ParquetWriteValidation.ParquetWriteValidationBuilder;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_WRITER_CLOSE_ERROR;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_WRITER_DATA_ERROR;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_WRITE_VALIDATION_FAILED;
import static java.util.Objects.requireNonNull;

public class ParquetFileWriter
        implements FileWriter
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(ParquetFileWriter.class).instanceSize();
    private static final ThreadMXBean THREAD_MX_BEAN = ManagementFactory.getThreadMXBean();

    private final ParquetWriter parquetWriter;
    private final Callable<Void> rollbackAction;
    private final int[] fileInputColumnIndexes;
    private final List<Block> nullBlocks;
    private final Optional<Supplier<ParquetDataSource>> validationInputFactory;
    private long validationCpuNanos;

    public ParquetFileWriter(
            OutputStream outputStream,
            Callable<Void> rollbackAction,
            List<Type> fileColumnTypes,
            List<String> fileColumnNames,
            MessageType messageType,
            Map<List<String>, Type> primitiveTypes,
            ParquetWriterOptions parquetWriterOptions,
            int[] fileInputColumnIndexes,
            CompressionCodecName compressionCodecName,
            String trinoVersion,
            Optional<DateTimeZone> parquetTimeZone,
            Optional<Supplier<ParquetDataSource>> validationInputFactory)
    {
        requireNonNull(outputStream, "outputStream is null");
        requireNonNull(trinoVersion, "trinoVersion is null");
        this.validationInputFactory = requireNonNull(validationInputFactory, "validationInputFactory is null");

        this.parquetWriter = new ParquetWriter(
                outputStream,
                messageType,
                primitiveTypes,
                parquetWriterOptions,
                compressionCodecName,
                trinoVersion,
                parquetTimeZone,
                validationInputFactory.isPresent()
                        ? Optional.of(new ParquetWriteValidationBuilder(fileColumnTypes, fileColumnNames))
                        : Optional.empty());

        this.rollbackAction = requireNonNull(rollbackAction, "rollbackAction is null");
        this.fileInputColumnIndexes = requireNonNull(fileInputColumnIndexes, "fileInputColumnIndexes is null");

        ImmutableList.Builder<Block> nullBlocks = ImmutableList.builder();
        for (Type fileColumnType : fileColumnTypes) {
            BlockBuilder blockBuilder = fileColumnType.createBlockBuilder(null, 1, 0);
            blockBuilder.appendNull();
            nullBlocks.add(blockBuilder.build());
        }
        this.nullBlocks = nullBlocks.build();
    }

    @Override
    public long getWrittenBytes()
    {
        return parquetWriter.getWrittenBytes() + parquetWriter.getBufferedBytes();
    }

    @Override
    public long getMemoryUsage()
    {
        return INSTANCE_SIZE + parquetWriter.getRetainedBytes();
    }

    @Override
    public void appendRows(Page dataPage)
    {
        Block[] blocks = new Block[fileInputColumnIndexes.length];
        for (int i = 0; i < fileInputColumnIndexes.length; i++) {
            int inputColumnIndex = fileInputColumnIndexes[i];
            if (inputColumnIndex < 0) {
                blocks[i] = new RunLengthEncodedBlock(nullBlocks.get(i), dataPage.getPositionCount());
            }
            else {
                blocks[i] = dataPage.getBlock(inputColumnIndex);
            }
        }
        Page page = new Page(dataPage.getPositionCount(), blocks);
        try {
            parquetWriter.write(page);
        }
        catch (IOException | UncheckedIOException e) {
            throw new TrinoException(HIVE_WRITER_DATA_ERROR, e);
        }
    }

    @Override
    public void commit()
    {
        try {
            parquetWriter.close();
        }
        catch (IOException | UncheckedIOException e) {
            try {
                rollbackAction.call();
            }
            catch (Exception ignored) {
                // ignore
            }
            throw new TrinoException(HIVE_WRITER_CLOSE_ERROR, "Error committing write parquet to Hive", e);
        }

        if (validationInputFactory.isPresent()) {
            try {
                try (ParquetDataSource input = validationInputFactory.get().get()) {
                    long startThreadCpuTime = THREAD_MX_BEAN.getCurrentThreadCpuTime();
                    parquetWriter.validate(input);
                    validationCpuNanos += THREAD_MX_BEAN.getCurrentThreadCpuTime() - startThreadCpuTime;
                }
            }
            catch (IOException | UncheckedIOException e) {
                throw new TrinoException(HIVE_WRITE_VALIDATION_FAILED, e);
            }
        }
    }

    @Override
    public void rollback()
    {
        try {
            try {
                parquetWriter.close();
            }
            finally {
                rollbackAction.call();
            }
        }
        catch (Exception e) {
            throw new TrinoException(HIVE_WRITER_CLOSE_ERROR, "Error rolling back write parquet to Hive", e);
        }
    }

    @Override
    public long getValidationCpuNanos()
    {
        return validationCpuNanos;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("writer", parquetWriter)
                .toString();
    }
}
