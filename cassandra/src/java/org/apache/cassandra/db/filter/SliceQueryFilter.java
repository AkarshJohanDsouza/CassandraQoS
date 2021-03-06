/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.cassandra.db.filter;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

import org.apache.cassandra.config.CFMetaData;
import org.apache.cassandra.config.ColumnDefinition;
import org.apache.cassandra.utils.ByteBufferUtil;
import org.apache.cassandra.utils.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.cassandra.config.DatabaseDescriptor;
import org.apache.cassandra.db.*;
import org.apache.cassandra.db.columniterator.OnDiskAtomIterator;
import org.apache.cassandra.db.columniterator.SSTableSliceIterator;
import org.apache.cassandra.db.marshal.AbstractType;
import org.apache.cassandra.db.marshal.CompositeType;
import org.apache.cassandra.io.IVersionedSerializer;
import org.apache.cassandra.io.sstable.SSTableReader;
import org.apache.cassandra.io.util.FileDataInput;
import org.apache.cassandra.tracing.Tracing;

public class SliceQueryFilter implements IDiskAtomFilter
{
    private static final Logger logger = LoggerFactory.getLogger(SliceQueryFilter.class);
    public static final Serializer serializer = new Serializer();

    /**
     * A special value for compositesToGroup that indicates that partitioned tombstones should not be included in results
     * or count towards the limit.  See CASSANDRA-8490 for more details on why this is needed (and done this way).
     **/
    public static final int IGNORE_TOMBSTONED_PARTITIONS = -2;

    public final ColumnSlice[] slices;
    public final boolean reversed;
    public volatile int count;
    public final int compositesToGroup;

    // Not serialized, just a ack for range slices to find the number of live column counted, even when we group
    private ColumnCounter columnCounter;

    public SliceQueryFilter(ByteBuffer start, ByteBuffer finish, boolean reversed, int count)
    {
        this(new ColumnSlice[] { new ColumnSlice(start, finish) }, reversed, count);
    }

    public SliceQueryFilter(ByteBuffer start, ByteBuffer finish, boolean reversed, int count, int compositesToGroup)
    {
        this(new ColumnSlice[] { new ColumnSlice(start, finish) }, reversed, count, compositesToGroup);
    }

    /**
     * Constructor that accepts multiple slices. All slices are assumed to be in the same direction (forward or
     * reversed).
     */
    public SliceQueryFilter(ColumnSlice[] slices, boolean reversed, int count)
    {
        this(slices, reversed, count, -1);
    }

    public SliceQueryFilter(ColumnSlice[] slices, boolean reversed, int count, int compositesToGroup)
    {
        this.slices = slices;
        this.reversed = reversed;
        this.count = count;
        this.compositesToGroup = compositesToGroup;
    }

    public SliceQueryFilter cloneShallow()
    {
        return new SliceQueryFilter(slices, reversed, count, compositesToGroup);
    }

    public SliceQueryFilter withUpdatedCount(int newCount)
    {
        return new SliceQueryFilter(slices, reversed, newCount, compositesToGroup);
    }

    public SliceQueryFilter withUpdatedSlices(ColumnSlice[] newSlices)
    {
        return new SliceQueryFilter(newSlices, reversed, count, compositesToGroup);
    }

    /** Returns true if the slice includes static columns, false otherwise. */
    private boolean sliceIncludesStatics(ColumnSlice slice, CFMetaData cfm)
    {
        return cfm.hasStaticColumns() &&
               cfm.getStaticColumnNameBuilder().build().compareTo(reversed ? slice.finish : slice.start) >= 0;
    }

    public boolean hasStaticSlice(CFMetaData cfm)
    {
        for (ColumnSlice slice : slices)
            if (sliceIncludesStatics(slice, cfm))
                return true;

        return false;
    }

    /**
     * Splits this filter into two SliceQueryFilters: one that slices only the static columns, and one that slices the
     * remainder of the normal data.
     *
     * This should only be called when the filter is reversed and the filter is known to cover static columns (through
     * hasStaticSlice()).
     *
     * @return a pair of (static, normal) SliceQueryFilters
     */
    public Pair<SliceQueryFilter, SliceQueryFilter> splitOutStaticSlice(CFMetaData cfm)
    {
        assert reversed;

        ByteBuffer staticSliceEnd = cfm.getStaticColumnNameBuilder().buildAsEndOfRange();
        List<ColumnSlice> nonStaticSlices = new ArrayList<>(slices.length);
        for (ColumnSlice slice : slices)
        {
            if (sliceIncludesStatics(slice, cfm))
                nonStaticSlices.add(new ColumnSlice(slice.start, staticSliceEnd));
            else
                nonStaticSlices.add(slice);
        }

        return Pair.create(
            new SliceQueryFilter(staticSliceEnd, ByteBufferUtil.EMPTY_BYTE_BUFFER, true, count, compositesToGroup),
            new SliceQueryFilter(nonStaticSlices.toArray(new ColumnSlice[nonStaticSlices.size()]), true, count, compositesToGroup));
    }

    public SliceQueryFilter withUpdatedStart(ByteBuffer newStart, CFMetaData cfm)
    {
        Comparator<ByteBuffer> cmp = reversed ? cfm.comparator.reverseComparator : cfm.comparator;

        // Check our slices to see if any fall before the new start (in which case they can be removed) or
        // if they contain the new start (in which case they should start from the page start).  However, if the
        // slices would include static columns, we need to ensure they are also fetched, and so a separate
        // slice for the static columns may be required.
        // Note that if the query is reversed, we can't handle statics by simply adding a separate slice here, so
        // the reversed case is handled by SliceFromReadCommand instead. See CASSANDRA-8502 for more details.
        List<ColumnSlice> newSlices = new ArrayList<>();
        boolean pastNewStart = false;
        for (int i = 0; i < slices.length; i++)
        {
            ColumnSlice slice = slices[i];

            if (pastNewStart)
            {
                newSlices.add(slice);
                continue;
            }

            if (slices[i].isBefore(cmp, newStart))
            {
                if (!reversed && sliceIncludesStatics(slice, cfm))
                    newSlices.add(new ColumnSlice(ByteBufferUtil.EMPTY_BYTE_BUFFER, cfm.getStaticColumnNameBuilder().buildAsEndOfRange()));

                continue;
            }
            else if (slice.includes(cmp, newStart))
            {
                if (!reversed && sliceIncludesStatics(slice, cfm) && !newStart.equals(ByteBufferUtil.EMPTY_BYTE_BUFFER))
                    newSlices.add(new ColumnSlice(ByteBufferUtil.EMPTY_BYTE_BUFFER, cfm.getStaticColumnNameBuilder().buildAsEndOfRange()));

                newSlices.add(new ColumnSlice(newStart, slice.finish));
            }
            else
            {
                newSlices.add(slice);
            }

            pastNewStart = true;
        }
        return withUpdatedSlices(newSlices.toArray(new ColumnSlice[newSlices.size()]));
    }

    public SliceQueryFilter withUpdatedSlice(ByteBuffer start, ByteBuffer finish)
    {
        return new SliceQueryFilter(new ColumnSlice[]{ new ColumnSlice(start, finish) }, reversed, count, compositesToGroup);
    }

    public OnDiskAtomIterator getColumnFamilyIterator(final DecoratedKey key, final ColumnFamily cf)
    {
        assert cf != null;
        final Iterator<Column> filteredIter = reversed ? cf.reverseIterator(slices) : cf.iterator(slices);

        return new OnDiskAtomIterator()
        {
            public ColumnFamily getColumnFamily()
            {
                return cf;
            }

            public DecoratedKey getKey()
            {
                return key;
            }

            public boolean hasNext()
            {
                return filteredIter.hasNext();
            }

            public OnDiskAtom next()
            {
                return filteredIter.next();
            }

            public void close() throws IOException { }

            public void remove()
            {
                throw new UnsupportedOperationException();
            }
        };
    }

    public OnDiskAtomIterator getSSTableColumnIterator(SSTableReader sstable, DecoratedKey key)
    {
        return new SSTableSliceIterator(sstable, key, slices, reversed);
    }

    public OnDiskAtomIterator getSSTableColumnIterator(SSTableReader sstable, FileDataInput file, DecoratedKey key, RowIndexEntry indexEntry)
    {
        return new SSTableSliceIterator(sstable, file, key, slices, reversed, indexEntry);
    }

    public Comparator<Column> getColumnComparator(AbstractType<?> comparator)
    {
        return reversed ? comparator.columnReverseComparator : comparator.columnComparator;
    }

    public void collectReducedColumns(ColumnFamily container, Iterator<Column> reducedColumns, int gcBefore, long now)
    {
        columnCounter = columnCounter(container.getComparator(), now);
        DeletionInfo.InOrderTester tester = container.deletionInfo().inOrderTester(reversed);
        while (reducedColumns.hasNext())
        {
            Column column = reducedColumns.next();
            if (logger.isTraceEnabled())
              /*  logger.trace(String.format("collecting %s of %s: %s",
                                           columnCounter.live(), count, column.getString(container.getComparator()))); */

            columnCounter.count(column, tester);

            if (columnCounter.live() > count)
                break;

            if (respectTombstoneThresholds() && columnCounter.tombstones() > DatabaseDescriptor.getTombstoneFailureThreshold())
            {
                Tracing.trace("Scanned over {} tombstones; query aborted (see tombstone_failure_threshold)", DatabaseDescriptor.getTombstoneFailureThreshold());
                logger.error("Scanned over {} tombstones in {}.{}; query aborted (see tombstone_failure_threshold)",
                             DatabaseDescriptor.getTombstoneFailureThreshold(), container.metadata().ksName, container.metadata().cfName);
                throw new TombstoneOverwhelmingException();
            }

            container.addIfRelevant(column, tester, gcBefore);
        }

        Tracing.trace("Read {} live and {} tombstone cells", columnCounter.live(), columnCounter.tombstones());
        if (respectTombstoneThresholds() && columnCounter.tombstones() > DatabaseDescriptor.getTombstoneWarnThreshold())
        {
            StringBuilder sb = new StringBuilder();
            AbstractType<?> type = container.metadata().comparator;
            for (ColumnSlice sl : slices)
            {
                if (sl == null)
                    continue;

                sb.append('[');
                sb.append(type.getString(sl.start));
                sb.append('-');
                sb.append(type.getString(sl.finish));
                sb.append(']');
            }

            logger.warn("Read {} live and {} tombstone cells in {}.{} (see tombstone_warn_threshold). {} columns was requested, slices={}",
                        columnCounter.live(),
                        columnCounter.tombstones(),
                        container.metadata().ksName,
                        container.metadata().cfName,
                        count,
                        sb);
        }
    }

    protected boolean respectTombstoneThresholds()
    {
        return true;
    }

    public int getLiveCount(ColumnFamily cf, long now)
    {
        return columnCounter(cf.getComparator(), now).countAll(cf).live();
    }

    public ColumnCounter columnCounter(AbstractType<?> comparator, long now)
    {
        if (compositesToGroup < 0)
            return new ColumnCounter(now);
        else if (compositesToGroup == 0)
            return new ColumnCounter.GroupByPrefix(now, null, 0);
        else if (reversed)
            return new ColumnCounter.GroupByPrefixReversed(now, (CompositeType)comparator, compositesToGroup);
        else
            return new ColumnCounter.GroupByPrefix(now, (CompositeType)comparator, compositesToGroup);
    }

    public void trim(ColumnFamily cf, int trimTo, long now)
    {
        // each cell can increment the count by at most one, so if we have fewer cells than trimTo, we can skip trimming
        if (cf.getColumnCount() < trimTo)
            return;

        ColumnCounter counter = columnCounter(cf.getComparator(), now);

        Collection<Column> columns = reversed
                                   ? cf.getReverseSortedColumns()
                                   : cf.getSortedColumns();

        DeletionInfo.InOrderTester tester = cf.deletionInfo().inOrderTester(reversed);

        for (Iterator<Column> iter = columns.iterator(); iter.hasNext(); )
        {
            Column column = iter.next();
            counter.count(column, tester);

            if (counter.live() > trimTo)
            {
                iter.remove();
                while (iter.hasNext())
                {
                    iter.next();
                    iter.remove();
                }
            }
        }
    }

    public ByteBuffer start()
    {
        return this.slices[0].start;
    }

    public ByteBuffer finish()
    {
        return this.slices[slices.length - 1].finish;
    }

    public void setStart(ByteBuffer start)
    {
        assert slices.length == 1;
        this.slices[0] = new ColumnSlice(start, this.slices[0].finish);
    }

    public int lastCounted()
    {
        // If we have a slice limit set, columnCounter.live() can overcount by one because we have to call
        // columnCounter.count() before we can tell if we've exceeded the slice limit (and accordingly, should not
        // add the cells to returned container).  To deal with this overcounting, we take the min of the slice
        // limit and the counter's count.
        return columnCounter == null ? 0 : Math.min(columnCounter.live(), count);
    }

    public int lastTombstones()
    {
        return columnCounter == null ? 0 : columnCounter.tombstones();
    }

    public int lastLive()
    {
        return columnCounter == null ? 0 : columnCounter.live();
    }

    @Override
    public String toString()
    {
        return "SliceQueryFilter [reversed=" + reversed + ", slices=" + Arrays.toString(slices) + ", count=" + count + ", toGroup = " + compositesToGroup + "]";
    }

    public boolean isReversed()
    {
        return reversed;
    }

    public void updateColumnsLimit(int newLimit)
    {
        count = newLimit;
    }

    public boolean maySelectPrefix(Comparator<ByteBuffer> cmp, ByteBuffer prefix)
    {
        for (ColumnSlice slice : slices)
            if (slice.includes(cmp, prefix))
                return true;
        return false;
    }

    public boolean shouldInclude(SSTableReader sstable)
    {
        List<ByteBuffer> minColumnNames = sstable.getSSTableMetadata().minColumnNames;
        List<ByteBuffer> maxColumnNames = sstable.getSSTableMetadata().maxColumnNames;
        assert minColumnNames.size() == maxColumnNames.size();
        AbstractType<?> comparator = sstable.metadata.comparator;

        if (minColumnNames.isEmpty() || maxColumnNames.isEmpty())
            return true;

        return comparator.intersects(minColumnNames, maxColumnNames, this);
    }

    public static class Serializer implements IVersionedSerializer<SliceQueryFilter>
    {
        public void serialize(SliceQueryFilter f, DataOutput out, int version) throws IOException
        {
            out.writeInt(f.slices.length);
            for (ColumnSlice slice : f.slices)
                ColumnSlice.serializer.serialize(slice, out, version);
            out.writeBoolean(f.reversed);
            int count = f.count;
            out.writeInt(count);

            out.writeInt(f.compositesToGroup);
        }

        public SliceQueryFilter deserialize(DataInput in, int version) throws IOException
        {
            ColumnSlice[] slices;
            slices = new ColumnSlice[in.readInt()];
            for (int i = 0; i < slices.length; i++)
                slices[i] = ColumnSlice.serializer.deserialize(in, version);
            boolean reversed = in.readBoolean();
            int count = in.readInt();
            int compositesToGroup = -1;
            compositesToGroup = in.readInt();

            return new SliceQueryFilter(slices, reversed, count, compositesToGroup);
        }

        public long serializedSize(SliceQueryFilter f, int version)
        {
            TypeSizes sizes = TypeSizes.NATIVE;

            int size = 0;
            size += sizes.sizeof(f.slices.length);
            for (ColumnSlice slice : f.slices)
                size += ColumnSlice.serializer.serializedSize(slice, version);
            size += sizes.sizeof(f.reversed);
            size += sizes.sizeof(f.count);

            size += sizes.sizeof(f.compositesToGroup);
            return size;
        }
    }
}
