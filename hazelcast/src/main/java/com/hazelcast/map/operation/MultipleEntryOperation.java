package com.hazelcast.map.operation;

import com.hazelcast.core.EntryEventType;
import com.hazelcast.core.ManagedContext;
import com.hazelcast.map.EntryBackupProcessor;
import com.hazelcast.map.EntryProcessor;
import com.hazelcast.map.MapEntrySet;
import com.hazelcast.map.MapEntrySimple;
import com.hazelcast.map.RecordStore;
import com.hazelcast.map.SimpleEntryView;
import com.hazelcast.map.record.Record;
import com.hazelcast.monitor.impl.LocalMapStatsImpl;
import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.Data;
import com.hazelcast.partition.InternalPartitionService;
import com.hazelcast.spi.BackupAwareOperation;
import com.hazelcast.spi.Operation;
import com.hazelcast.spi.PartitionAwareOperation;
import com.hazelcast.util.Clock;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * date: 19/12/13
 * author: eminn
 */
public class MultipleEntryOperation extends AbstractMapOperation
        implements BackupAwareOperation, PartitionAwareOperation {

    private static final EntryEventType __NO_NEED_TO_FIRE_EVENT = null;
    private EntryProcessor entryProcessor;
    private Set<Data> keys;
    MapEntrySet response;


    public MultipleEntryOperation() {
    }

    public MultipleEntryOperation(String name, Set<Data> keys, EntryProcessor entryProcessor) {
        super(name);
        this.keys = keys;
        this.entryProcessor = entryProcessor;
    }

    public void innerBeforeRun() {
        final ManagedContext managedContext = getNodeEngine().getSerializationService().getManagedContext();
        managedContext.initialize(entryProcessor);
    }

    @Override
    public void run() throws Exception {
        response = new MapEntrySet();
        final InternalPartitionService partitionService = getNodeEngine().getPartitionService();
        final RecordStore recordStore = mapService.getRecordStore(getPartitionId(), name);
        final LocalMapStatsImpl mapStats = mapService.getLocalMapStatsImpl(name);
        MapEntrySimple entry;

        for (Data key : keys) {
            if (partitionService.getPartitionId(key) != getPartitionId())
                continue;
            long start = System.currentTimeMillis();
            Object objectKey = mapService.toObject(key);
            final Map.Entry<Data, Object> mapEntry = recordStore.getMapEntry(key);
            final Object valueBeforeProcess = mapEntry.getValue();
            final Object valueBeforeProcessObject = mapService.toObject(valueBeforeProcess);
            entry = new MapEntrySimple(objectKey, valueBeforeProcessObject);
            final Object result = entryProcessor.process(entry);
            final Object valueAfterProcess = entry.getValue();
            Data dataValue = null;
            if (result != null) {
                dataValue = mapService.toData(result);
                response.add(new AbstractMap.SimpleImmutableEntry<Data, Data>(key, dataValue));
            }
            EntryEventType eventType;
            if (valueAfterProcess == null) {
                recordStore.remove(key);
                mapStats.incrementRemoves(getLatencyFrom(start));
                eventType = EntryEventType.REMOVED;
            } else {
                if (valueBeforeProcessObject == null) {
                    mapStats.incrementPuts(getLatencyFrom(start));
                    eventType = EntryEventType.ADDED;
                }
                // take this case as a read so no need to fire an event.
                else if (!entry.isModified()) {
                    mapStats.incrementGets(getLatencyFrom(start));
                    eventType = __NO_NEED_TO_FIRE_EVENT;
                } else {
                    mapStats.incrementPuts(getLatencyFrom(start));
                    eventType = EntryEventType.UPDATED;
                }
                // todo if this is a read only operation, record access operations should be done.
                if (eventType != __NO_NEED_TO_FIRE_EVENT) {
                    recordStore.put(new AbstractMap.SimpleImmutableEntry<Data, Object>(key, valueAfterProcess));
                }
            }

            if (eventType != __NO_NEED_TO_FIRE_EVENT) {
                final Data oldValue = mapService.toData(valueBeforeProcess);
                final Data value = mapService.toData(valueAfterProcess);
                mapService.publishEvent(getCallerAddress(), name, eventType, key, oldValue, value);
                if (mapService.isNearCacheAndInvalidationEnabled(name)) {
                    mapService.invalidateAllNearCaches(name, key);
                }
                if (mapContainer.getWanReplicationPublisher() != null && mapContainer.getWanMergePolicy() != null) {
                    if (EntryEventType.REMOVED.equals(eventType)) {
                        mapService.publishWanReplicationRemove(name, key, Clock.currentTimeMillis());
                    } else {
                        Record record = recordStore.getRecord(key);
                        Data tempValue = mapService.toData(dataValue);
                        final SimpleEntryView entryView = mapService.createSimpleEntryView(key, tempValue, record);
                        mapService.publishWanReplicationUpdate(name, entryView);
                    }
                }
            }

        }

    }

    @Override
    public boolean returnsResponse() {
        return true;
    }

    @Override
    public Object getResponse() {
        return response;
    }

    @Override
    public String toString() {
        return "MultipleEntryOperation{}";
    }

    @Override
    public boolean shouldBackup() {
        return entryProcessor.getBackupProcessor() != null;
    }

    @Override
    public int getSyncBackupCount() {
        return 0;
    }

    @Override
    public int getAsyncBackupCount() {
        return mapContainer.getTotalBackupCount();
    }

    @Override
    public Operation getBackupOperation() {
        EntryBackupProcessor backupProcessor = entryProcessor.getBackupProcessor();
        return backupProcessor != null ? new MultipleEntryBackupOperation(name, keys, backupProcessor) : null;
    }

    @Override
    protected void readInternal(ObjectDataInput in) throws IOException {
        super.readInternal(in);
        entryProcessor = in.readObject();
        int size = in.readInt();
        keys = new HashSet<Data>(size);
        for (int i = 0; i < size; i++) {
            Data key = new Data();
            key.readData(in);
            keys.add(key);
        }

    }

    @Override
    protected void writeInternal(ObjectDataOutput out) throws IOException {
        super.writeInternal(out);
        out.writeObject(entryProcessor);
        out.writeInt(keys.size());
        for (Data key : keys) {
            key.writeData(out);
        }

    }

    private long getLatencyFrom(long begin) {
        return Clock.currentTimeMillis() - begin;
    }


}
