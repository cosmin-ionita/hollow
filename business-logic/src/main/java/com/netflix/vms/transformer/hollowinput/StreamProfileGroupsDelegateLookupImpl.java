package com.netflix.vms.transformer.hollowinput;

import com.netflix.hollow.api.objects.delegate.HollowObjectAbstractDelegate;
import com.netflix.hollow.core.read.dataaccess.HollowObjectTypeDataAccess;
import com.netflix.hollow.core.schema.HollowObjectSchema;

@SuppressWarnings("all")
public class StreamProfileGroupsDelegateLookupImpl extends HollowObjectAbstractDelegate implements StreamProfileGroupsDelegate {

    private final StreamProfileGroupsTypeAPI typeAPI;

    public StreamProfileGroupsDelegateLookupImpl(StreamProfileGroupsTypeAPI typeAPI) {
        this.typeAPI = typeAPI;
    }

    public int getGroupNameOrdinal(int ordinal) {
        return typeAPI.getGroupNameOrdinal(ordinal);
    }

    public int getStreamProfileIdsOrdinal(int ordinal) {
        return typeAPI.getStreamProfileIdsOrdinal(ordinal);
    }

    public StreamProfileGroupsTypeAPI getTypeAPI() {
        return typeAPI;
    }

    @Override
    public HollowObjectSchema getSchema() {
        return typeAPI.getTypeDataAccess().getSchema();
    }

    @Override
    public HollowObjectTypeDataAccess getTypeDataAccess() {
        return typeAPI.getTypeDataAccess();
    }

}