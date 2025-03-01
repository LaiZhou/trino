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
package io.trino.plugin.hive;

import com.google.common.annotations.VisibleForTesting;
import io.trino.plugin.hive.HiveWriterFactory.RowIdSortingFileWriterMaker;
import io.trino.plugin.hive.acid.AcidOperation;
import io.trino.plugin.hive.acid.AcidTransaction;
import io.trino.plugin.hive.orc.OrcFileWriterFactory;
import io.trino.spi.Page;
import io.trino.spi.block.Block;
import io.trino.spi.block.RowBlock;
import io.trino.spi.block.RunLengthEncodedBlock;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.MergePage;
import io.trino.spi.type.TypeManager;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.trino.spi.connector.MergePage.createDeleteAndInsertPages;
import static io.trino.spi.type.BigintType.BIGINT;
import static java.util.Objects.requireNonNull;

public class MergeFileWriter
        extends AbstractHiveAcidWriters
        implements FileWriter
{
    private final String partitionName;
    private final List<HiveColumnHandle> inputColumns;

    private int deleteRowCount;
    private int insertRowCount;

    public MergeFileWriter(
            AcidTransaction transaction,
            int statementId,
            OptionalInt bucketNumber,
            RowIdSortingFileWriterMaker sortingFileWriterMaker,
            Path bucketPath,
            Optional<String> partitionName,
            OrcFileWriterFactory orcFileWriterFactory,
            List<HiveColumnHandle> inputColumns,
            Configuration configuration,
            ConnectorSession session,
            TypeManager typeManager,
            HiveType hiveRowType)
    {
        super(transaction,
                statementId,
                bucketNumber,
                Optional.of(sortingFileWriterMaker),
                bucketPath,
                false,
                orcFileWriterFactory,
                configuration,
                session,
                typeManager,
                hiveRowType,
                AcidOperation.MERGE);
        this.partitionName = partitionName.orElse("");
        this.inputColumns = requireNonNull(inputColumns, "inputColumns is null");
    }

    @Override
    public void appendRows(Page page)
    {
        if (page.getPositionCount() == 0) {
            return;
        }

        MergePage mergePage = createDeleteAndInsertPages(page, inputColumns.size());
        mergePage.getDeletionsPage().ifPresent(deletePage -> {
            Block acidBlock = deletePage.getBlock(deletePage.getChannelCount() - 1);
            Page orcDeletePage = buildDeletePage(acidBlock, transaction.getWriteId());
            getOrCreateDeleteFileWriter().appendRows(orcDeletePage);
            deleteRowCount += deletePage.getPositionCount();
        });
        mergePage.getInsertionsPage().ifPresent(insertPage -> {
            Page orcInsertPage = buildInsertPage(insertPage, transaction.getWriteId(), inputColumns, bucketValueBlock, insertRowCount);
            insertRowCount += insertPage.getPositionCount();
            getOrCreateInsertFileWriter().appendRows(orcInsertPage);
            insertRowCount += insertPage.getPositionCount();
        });
    }

    @VisibleForTesting
    public static Page buildInsertPage(Page insertPage, long writeId, List<HiveColumnHandle> columns, Block bucketValueBlock, int insertRowCount)
    {
        int positionCount = insertPage.getPositionCount();
        List<Block> dataColumns = columns.stream()
                .filter(column -> !column.isPartitionKey() && !column.isHidden())
                .map(column -> insertPage.getBlock(column.getBaseHiveColumnIndex()))
                .collect(toImmutableList());
        Block mergedColumnsBlock = RowBlock.fromFieldBlocks(positionCount, Optional.empty(), dataColumns.toArray(new Block[]{}));
        Block currentTransactionBlock = RunLengthEncodedBlock.create(BIGINT, writeId, positionCount);
        Block[] blockArray = {
                new RunLengthEncodedBlock(INSERT_OPERATION_BLOCK, positionCount),
                currentTransactionBlock,
                new RunLengthEncodedBlock(bucketValueBlock, positionCount),
                createRowIdBlock(positionCount, insertRowCount),
                currentTransactionBlock,
                mergedColumnsBlock
        };

        return new Page(blockArray);
    }

    public String getPartitionName()
    {
        return partitionName;
    }

    @Override
    public long getWrittenBytes()
    {
        return deleteFileWriter.map(FileWriter::getWrittenBytes).orElse(0L) +
                insertFileWriter.map(FileWriter::getWrittenBytes).orElse(0L);
    }

    @Override
    public long getMemoryUsage()
    {
        return (deleteFileWriter.map(FileWriter::getMemoryUsage).orElse(0L)) +
                (insertFileWriter.map(FileWriter::getMemoryUsage).orElse(0L));
    }

    @Override
    public void commit()
    {
        deleteFileWriter.ifPresent(FileWriter::commit);
        insertFileWriter.ifPresent(FileWriter::commit);
    }

    @Override
    public void rollback()
    {
        // Make sure both writers get rolled back
        try {
            deleteFileWriter.ifPresent(FileWriter::rollback);
        }
        finally {
            insertFileWriter.ifPresent(FileWriter::rollback);
        }
    }

    @Override
    public long getValidationCpuNanos()
    {
        return (deleteFileWriter.map(FileWriter::getValidationCpuNanos).orElse(0L)) +
                (insertFileWriter.map(FileWriter::getValidationCpuNanos).orElse(0L));
    }

    public PartitionUpdateAndMergeResults getPartitionUpdateAndMergeResults(PartitionUpdate partitionUpdate)
    {
        return new PartitionUpdateAndMergeResults(
                partitionUpdate,
                insertRowCount,
                insertFileWriter.isPresent() ? Optional.of(deltaDirectory.toString()) : Optional.empty(),
                deleteRowCount,
                deleteFileWriter.isPresent() ? Optional.of(deleteDeltaDirectory.toString()) : Optional.empty());
    }
}
